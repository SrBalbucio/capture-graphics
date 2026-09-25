package balbucio.capturegraphics.core;

import balbucio.capturegraphics.api.Frame;
import balbucio.capturegraphics.api.FrameFormat;
import balbucio.capturegraphics.api.FrameMeta;
import balbucio.capturegraphics.api.SessionMetrics;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Lock-free counters for fps / drops / acquire latency. Safe to call from hot path.
 * Logging policy: no logging here; backends log only on open/close/errors via System.Logger.
 */
public final class MetricsTracker {
    private final LongAdder delivered = new LongAdder();
    private final LongAdder dropped = new LongAdder();
    private final LongAdder acquireNanos = new LongAdder();
    private final AtomicLong startNanos = new AtomicLong(System.nanoTime());

    public void onDelivered(long acquireNanos, long droppedSinceLast) {
        delivered.increment();
        dropped.add(droppedSinceLast);
        this.acquireNanos.add(acquireNanos);
    }

    public void onDrop(long n) {
        dropped.add(n);
    }

    public SessionMetrics snapshot() {
        long d = delivered.sum();
        double elapsedSec = Math.max(1e-9, (System.nanoTime() - startNanos.get()) / 1e9);
        double avgMs = d == 0 ? 0 : (acquireNanos.sum() / 1e6) / d;
        return new SessionMetrics(d / elapsedSec, d, dropped.sum(), avgMs);
    }
}
