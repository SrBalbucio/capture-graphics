package balbucio.capturegraphics.dxgi;

import balbucio.capturegraphics.api.Capture;
import balbucio.capturegraphics.api.CaptureConfig;
import balbucio.capturegraphics.api.CaptureException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

/**
 * DXGI MVP smoke test: opens output 0, acquires a sequence, validates the
 * zero-copy contract (direct buffer, monotonic sequence/pts), closes cleanly.
 *
 * <p>Aborts (does not fail) when DXGI is unavailable: non-Windows OS, RDP /
 * headless session, or protected content (all expected platform limits).
 */
class DxgiSmokeTest {
    @Test
    void acquireSequence() throws Exception {
        assumeTrue(NativeLibLoader.isWindowsX64(), "requires Windows x64");
        assumeTrue(Capture.backends().stream().anyMatch(b -> b.id().equals("win-dxgi")),
                "win-dxgi backend not on classpath");

        CaptureConfig config = CaptureConfig.builder().timeoutMs(100).targetFps(60).build();
        DxgiBackend backend = new DxgiBackend();
        try (var session = backend.open(config)) {
            assertEquals("win-dxgi", backend.id());
            long lastSeq = -1;
            long lastPts = -1;
            int got = 0;
            long deadline = System.currentTimeMillis() + 15_000;
            while (got < 30 && System.currentTimeMillis() < deadline) {
                try (var f = session.acquire()) {
                    if (f == null) {
                        continue; // timeout: no new frame yet
                    }
                    assertTrue(f.data().isDirect(), "hot path must stay direct");
                    assertTrue(f.meta().sequence() > lastSeq, "sequence must increase");
                    assertTrue(f.meta().ptsNanos() >= lastPts, "pts must be monotonic");
                    assertEquals(f.width() * 4, f.rowStride());
                    assertEquals(f.width() * f.height() * 4, f.data().remaining(),
                            "buffer must expose the full copied frame");
                    assertTrue(f.meta().fullFrame());
                    lastSeq = f.meta().sequence();
                    lastPts = f.meta().ptsNanos();
                    got++;
                }
            }
            assertTrue(got >= 5, "expected frames within 15s, got " + got);
            assertTrue(session.metrics().delivered() >= got);
        } catch (CaptureException e) {
            assumeTrue(false, "DXGI unavailable in this session: " + e.reason() + " " + e.getMessage());
        }
    }
}
