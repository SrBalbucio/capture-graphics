package balbucio.capturegraphics.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CaptureConfigTest {
    @Test
    void defaultsTargetSequentialCapture() {
        CaptureConfig c = CaptureConfig.bgra();
        assertEquals(FrameFormat.BGRA_8, c.format());
        assertEquals(60, c.targetFps());
        assertTrue(c.timeoutMs() > 0);
        assertTrue(c.framePoolSize() >= 5, "pool must buffer ~150ms for recorders");
    }
}
