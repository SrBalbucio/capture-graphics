package balbucio.capturegraphics.api;

/**
 * A capture session delivering GPU-resident frames. GPU-only by contract:
 * {@link #acquire()} (CPU) always fails with {@code UNSUPPORTED_OPERATION} —
 * open a CPU session for readback instead. Both session kinds may coexist on
 * the same output.
 *
 * <p>Backpressure is physical: the session owns a fixed pool of shared textures
 * (sized by {@link CaptureConfig#framePoolSize()}). A consumer holding every slot
 * makes {@link #acquireGpu()} fail fast with {@code NATIVE_ERROR} instead of
 * blocking the compositor; close frames promptly.
 */
public interface GpuCaptureSession extends CaptureSession {
    /**
     * Acquire the next GPU frame, or {@code null} on timeout (no new frame within
     * {@link CaptureConfig#timeoutMs()}). Caller owns the frame and must close it.
     */
    GpuFrame acquireGpu() throws CaptureException;
}
