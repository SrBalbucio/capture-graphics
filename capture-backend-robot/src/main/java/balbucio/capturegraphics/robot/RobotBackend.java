package balbucio.capturegraphics.robot;

import balbucio.capturegraphics.api.Backpressure;
import balbucio.capturegraphics.api.Capabilities;
import balbucio.capturegraphics.api.CaptureBackend;
import balbucio.capturegraphics.api.CaptureConfig;
import balbucio.capturegraphics.api.CaptureException;
import balbucio.capturegraphics.api.CaptureSession;
import balbucio.capturegraphics.api.DisplayId;
import balbucio.capturegraphics.api.FrameFormat;
import balbucio.capturegraphics.api.FrameListener;
import balbucio.capturegraphics.api.FrameMeta;
import balbucio.capturegraphics.api.Rect;
import balbucio.capturegraphics.api.SessionMetrics;
import balbucio.capturegraphics.core.FramePool;
import balbucio.capturegraphics.core.MetricsTracker;

import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.lang.System.Logger;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Pure-JDK fallback backend. Baseline for benchmarks and for environments
 * without DXGI. Slow by design (GDI + ARGB conversion); the DXGI backend must beat it.
 */
public final class RobotBackend implements CaptureBackend {
    private static final Logger LOG = System.getLogger(RobotBackend.class.getName());

    @Override
    public String id() {
        return "robot";
    }

    @Override
    public Capabilities capabilities() {
        var size = Toolkit.getDefaultToolkit().getScreenSize();
        return Capabilities.robotFallback(size.width, size.height);
    }

    @Override
    public List<DisplayId> displays() {
        var size = Toolkit.getDefaultToolkit().getScreenSize();
        return List.of(DisplayId.primary(size.width, size.height));
    }

    @Override
    public CaptureSession open(CaptureConfig config) throws CaptureException {
        if (GraphicsEnvironment.isHeadless()) {
            throw new CaptureException(CaptureException.Reason.ACCESS_DENIED, "Headless environment");
        }
        if (config.format() != FrameFormat.BGRA_8) {
            throw new CaptureException(CaptureException.Reason.UNSUPPORTED_FORMAT, "Robot supports only BGRA_8");
        }
        try {
            var size = Toolkit.getDefaultToolkit().getScreenSize();
            DisplayId display = config.display() != null
                    ? config.display()
                    : DisplayId.primary(size.width, size.height);
            Robot robot = new Robot();
            FramePool pool = new FramePool(display.width(), display.height(),
                    FrameFormat.BGRA_8, Math.max(2, config.framePoolSize()));
            LOG.log(Logger.Level.INFO, "Robot backend opened {0}x{1}", display.width(), display.height());
            return new RobotSession(robot, display, config, pool);
        } catch (Exception e) {
            throw new CaptureException(CaptureException.Reason.NATIVE_ERROR, "Cannot init Robot", e);
        }
    }

    private static final class RobotSession implements CaptureSession {
        private final Robot robot;
        private final Rectangle area;
        private final DisplayId display;
        private final CaptureConfig config;
        private final FramePool pool;
        private final MetricsTracker metrics = new MetricsTracker();
        private final AtomicLong seq = new AtomicLong(0);
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final AtomicReference<PushLoop> push = new AtomicReference<>();
        private final int[] rgbCache;

        RobotSession(Robot robot, DisplayId display, CaptureConfig config, FramePool pool) {
            this.robot = robot;
            this.display = display;
            this.config = config;
            this.pool = pool;
            this.area = new Rectangle(display.width(), display.height());
            this.rgbCache = new int[display.width() * display.height()];
        }

        @Override
        public FramePool.PooledFrame acquire() throws CaptureException {
            if (closed.get()) {
                throw new CaptureException(CaptureException.Reason.CLOSED, "Session closed");
            }
            long t0 = System.nanoTime();
            BufferedImage img = robot.createScreenCapture(area);
            img.getRGB(0, 0, display.width(), display.height(), rgbCache, 0, display.width());
            var frame = pool.take();
            ByteBuffer dst = frame.writable();
            // ARGB int -> BGRA bytes. GDI order, no alpha tricks.
            for (int argb : rgbCache) {
                dst.put((byte) (argb));
                dst.put((byte) (argb >> 8));
                dst.put((byte) (argb >> 16));
                dst.put((byte) (argb >> 24));
            }
            dst.flip();
            long s = seq.getAndIncrement();
            long duration = config.targetFps() > 0 ? 1_000_000_000L / config.targetFps() : 16_666_666L;
            frame.withMeta(new FrameMeta(s, System.nanoTime(), duration, display, 0, List.of(), true));
            metrics.onDelivered(System.nanoTime() - t0, 0);
            return frame;
        }

        @Override
        public void onFrame(FrameListener listener, Backpressure backpressure) {
            clearListener();
            PushLoop loop = new PushLoop(listener, backpressure);
            push.set(loop);
            Thread t = new Thread(loop, "capture-robot-push");
            t.setDaemon(true);
            loop.thread = t;
            t.start();
        }

        @Override
        public void clearListener() {
            PushLoop old = push.getAndSet(null);
            if (old != null) {
                old.stop();
            }
        }

        @Override
        public SessionMetrics metrics() {
            return metrics.snapshot();
        }

        @Override
        public DisplayId display() {
            return display;
        }

        @Override
        public CaptureConfig config() {
            return config;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                clearListener();
            }
        }

        private final class PushLoop implements Runnable {
            private final FrameListener listener;
            private final Backpressure bp;
            private volatile boolean running = true;
            private volatile boolean busy = false;
            private Thread thread;

            PushLoop(FrameListener listener, Backpressure bp) {
                this.listener = listener;
                this.bp = bp;
            }

            void stop() {
                running = false;
                if (thread != null) {
                    thread.interrupt();
                }
            }

            @Override
            public void run() {
                while (running && !closed.get()) {
                    try {
                        var f = acquire();
                        if (busy && bp == Backpressure.DROP_NEWEST) {
                            f.close();
                            metrics.onDrop(1);
                            continue;
                        }
                        busy = true;
                        try {
                            listener.onFrame(f); // listener owns close
                        } finally {
                            busy = false;
                        }
                    } catch (CaptureException e) {
                        if (running) {
                            System.getLogger(RobotSession.class.getName())
                                    .log(Logger.Level.WARNING, "acquire failed: {0}", e.getMessage());
                        }
                    }
                }
            }
        }
    }
}
