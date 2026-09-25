package balbucio.capturegraphics.core;

import balbucio.capturegraphics.api.FrameFormat;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FramePoolTest {
    @Test
    void takeCloseRecyclesSlot() {
        FramePool pool = new FramePool(64, 64, FrameFormat.BGRA_8, 2);
        var a = pool.take();
        var b = pool.take();
        assertEquals(0, pool.freeCount());
        a.close();
        assertEquals(1, pool.freeCount());
        var c = pool.take(); // reuses a's buffer, no alloc on happy path
        assertEquals(0, pool.freeCount());
        b.close();
        c.close();
        assertEquals(2, pool.freeCount());
    }

    @Test
    void closeIsIdempotent() {
        FramePool pool = new FramePool(16, 16, FrameFormat.BGRA_8, 1);
        var f = pool.take();
        f.close();
        f.close();
        assertEquals(1, pool.freeCount());
    }

    @Test
    void metricsSnapshot() {
        MetricsTracker m = new MetricsTracker();
        m.onDelivered(1_000_000, 2);
        m.onDelivered(1_000_000, 0);
        var s = m.snapshot();
        assertEquals(2, s.delivered());
        assertEquals(2, s.dropped());
        assertTrue(s.avgAcquireMs() > 0);
    }
}
