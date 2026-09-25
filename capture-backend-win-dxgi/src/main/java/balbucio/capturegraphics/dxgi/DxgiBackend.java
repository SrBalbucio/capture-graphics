package balbucio.capturegraphics.dxgi;

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
import balbucio.capturegraphics.api.SessionMetrics;
import balbucio.capturegraphics.core.FramePool;
import balbucio.capturegraphics.core.MetricsTracker;

import java.awt.Toolkit;
import java.lang.System.Logger;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Windows DXGI Desktop Duplication backend (MVP: output 0, BGRA_8, full frames).
 *
 * <p>Frame path per {@code acquire()}: native staging {@code Map} + row memcpy into the
 * pooled direct buffer — 1 copy total, zero object allocation in steady state.
 * Presentation timestamps come from QPC captured natively and converted to the
 * {@link System#nanoTime()} domain once per session.
 */
public final class DxgiBackend implements CaptureBackend {
    private static final Logger LOG = System.getLogger(DxgiBackend.class.getName());

    @Override
    public String id() {
        return "win-dxgi";
    }

    @Override
    public Capabilities capabilities() {
        var size = Toolkit.getDefaultToolkit().getScreenSize();
        return new Capabilities(Set.of(FrameFormat.BGRA_8), false, false,
                size.width, size.height, false);
    }

    @Override
    public List<DisplayId> displays() {
        var size = Toolkit.getDefaultToolkit().getScreenSize();
        return List.of(new DisplayId("dxgi-output-0", "Primary Display (DXGI output 0)",
                size.width, size.height));
    }

    @Override
    public CaptureSession open(CaptureConfig config) throws CaptureException {
        if (config.format() != FrameFormat.BGRA_8) {
            throw new CaptureException(CaptureException.Reason.UNSUPPORTED_FORMAT,
                    "DXGI MVP supports only BGRA_8");
        }
        try {
            NativeLibLoader.ensureLoaded();
        } catch (IllegalStateException e) {
            throw new CaptureException(CaptureException.Reason.NATIVE_ERROR,
                    "Native library unavailable: " + e.getMessage(), e);
        }
        final long handle;
        try {
            handle = DxgiNative.nOpen(0, 0);
        } catch (UnsatisfiedLinkError | IllegalStateException e) {
            throw new CaptureException(CaptureException.Reason.NATIVE_ERROR,
                    "cg_dxgi_open failed: " + e.getMessage(), e);
        }
        if (handle == 0) {
            throw new CaptureException(CaptureException.Reason.NATIVE_ERROR, "cg_dxgi_open failed");
        }
        int[] info = DxgiNative.nInfo(handle);
        DisplayId display = config.display();
        int w = display != null ? display.width() : 0;
        int h = display != null ? display.height() : 0;
        if (info != null && info[0] > 0 && info[1] > 0) {
            w = info[0];
            h = info[1];
        }
        if (w <= 0 || h <= 0) {
            DxgiNative.nClose(handle);
            throw new CaptureException(CaptureException.Reason.NATIVE_ERROR,
                    "Could not determine display size");
        }
        long freq = DxgiNative.nQpcFrequency();
        if (freq <= 0) {
            DxgiNative.nClose(handle);
            throw new CaptureException(CaptureException.Reason.NATIVE_ERROR, "QPC unavailable");
        }
        DisplayId resolved = new DisplayId("dxgi-output-0", "Primary Display (DXGI output 0)", w, h);
        FramePool pool = new FramePool(w, h, FrameFormat.BGRA_8, Math.max(2, config.framePoolSize()));
        LOG.log(Logger.Level.INFO, "DXGI backend opened {0}x{1}", w, h);
        return new DxgiSession(handle, resolved, config, pool, freq);
    }

    private static final class DxgiSession implements CaptureSession {
        private final long handle;
        private final DisplayId display;
        private final CaptureConfig config;
        private final FramePool pool;
        private final int stride;
        private final MetricsTracker metrics = new MetricsTracker();
        private final AtomicLong seq = new AtomicLong(0);
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final AtomicReference<PushLoop> push = new AtomicReference<>();
        private final long qpcFreq;
        private final long durationNanos;
        private final long[] qpcOut = new long[1];
        private long lastQpc = 0;

        DxgiSession(long handle, DisplayId display, CaptureConfig config, FramePool pool, long qpcFreq) {
            this.handle = handle;
            this.display = display;
            this.config = config;
            this.pool = pool;
            this.stride = display.width() * 4;
            this.qpcFreq = qpcFreq;
            this.durationNanos = config.targetFps() > 0 ? 1_000_000_000L / config.targetFps() : 16_666_666L;
        }

        private long baseQpc = 0;
        private long baseNano = 0;

        @Override
        public synchronized FramePool.PooledFrame acquire() throws CaptureException {
            if (closed.get()) {
                throw new CaptureException(CaptureException.Reason.CLOSED, "Session closed");
            }
            long t0 = System.nanoTime();
            var frame = pool.take();
            int rc = DxgiNative.nAcquire(handle, frame.writable(), stride, config.timeoutMs(), qpcOut);
            if (rc == DxgiNative.CG_TIMEOUT) {
                frame.close();
                return null;
            }
            if (rc != DxgiNative.CG_OK) {
                frame.close();
                throw toException(rc);
            }
            // Native wrote via raw pointer (position untouched): expose full buffer.
            var buf = frame.writable();
            buf.position(buf.capacity()).flip();
            long qpc = qpcOut[0];
            if (baseQpc == 0) {
                baseQpc = qpc;
                baseNano = t0;
            }
            long pts = baseNano + ((qpc - baseQpc) * 1_000_000_000L) / qpcFreq;
            long dropped = 0;
            if (lastQpc != 0 && durationNanos > 0) {
                long gapNanos = ((qpc - lastQpc) * 1_000_000_000L) / qpcFreq;
                dropped = Math.max(0, Math.round((double) gapNanos / durationNanos) - 1);
            }
            lastQpc = qpc;
            long s = seq.getAndIncrement();
            frame.withMeta(new FrameMeta(s, pts, durationNanos, display, dropped, List.of(), true));
            metrics.onDelivered(System.nanoTime() - t0, dropped);
            return frame;
        }

        private CaptureException toException(int rc) {
            String detail = DxgiNative.nLastError(handle);
            return switch (rc) {
                case DxgiNative.CG_ERR_ACCESS_LOST -> new CaptureException(
                        CaptureException.Reason.MODE_CHANGED, "DXGI access lost (reopen): " + detail);
                case DxgiNative.CG_ERR_DEVICE_REMOVED -> new CaptureException(
                        CaptureException.Reason.DEVICE_LOST, "DXGI device removed (reopen): " + detail);
                default -> new CaptureException(CaptureException.Reason.NATIVE_ERROR,
                        "DXGI acquire failed rc=" + rc + ": " + detail);
            };
        }

        @Override
        public void onFrame(FrameListener listener, Backpressure backpressure) {
            clearListener();
            PushLoop loop = new PushLoop(listener, backpressure);
            push.set(loop);
            Thread t = new Thread(loop, "capture-dxgi-push");
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
        public synchronized void close() {
            if (closed.compareAndSet(false, true)) {
                clearListener();
                DxgiNative.nClose(handle);
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
                        if (f == null) {
                            continue; // timeout: poll again
                        }
                        if (busy && bp == Backpressure.DROP_NEWEST) {
                            f.close();
                            metrics.onDrop(1);
                            continue;
                        }
                        busy = true;
                        try {
                            listener.onFrame(f);
                        } finally {
                            busy = false;
                        }
                    } catch (CaptureException e) {
                        if (running && e.reason() != CaptureException.Reason.CLOSED) {
                            LOG.log(Logger.Level.WARNING, "DXGI acquire failed: {0}", e.getMessage());
                        }
                        if (e.reason() == CaptureException.Reason.DEVICE_LOST
                                || e.reason() == CaptureException.Reason.MODE_CHANGED) {
                            return; // fatal: consumer must reopen
                        }
                    }
                }
            }
        }
    }
}
