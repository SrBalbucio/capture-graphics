package balbucio.capturegraphics.robot;

import balbucio.capturegraphics.api.Backpressure;
import balbucio.capturegraphics.api.Capabilities;
import balbucio.capturegraphics.api.CaptureBackend;
import balbucio.capturegraphics.api.CaptureConfig;
import balbucio.capturegraphics.api.CaptureException;
import balbucio.capturegraphics.api.CaptureSession;
import balbucio.capturegraphics.api.DisplayId;
import balbucio.capturegraphics.api.Frame;
import balbucio.capturegraphics.api.FrameFormat;
import balbucio.capturegraphics.api.FrameListener;
import balbucio.capturegraphics.api.FrameMeta;
import balbucio.capturegraphics.api.Rect;
import balbucio.capturegraphics.api.SessionMetrics;
import balbucio.capturegraphics.core.FramePool;
import balbucio.capturegraphics.core.MetricsTracker;
import balbucio.capturegraphics.core.RetainedLatest;

import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.lang.System.Logger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Pure-JDK fallback backend. Baseline for benchmarks and for environments
 * without DXGI. Slow by design (GDI capture); the DXGI backend must beat it.
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
        if (GraphicsEnvironment.isHeadless()) {
            return List.of();
        }
        GraphicsDevice[] devices =
                GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices();
        List<DisplayId> out = new ArrayList<>(devices.length);
        for (int i = 0; i < devices.length; i++) {
            Rectangle b = devices[i].getDefaultConfiguration().getBounds();
            out.add(new DisplayId("robot-output-" + i,
                    devices[i].getIDstring() + (i == 0 ? " (primary)" : ""),
                    b.x, b.y, b.width, b.height, false));
        }
        return List.copyOf(out);
    }

    /** Parses {@code robot-output-N} ids; anything else (or null) means device 0. */
    static int deviceIndex(DisplayId display) {
        if (display == null || display.id() == null) {
            return 0;
        }
        String id = display.id().trim();
        if (id.startsWith("robot-output-")) {
            try {
                return Math.max(0, Integer.parseInt(id.substring("robot-output-".length())));
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        if (id.startsWith("dxgi-output-")) {
            try {
                return Math.max(0, Integer.parseInt(id.substring("dxgi-output-".length())));
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
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
            GraphicsDevice[] devices =
                    GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices();
            int index = Math.min(deviceIndex(config.display()), devices.length - 1);
            Rectangle bounds = devices[index].getDefaultConfiguration().getBounds();
            DisplayId display = new DisplayId("robot-output-" + index,
                    devices[index].getIDstring(), bounds.x, bounds.y,
                    bounds.width, bounds.height, false);
            Rect region = clipRegion(config.region(), display);
            if (region == null) {
                throw new CaptureException(CaptureException.Reason.INVALID_ARG,
                        "Region lies completely outside the display");
            }
            Robot robot = new Robot(devices[index]);
            FramePool pool = new FramePool(region.width(), region.height(),
                    FrameFormat.BGRA_8, Math.max(2, config.framePoolSize()));
            LOG.log(Logger.Level.INFO, "Robot backend opened " + display.width() + "x"
                    + display.height() + (region.width() == display.width()
                    && region.height() == display.height() ? "" : " region " + region.width()
                    + "x" + region.height()));
            return new RobotSession(robot, display, region, config, pool);
        } catch (CaptureException e) {
            throw e;
        } catch (Exception e) {
            throw new CaptureException(CaptureException.Reason.NATIVE_ERROR, "Cannot init Robot", e);
        }
    }

    static Rect clipRegion(Rect requested, DisplayId display) {
        if (requested == null) {
            return new Rect(display.x(), display.y(), display.width(), display.height());
        }
        int x0 = Math.max(display.x(), requested.x());
        int y0 = Math.max(display.y(), requested.y());
        int x1 = Math.min(display.x() + display.width(), requested.x() + requested.width());
        int y1 = Math.min(display.y() + display.height(), requested.y() + requested.height());
        if (x1 <= x0 || y1 <= y0) {
            return null;
        }
        return new Rect(x0, y0, x1 - x0, y1 - y0);
    }

    private static final class RobotSession implements CaptureSession {
        private final Robot robot;
        private final Rectangle area;
        private final DisplayId display;
        private final Rect region;
        private final CaptureConfig config;
        private final FramePool pool;
        private final MetricsTracker metrics = new MetricsTracker();
        private final RetainedLatest retained = new RetainedLatest();
        private final AtomicLong seq = new AtomicLong(0);
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final AtomicReference<PushLoop> push = new AtomicReference<>();

        RobotSession(Robot robot, DisplayId display, Rect region, CaptureConfig config,
                     FramePool pool) {
            this.robot = robot;
            this.display = display;
            this.region = region;
            this.config = config;
            this.pool = pool;
            this.area = new Rectangle(display.x(), display.y(), display.width(), display.height());
        }

        @Override
        public FramePool.PooledFrame acquire() throws CaptureException {
            if (closed.get()) {
                throw new CaptureException(CaptureException.Reason.CLOSED, "Session closed");
            }
            long t0 = System.nanoTime();
            BufferedImage img = robot.createScreenCapture(area);
            var frame = pool.take();
            copyRegion(img, frame.writable(), region, display);
            var buf = frame.writable();
            buf.position(buf.capacity()).flip();
            long s = seq.getAndIncrement();
            long duration = config.targetFps() > 0 ? 1_000_000_000L / config.targetFps() : 16_666_666L;
            frame.withMeta(new FrameMeta(s, System.nanoTime(), duration, display, 0,
                    List.of(Rect.full(region.width(), region.height())), true));
            retained.offer(config.retainLast(), buf, region.width(), region.height(),
                    region.width() * 4, frame.meta());
            metrics.onDelivered(System.nanoTime() - t0, 0);
            return frame;
        }

        /**
         * Copies the region from a screen capture into the pooled buffer.
         * Fast path: INT_ARGB bank ints are 0xAARRGGBB, whose little-endian bytes
         * are exactly B,G,R,A — one bulk transfer, zero swizzle.
         */
        static void copyRegion(BufferedImage img, ByteBuffer dst, Rect region, DisplayId display) {
            int rx = region.x() - display.x();
            int ry = region.y() - display.y();
            int rw = region.width();
            if (img.getRaster().getDataBuffer() instanceof DataBufferInt dbi) {
                int[] bank = dbi.getData();
                var ints = dst.duplicate().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
                ints.clear();
                for (int y = 0; y < region.height(); y++) {
                    ints.put(bank, (ry + y) * display.width() + rx, rw);
                }
                return;
            }
            int[] row = new int[rw];
            var ints = dst.duplicate().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
            ints.clear();
            for (int y = 0; y < region.height(); y++) {
                img.getRGB(rx, ry + y, rw, 1, row, 0, rw);
                ints.put(row);
            }
        }

        @Override
        public Optional<Frame> latest() {
            return retained.view();
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
                                    .log(Logger.Level.WARNING,
                                            "acquire failed: " + e.getMessage());
                        }
                    }
                }
            }
        }
    }
}
