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
import balbucio.capturegraphics.api.GpuCaptureSession;
import balbucio.capturegraphics.api.Rect;
import balbucio.capturegraphics.api.SessionMetrics;
import balbucio.capturegraphics.core.FramePool;
import balbucio.capturegraphics.core.MetricsTracker;

import java.lang.System.Logger;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Windows DXGI Desktop Duplication backend (Phase 2: multi-output, dirty rects,
 * optional cursor, transparent reopen).
 *
 * <p>Frame path per {@code acquire()}: one JNI transition carrying the staging
 * {@code Map} + row memcpy, dirty/move rects and pointer state — 1 copy total,
 * zero object allocation in steady state. Presentation timestamps come from QPC
 * captured natively and converted to the {@link System#nanoTime()} domain.
 */
public final class DxgiBackend implements CaptureBackend {
    private static final Logger LOG = System.getLogger(DxgiBackend.class.getName());
    private static final int REOPEN_ATTEMPTS = 3;
    private static final long[] REOPEN_BACKOFF_MS = {100, 250, 500};

    @Override
    public String id() {
        return "win-dxgi";
    }

    @Override
    public Capabilities capabilities() {
        return new Capabilities(Set.of(FrameFormat.BGRA_8), true, true,
                7680, 4320, false, true);
    }

    @Override
    public List<DisplayId> displays() {
        try {
            NativeLibLoader.ensureLoaded();
        } catch (IllegalStateException e) {
            return fallbackDisplay();
        }
        int count = DxgiNative.nOutputCount(0);
        if (count <= 0) {
            return fallbackDisplay();
        }
        List<DisplayId> out = new ArrayList<>(count);
        int[] desc = new int[5];
        for (int i = 0; i < count; i++) {
            if (DxgiNative.nOutputDesc(0, i, desc) == DxgiNative.CG_OK && desc[2] > 0 && desc[3] > 0) {
                out.add(new DisplayId("dxgi-output-" + i,
                        "DXGI output " + i + (i == 0 ? " (primary)" : ""),
                        desc[0], desc[1], desc[2], desc[3], desc[4] != 0));
            }
        }
        return out.isEmpty() ? fallbackDisplay() : List.copyOf(out);
    }

    private static List<DisplayId> fallbackDisplay() {
        var size = java.awt.Toolkit.getDefaultToolkit().getScreenSize();
        return List.of(new DisplayId("dxgi-output-0", "Primary Display (DXGI output 0)",
                0, 0, size.width, size.height, false));
    }

    /** Parses {@code dxgi-output-N} ids; anything else (or null) means output 0. */
    static int outputIndex(DisplayId display) {
        if (display == null || display.id() == null) {
            return 0;
        }
        String id = display.id().trim();
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
        if (config.format() != FrameFormat.BGRA_8) {
            throw new CaptureException(CaptureException.Reason.UNSUPPORTED_FORMAT,
                    "DXGI supports only BGRA_8");
        }
        try {
            NativeLibLoader.ensureLoaded();
        } catch (IllegalStateException e) {
            throw new CaptureException(CaptureException.Reason.NATIVE_ERROR,
                    "Native library unavailable: " + e.getMessage(), e);
        }
        int output = outputIndex(config.display());
        long handle = openHandle(output);
        int[] info = DxgiNative.nInfo(handle);
        if (info == null || info[0] <= 0 || info[1] <= 0) {
            // Size unknown until the first frame; resolve from enumeration.
            int[] desc = new int[5];
            if (DxgiNative.nOutputDesc(0, output, desc) == DxgiNative.CG_OK) {
                info = new int[]{desc[2], desc[3], desc[2] * 4};
            } else {
                DxgiNative.nClose(handle);
                throw new CaptureException(CaptureException.Reason.NATIVE_ERROR,
                        "Could not determine output size");
            }
        }
        long freq = DxgiNative.nQpcFrequency();
        if (freq <= 0) {
            DxgiNative.nClose(handle);
            throw new CaptureException(CaptureException.Reason.NATIVE_ERROR, "QPC unavailable");
        }
        DisplayId resolved = resolveDisplay(output, info[0], info[1]);
        FramePool pool = new FramePool(info[0], info[1], FrameFormat.BGRA_8,
                Math.max(2, config.framePoolSize()));
        LOG.log(Logger.Level.INFO, "DXGI backend opened output {0} {1}x{2}{3}",
                output, info[0], info[1], config.cursor() ? " +cursor" : "");
        return new DxgiSession(handle, output, resolved, config, pool, freq);
    }

    @Override
    public GpuCaptureSession openGpu(CaptureConfig config) throws CaptureException {
        if (config.format() != FrameFormat.BGRA_8) {
            throw new CaptureException(CaptureException.Reason.UNSUPPORTED_FORMAT,
                    "DXGI supports only BGRA_8");
        }
        try {
            NativeLibLoader.ensureLoaded();
        } catch (IllegalStateException e) {
            throw new CaptureException(CaptureException.Reason.NATIVE_ERROR,
                    "Native library unavailable: " + e.getMessage(), e);
        }
        if (config.cursor()) {
            LOG.log(Logger.Level.WARNING,
                    "Cursor compositing needs a CPU buffer; ignored in GPU mode. "
                            + "Consumers draw the cursor themselves.");
        }
        int output = outputIndex(config.display());
        long handle = openHandle(output);
        int[] info = DxgiNative.nInfo(handle);
        if (info == null || info[0] <= 0 || info[1] <= 0) {
            int[] desc = new int[5];
            if (DxgiNative.nOutputDesc(0, output, desc) == DxgiNative.CG_OK) {
                info = new int[]{desc[2], desc[3], desc[2] * 4};
            } else {
                DxgiNative.nClose(handle);
                throw new CaptureException(CaptureException.Reason.NATIVE_ERROR,
                        "Could not determine output size");
            }
        }
        long freq = DxgiNative.nQpcFrequency();
        if (freq <= 0) {
            DxgiNative.nClose(handle);
            throw new CaptureException(CaptureException.Reason.NATIVE_ERROR, "QPC unavailable");
        }
        int slots = Math.min(16, Math.max(2, config.framePoolSize()));
        int rc = DxgiNative.nGpuInit(handle, slots);
        if (rc != DxgiNative.CG_OK) {
            String detail = DxgiNative.nLastError(handle);
            DxgiNative.nClose(handle);
            throw new CaptureException(CaptureException.Reason.NATIVE_ERROR,
                    "GPU pool init failed (need Windows 10 1703+): " + detail);
        }
        DisplayId resolved = resolveDisplay(output, info[0], info[1]);
        LOG.log(Logger.Level.INFO, "DXGI GPU backend opened output {0} {1}x{2} slots={3}",
                output, info[0], info[1], slots);
        return new DxgiGpuSession(handle, output, resolved, config, freq);
    }

    static long openHandle(int output) throws CaptureException {
        try {
            long handle = DxgiNative.nOpen(0, output);
            if (handle == 0) {
                throw new CaptureException(CaptureException.Reason.NATIVE_ERROR, "cg_dxgi_open failed");
            }
            return handle;
        } catch (UnsatisfiedLinkError | IllegalStateException e) {
            throw new CaptureException(CaptureException.Reason.NATIVE_ERROR,
                    "cg_dxgi_open failed: " + e.getMessage(), e);
        }
    }

    static DisplayId resolveDisplay(int output, int w, int h) {
        int[] desc = new int[5];
        if (DxgiNative.nOutputDesc(0, output, desc) == DxgiNative.CG_OK) {
            return new DisplayId("dxgi-output-" + output, "DXGI output " + output,
                    desc[0], desc[1], w, h, desc[4] != 0);
        }
        return new DisplayId("dxgi-output-" + output, "DXGI output " + output, 0, 0, w, h, false);
    }

    private final class DxgiSession implements CaptureSession {
        private long handle;
        private final int output;
        private DisplayId display;
        private final CaptureConfig config;
        private FramePool pool;
        private int stride;
        private final MetricsTracker metrics = new MetricsTracker();
        private final AtomicLong seq = new AtomicLong(0);
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final AtomicReference<PushLoop> push = new AtomicReference<>();
        private final long qpcFreq;
        private final long durationNanos;
        // Reused native call buffers: zero allocation per frame.
        private final long[] qpcOut = new long[1];
        private final int[] meta = new int[4];
        private final ByteBuffer moves;
        private final ByteBuffer dirties;
        private final int[] ptr = new int[9];
        private final ByteBuffer shape;
        private long baseQpc = 0;
        private long baseNano = 0;
        private long lastQpc = 0;

        DxgiSession(long handle, int output, DisplayId display, CaptureConfig config,
                    FramePool pool, long qpcFreq) {
            this.handle = handle;
            this.output = output;
            this.display = display;
            this.config = config;
            this.pool = pool;
            this.stride = display.width() * 4;
            this.qpcFreq = qpcFreq;
            this.durationNanos = config.targetFps() > 0
                    ? 1_000_000_000L / config.targetFps() : 16_666_666L;
            this.moves = ByteBuffer.allocateDirect(DxgiNative.MAX_MOVES * 6 * 4)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN);
            this.dirties = ByteBuffer.allocateDirect(DxgiNative.MAX_DIRTY * 4 * 4)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN);
            this.shape = ByteBuffer.allocateDirect(DxgiNative.PTR_SHAPE_CAP)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        }

        @Override
        public synchronized FramePool.PooledFrame acquire() throws CaptureException {
            if (closed.get()) {
                throw new CaptureException(CaptureException.Reason.CLOSED, "Session closed");
            }
            long t0 = System.nanoTime();
            var frame = pool.take();
            int rc = DxgiNative.nAcquire(handle, frame.writable(), stride, config.timeoutMs(),
                    qpcOut, meta, moves, dirties, ptr, shape);
            if (rc == DxgiNative.CG_TIMEOUT) {
                frame.close();
                return null;
            }
            if (isFatal(rc)) {
                frame.close();
                long gapStart = System.nanoTime();
                if (tryReopen()) {
                    long gapDropped = durationNanos > 0
                            ? Math.max(0, Math.round((double) (System.nanoTime() - gapStart)
                                    / durationNanos) - 1)
                            : 0;
                    metrics.onDrop(gapDropped);
                    return acquireAfterReopen(t0);
                }
                throw toException(rc);
            }
            if (rc != DxgiNative.CG_OK) {
                frame.close();
                throw toException(rc);
            }
            return wrap(frame, t0, 0);
        }

        private FramePool.PooledFrame acquireAfterReopen(long t0) throws CaptureException {
            var frame = pool.take();
            int rc = DxgiNative.nAcquire(handle, frame.writable(), stride, config.timeoutMs(),
                    qpcOut, meta, moves, dirties, ptr, shape);
            if (rc == DxgiNative.CG_TIMEOUT) {
                frame.close();
                return null;
            }
            if (rc != DxgiNative.CG_OK) {
                frame.close();
                throw toException(rc);
            }
            return wrap(frame, t0, 0);
        }

        private FramePool.PooledFrame wrap(FramePool.PooledFrame frame, long t0, long extraDropped) {
            // Native wrote via raw pointer (position untouched): expose full buffer.
            var buf = frame.writable();
            buf.position(buf.capacity()).flip();
            long qpc = qpcOut[0];
            if (baseQpc == 0) {
                baseQpc = qpc;
                baseNano = t0;
            }
            long pts = baseNano + ((qpc - baseQpc) * 1_000_000_000L) / qpcFreq;
            long dropped = extraDropped;
            if (lastQpc != 0 && durationNanos > 0) {
                long gapNanos = ((qpc - lastQpc) * 1_000_000_000L) / qpcFreq;
                dropped += Math.max(0, Math.round((double) gapNanos / durationNanos) - 1);
            }
            lastQpc = qpc;
            long s = seq.getAndIncrement();
            frame.withMeta(new FrameMeta(s, pts, durationNanos, display, dropped,
                    unionDirty(meta, moves, dirties, display.width(), display.height()), true));
            if (config.cursor() && ptr[DxgiNative.PTR_VISIBLE] != 0) {
                CursorCompositor.composite(buf, display.width(), display.height(), stride, ptr, shape);
            }
            metrics.onDelivered(System.nanoTime() - t0, dropped);
            return frame;
        }

        private boolean isFatal(int rc) {
            return rc == DxgiNative.CG_ERR_ACCESS_LOST || rc == DxgiNative.CG_ERR_DEVICE_REMOVED;
        }

        /**
         * Transparent reopen after mode change / device loss. Re-queries geometry
         * (resolution may have changed) and resets the QPC anchor so {@code pts}
         * stays monotonic. Returns false when all attempts fail.
         */
        private boolean tryReopen() {
            for (int attempt = 0; attempt < REOPEN_ATTEMPTS && !closed.get(); attempt++) {
                try {
                    Thread.sleep(REOPEN_BACKOFF_MS[attempt]);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
                long next;
                try {
                    next = openHandle(output);
                } catch (CaptureException e) {
                    LOG.log(Logger.Level.WARNING, "DXGI reopen attempt {0} failed: {1}",
                            attempt + 1, e.getMessage());
                    continue;
                }
                int[] info = DxgiNative.nInfo(next);
                int w = 0, h = 0;
                if (info != null && info[0] > 0) {
                    w = info[0];
                    h = info[1];
                } else {
                    int[] desc = new int[5];
                    if (DxgiNative.nOutputDesc(0, output, desc) == DxgiNative.CG_OK) {
                        w = desc[2];
                        h = desc[3];
                    }
                }
                if (w <= 0 || h <= 0) {
                    DxgiNative.nClose(next);
                    continue;
                }
                DxgiNative.nClose(handle);
                handle = next;
                if (w != display.width() || h != display.height()) {
                    pool = new FramePool(w, h, FrameFormat.BGRA_8,
                            Math.max(2, config.framePoolSize()));
                    stride = w * 4;
                    display = resolveDisplay(output, w, h);
                    LOG.log(Logger.Level.INFO, "DXGI reopened with new geometry {0}x{1}", w, h);
                } else {
                    LOG.log(Logger.Level.INFO, "DXGI reopened on output {0}", output);
                }
                baseQpc = 0; // re-anchor below; pts continues from wall clock
                lastQpc = 0;
                baseNano = System.nanoTime();
                return true;
            }
            return false;
        }

        private CaptureException toException(int rc) {
            String detail = DxgiNative.nLastError(handle);
            return switch (rc) {
                case DxgiNative.CG_ERR_ACCESS_LOST -> new CaptureException(
                        CaptureException.Reason.MODE_CHANGED, "DXGI access lost: " + detail);
                case DxgiNative.CG_ERR_DEVICE_REMOVED -> new CaptureException(
                        CaptureException.Reason.DEVICE_LOST, "DXGI device removed: " + detail);
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
                        return; // reopen is handled inside acquire; reaching here is fatal
                    }
                }
            }
        }
    }

    /**
     * Merges move destinations + dirty rects (both output-relative) into the changed-region
     * list for encoders. Clipped to the frame; falls back to full-frame when DXGI reports
     * no regions for an acquired frame.
     */
    static List<Rect> unionDirty(int[] meta, ByteBuffer moves, ByteBuffer dirties, int w, int h) {
        List<Rect> out = new ArrayList<>();
        int moveCount = Math.min(meta[2], MAX_CAP);
        for (int i = 0; i < moveCount; i++) {
            int base = i * 6 * 4;
            addClipped(out, moves.getInt(base + 2 * 4), moves.getInt(base + 3 * 4),
                    moves.getInt(base + 4 * 4), moves.getInt(base + 5 * 4), w, h);
        }
        int dirtyCount = Math.min(meta[3], MAX_CAP);
        for (int i = 0; i < dirtyCount; i++) {
            int base = i * 4 * 4;
            addClipped(out, dirties.getInt(base), dirties.getInt(base + 4),
                    dirties.getInt(base + 8), dirties.getInt(base + 12), w, h);
        }
        if (out.isEmpty()) {
            out.add(Rect.full(w, h));
        }
        return List.copyOf(out);
    }

    private static final int MAX_CAP = 128;

    private static void addClipped(List<Rect> out, int x, int y, int width, int height, int w, int h) {
        int x0 = Math.max(0, x);
        int y0 = Math.max(0, y);
        int x1 = Math.min(w, x + width);
        int y1 = Math.min(h, y + height);
        if (x1 > x0 && y1 > y0) {
            out.add(new Rect(x0, y0, x1 - x0, y1 - y0));
        }
    }
}
