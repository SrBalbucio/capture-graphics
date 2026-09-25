package balbucio.capturegraphics.dxgi;

import balbucio.capturegraphics.api.Backpressure;
import balbucio.capturegraphics.api.CaptureConfig;
import balbucio.capturegraphics.api.CaptureException;
import balbucio.capturegraphics.api.DisplayId;
import balbucio.capturegraphics.api.Frame;
import balbucio.capturegraphics.api.FrameListener;
import balbucio.capturegraphics.api.FrameMeta;
import balbucio.capturegraphics.api.GpuCaptureSession;
import balbucio.capturegraphics.api.GpuFrame;
import balbucio.capturegraphics.api.SessionMetrics;
import balbucio.capturegraphics.core.MetricsTracker;

import java.lang.System.Logger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * GPU-resident session: every frame is a D3D11 shared texture, zero CPU copies.
 * CPU {@link #acquire()} and push listeners are unsupported by design (see
 * {@link GpuCaptureSession}); open a CPU session for readback instead.
 */
final class DxgiGpuSession implements GpuCaptureSession {
    private static final Logger LOG = System.getLogger(DxgiGpuSession.class.getName());
    private static final int REOPEN_ATTEMPTS = 3;
    private static final long[] REOPEN_BACKOFF_MS = {100, 250, 500};

    private long handle;
    private final int output;
    private DisplayId display;
    private final CaptureConfig config;
    private final MetricsTracker metrics = new MetricsTracker();
    private final AtomicLong seq = new AtomicLong(0);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicInteger outstanding = new AtomicInteger(0);
    private final long qpcFreq;
    private final long durationNanos;
    // Reused native call buffers: zero allocation per frame.
    private final long[] gpuMeta = new long[7];
    private final int[] meta = new int[4];
    private final ByteBuffer moves =
            ByteBuffer.allocateDirect(DxgiNative.MAX_MOVES * 6 * 4).order(ByteOrder.LITTLE_ENDIAN);
    private final ByteBuffer dirties =
            ByteBuffer.allocateDirect(DxgiNative.MAX_DIRTY * 4 * 4).order(ByteOrder.LITTLE_ENDIAN);
    private final int[] ptr = new int[3];
    private long baseQpc = 0;
    private long baseNano = 0;
    private long lastQpc = 0;

    DxgiGpuSession(long handle, int output, DisplayId display, CaptureConfig config, long qpcFreq) {
        this.handle = handle;
        this.output = output;
        this.display = display;
        this.config = config;
        this.qpcFreq = qpcFreq;
        this.durationNanos = config.targetFps() > 0
                ? 1_000_000_000L / config.targetFps() : 16_666_666L;
    }

    @Override
    public synchronized GpuFrame acquireGpu() throws CaptureException {
        if (closed.get()) {
            throw new CaptureException(CaptureException.Reason.CLOSED, "Session closed");
        }
        long t0 = System.nanoTime();
        int rc = DxgiNative.nAcquireGpu(handle, config.timeoutMs(), gpuMeta, meta, moves, dirties, ptr);
        if (rc == DxgiNative.CG_TIMEOUT) {
            return null;
        }
        if (isFatal(rc)) {
            long gapStart = System.nanoTime();
            if (outstanding.get() == 0 && tryReopen()) {
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
            throw toException(rc);
        }
        return wrap(t0, snapshotMeta());
    }

    private GpuFrame acquireAfterReopen(long t0) throws CaptureException {
        int rc = DxgiNative.nAcquireGpu(handle, config.timeoutMs(), gpuMeta, meta, moves, dirties, ptr);
        if (rc == DxgiNative.CG_TIMEOUT) {
            return null;
        }
        if (rc != DxgiNative.CG_OK) {
            throw toException(rc);
        }
        return wrap(t0, snapshotMeta());
    }

    /** Copies the reused native buffers before the next call overwrites them. */
    private long[] snapshotMeta() {
        return new long[]{gpuMeta[0], gpuMeta[1], gpuMeta[2], gpuMeta[3],
                gpuMeta[4], gpuMeta[5], gpuMeta[6]};
    }

    private GpuFrame wrap(long t0, long[] g) {
        long qpc = g[6];
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
        FrameMeta fm = new FrameMeta(s, pts, durationNanos, display, dropped,
                DxgiBackend.unionDirty(meta, moves, dirties, (int) g[4], (int) g[5]), true);
        outstanding.incrementAndGet();
        DxgiGpuFrame frame = new DxgiGpuFrame(handle, (int) g[0], g[1], g[2], g[3],
                (int) g[4], (int) g[5], fm);
        metrics.onDelivered(System.nanoTime() - t0, dropped);
        return new ReleasingFrame(frame);
    }

    /** Decrements the in-flight count so reopen knows when it is safe. */
    private final class ReleasingFrame implements GpuFrame {
        private final DxgiGpuFrame inner;
        private final AtomicBoolean done = new AtomicBoolean(false);

        ReleasingFrame(DxgiGpuFrame inner) {
            this.inner = inner;
        }

        @Override
        public void close() {
            if (done.compareAndSet(false, true)) {
                try {
                    inner.close();
                } finally {
                    outstanding.decrementAndGet();
                }
            }
        }

        @Override
        public balbucio.capturegraphics.api.GpuApi api() {
            return inner.api();
        }

        @Override
        public long nativeHandle() {
            return inner.nativeHandle();
        }

        @Override
        public long fenceHandle() {
            return inner.fenceHandle();
        }

        @Override
        public long fenceValue() {
            return inner.fenceValue();
        }

        @Override
        public int width() {
            return inner.width();
        }

        @Override
        public int height() {
            return inner.height();
        }

        @Override
        public balbucio.capturegraphics.api.FrameFormat format() {
            return inner.format();
        }

        @Override
        public FrameMeta meta() {
            return inner.meta();
        }
    }

    private boolean isFatal(int rc) {
        return rc == DxgiNative.CG_ERR_ACCESS_LOST || rc == DxgiNative.CG_ERR_DEVICE_REMOVED;
    }

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
                next = DxgiBackend.openHandle(output);
            } catch (CaptureException e) {
                LOG.log(Logger.Level.WARNING, "DXGI GPU reopen attempt {0} failed: {1}",
                        attempt + 1, e.getMessage());
                continue;
            }
            int slots = Math.min(16, Math.max(2, config.framePoolSize()));
            if (DxgiNative.nGpuInit(next, slots) != DxgiNative.CG_OK) {
                DxgiNative.nClose(next);
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
            display = DxgiBackend.resolveDisplay(output, w, h);
            baseQpc = 0;
            lastQpc = 0;
            baseNano = System.nanoTime();
            LOG.log(Logger.Level.INFO, "DXGI GPU session reopened on output {0}", output);
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
                    "DXGI GPU acquire failed rc=" + rc + ": " + detail);
        };
    }

    @Override
    public Frame acquire() throws CaptureException {
        throw new CaptureException(CaptureException.Reason.UNSUPPORTED_OPERATION,
                "GPU session delivers GpuFrame only; use acquireGpu() or open a CPU session");
    }

    @Override
    public void onFrame(FrameListener listener, Backpressure backpressure) {
        throw new UnsupportedOperationException(
                "Push mode is CPU-only; pull GpuFrames with acquireGpu()");
    }

    @Override
    public void clearListener() {
        // No push mode in GPU sessions; nothing to clear.
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
            DxgiNative.nClose(handle);
        }
    }
}
