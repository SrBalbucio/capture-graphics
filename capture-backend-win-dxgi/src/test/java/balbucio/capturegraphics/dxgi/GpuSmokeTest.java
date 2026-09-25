package balbucio.capturegraphics.dxgi;

import balbucio.capturegraphics.api.CaptureConfig;
import balbucio.capturegraphics.api.CaptureException;
import balbucio.capturegraphics.api.GpuApi;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

/**
 * GPU zero-copy smoke test: shared handles are non-zero, fence values strictly
 * increase, geometry matches, dirty metadata flows, slots recycle.
 * Aborts when DXGI is unavailable (same platform limits as the CPU path).
 */
class GpuSmokeTest {
    @Test
    void acquireGpuSequence() throws Exception {
        assumeTrue(NativeLibLoader.isWindowsX64(), "requires Windows x64");
        var backend = new DxgiBackend();
        assertTrue(backend.capabilities().gpuSharedTextures());

        CaptureConfig config = CaptureConfig.builder().timeoutMs(500).targetFps(60)
                .framePoolSize(4).build();
        try (var session = backend.openGpu(config)) {
            long lastFence = -1;
            long lastSeq = -1;
            int got = 0;
            long deadline = System.currentTimeMillis() + 20_000;
            while (got < 10 && System.currentTimeMillis() < deadline) {
                try (var f = session.acquireGpu()) {
                    if (f == null) {
                        continue;
                    }
                    assertEquals(GpuApi.D3D11, f.api());
                    assertNotEquals(0, f.nativeHandle(), "shared NT handle");
                    assertNotEquals(0, f.fenceHandle(), "shared fence handle");
                    assertTrue(f.fenceValue() > lastFence, "fence must increase");
                    assertTrue(f.meta().sequence() > lastSeq, "sequence must increase");
                    assertEquals(session.display().width(), f.width());
                    assertEquals(session.display().height(), f.height());
                    assertFalse(f.meta().dirty().isEmpty());
                    lastFence = f.fenceValue();
                    lastSeq = f.meta().sequence();
                    got++;
                }
            }
            assertTrue(got >= 3, "expected GPU frames within 20s, got " + got);
            assertTrue(session.metrics().delivered() >= got);
        } catch (CaptureException e) {
            assumeTrue(false, "DXGI unavailable: " + e.reason() + " " + e.getMessage());
        }
    }

    @Test
    void cpuAcquireRejectedInGpuSession() throws Exception {
        assumeTrue(NativeLibLoader.isWindowsX64(), "requires Windows x64");
        try (var session = new DxgiBackend().openGpu(CaptureConfig.bgra())) {
            var e = assertThrows(CaptureException.class, session::acquire);
            assertEquals(CaptureException.Reason.UNSUPPORTED_OPERATION, e.reason());
            assertThrows(UnsupportedOperationException.class,
                    () -> session.onFrame(f -> {}, balbucio.capturegraphics.api.Backpressure.DROP_NEWEST));
        } catch (CaptureException e) {
            assumeTrue(false, "DXGI unavailable: " + e.reason() + " " + e.getMessage());
        }
    }
}
