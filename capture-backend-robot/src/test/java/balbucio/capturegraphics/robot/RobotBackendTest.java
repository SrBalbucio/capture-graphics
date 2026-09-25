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
}
