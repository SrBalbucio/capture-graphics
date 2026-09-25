package balbucio.capturegraphics.robot;

import balbucio.capturegraphics.api.Capture;
import balbucio.capturegraphics.api.CaptureConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;

import java.awt.GraphicsEnvironment;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class RobotBackendTest {
    @Test
    void sequentialFramesAreMonotonic() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "needs display");
        var backends = Capture.backends();
        assertTrue(backends.stream().anyMatch(b -> b.id().equals("robot")));

        try (var session = new RobotBackend().open(CaptureConfig.bgra())) {
            long lastSeq = -1;
            long lastPts = -1;
            for (int i = 0; i < 5; i++) {
                try (var f = session.acquire()) {
                    assertNotNull(f);
                    assertTrue(f.meta().sequence() > lastSeq, "sequence must increase");
                    assertTrue(f.meta().ptsNanos() >= lastPts, "pts must be monotonic");
                    assertEquals(f.width() * 4, f.rowStride());
                    assertTrue(f.data().isDirect(), "hot path must stay direct");
                    lastSeq = f.meta().sequence();
                    lastPts = f.meta().ptsNanos();
                }
            }
            assertEquals(5, session.metrics().delivered());
        }
    }

    @Test
    void regionAndLatest() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "needs display");
        var backend = new RobotBackend();
        assertFalse(backend.displays().isEmpty(), "at least the primary screen");
        var display = backend.displays().get(0);
        var region = new balbucio.capturegraphics.api.Rect(
                display.x() + 5, display.y() + 5, 160, 100);
        var config = CaptureConfig.builder().region(region).retainLast(true).build();
        try (var session = backend.open(config)) {
            try (var f = session.acquire()) {
                assertNotNull(f);
                assertEquals(160, f.width());
                assertEquals(100, f.height());
                assertEquals(160 * 100 * 4, f.data().remaining());
                // Pixels must be real screen content, not zeros (bulk int path).
                var data = f.data();
                boolean nonZero = false;
                for (int i = 0; i < data.remaining(); i += 4096) {
                    if (data.get(i) != 0) {
                        nonZero = true;
                        break;
                    }
                }
                assertTrue(nonZero, "bulk int copy must carry real pixels");
            }
            var latest = session.latest();
            assertTrue(latest.isPresent());
            assertEquals(160, latest.get().width());
        }
    }

    @Test
    void invalidRegionRejected() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "needs display");
        var config = CaptureConfig.builder()
                .region(new balbucio.capturegraphics.api.Rect(-100000, -100000, 10, 10))
                .build();
        var e = assertThrows(balbucio.capturegraphics.api.CaptureException.class,
                () -> new RobotBackend().open(config));
        assertEquals(balbucio.capturegraphics.api.CaptureException.Reason.INVALID_ARG, e.reason());
    }
}
