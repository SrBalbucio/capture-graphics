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
                    assertFalse(f.meta().dirty().isEmpty(), "dirty list always populated");
                    for (var r : f.meta().dirty()) {
                        assertTrue(r.x() >= 0 && r.y() >= 0);
                        assertTrue(r.x() + r.width() <= f.width());
                        assertTrue(r.y() + r.height() <= f.height());
                    }
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

    @Test
    void acquireWithCursor() throws Exception {
        assumeTrue(NativeLibLoader.isWindowsX64(), "requires Windows x64");
        CaptureConfig config = CaptureConfig.builder().timeoutMs(100).targetFps(60).cursor(true).build();
        try (var session = new DxgiBackend().open(config)) {
            int got = 0;
            long deadline = System.currentTimeMillis() + 15_000;
            while (got < 5 && System.currentTimeMillis() < deadline) {
                try (var f = session.acquire()) {
                    if (f != null) {
                        got++;
                    }
                }
            }
            assertTrue(got >= 3, "expected cursor-composited frames, got " + got);
        } catch (CaptureException e) {
            assumeTrue(false, "DXGI unavailable in this session: " + e.reason() + " " + e.getMessage());
        }
    }

    @Test
    void enumeratesOutputs() {
        assumeTrue(NativeLibLoader.isWindowsX64(), "requires Windows x64");
        var displays = new DxgiBackend().displays();
        assertFalse(displays.isEmpty());
        assertTrue(displays.get(0).width() > 0);
    }

    @Test
    void acquireRegion() throws Exception {
        assumeTrue(NativeLibLoader.isWindowsX64(), "requires Windows x64");
        var backend = new DxgiBackend();
        var display = backend.displays().get(0);
        var region = new balbucio.capturegraphics.api.Rect(
                display.x() + 10, display.y() + 10, 320, 200);
        var config = CaptureConfig.builder().timeoutMs(100).targetFps(60).region(region).build();
        try (var session = backend.open(config)) {
            int got = 0;
            long deadline = System.currentTimeMillis() + 15_000;
            while (got < 5 && System.currentTimeMillis() < deadline) {
                try (var f = session.acquire()) {
                    if (f == null) {
                        continue;
                    }
                    assertEquals(320, f.width());
                    assertEquals(200, f.height());
                    assertEquals(320 * 200 * 4, f.data().remaining());
                    for (var r : f.meta().dirty()) {
                        assertTrue(r.x() >= 0 && r.y() >= 0);
                        assertTrue(r.x() + r.width() <= 320);
                        assertTrue(r.y() + r.height() <= 200);
                    }
                    got++;
                }
            }
            assertTrue(got >= 3, "expected region frames, got " + got);
        } catch (CaptureException e) {
            assumeTrue(false, "DXGI unavailable: " + e.reason() + " " + e.getMessage());
        }
    }

    @Test
    void latestRetained() throws Exception {
        assumeTrue(NativeLibLoader.isWindowsX64(), "requires Windows x64");
        var config = CaptureConfig.builder().timeoutMs(100).retainLast(true).build();
        try (var session = new DxgiBackend().open(config)) {
            assertTrue(session.latest().isEmpty(), "nothing delivered yet");
            long deadline = System.currentTimeMillis() + 15_000;
            long seq = -1;
            while (seq < 0 && System.currentTimeMillis() < deadline) {
                try (var f = session.acquire()) {
                    if (f != null) {
                        seq = f.meta().sequence();
                    }
                }
            }
            var latest = assertDoesNotThrow(() -> session.latest());
            assertTrue(latest.isPresent());
            assertEquals(seq, latest.get().meta().sequence());
            assertDoesNotThrow(() -> latest.get().close()); // no-op, session-owned
        } catch (CaptureException e) {
            assumeTrue(false, "DXGI unavailable: " + e.reason() + " " + e.getMessage());
        }
    }

    @Test
    void latestEmptyByDefault() throws Exception {
        assumeTrue(NativeLibLoader.isWindowsX64(), "requires Windows x64");
        try (var session = new DxgiBackend().open(CaptureConfig.bgra())) {
            try (var f = session.acquire()) {
                // may be null on timeout; latest must stay empty regardless
            }
            assertTrue(session.latest().isEmpty());
        } catch (CaptureException e) {
            assumeTrue(false, "DXGI unavailable: " + e.reason() + " " + e.getMessage());
        }
    }
}
