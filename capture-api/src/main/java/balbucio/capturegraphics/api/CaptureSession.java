package balbucio.capturegraphics.api;

/**
 * A capture session bound to one display. Owns the capture thread and frame pool.
 *
 * <p>Pull-model mirrors DXGI and is preferred for recorders:
 * <pre>{@code
 * try (CaptureSession s = Capture.openDefault(CaptureConfig.bgra())) {
 *   try (Frame f = s.acquire()) { if (f != null) encode(f); }
 * }
 * }</pre>
 * {@code acquire()} returns {@code null} on timeout (no new frame), never blocks past
 * {@link CaptureConfig#timeoutMs()}.
 */
public interface CaptureSession extends AutoCloseable {
    /** Acquire next frame or {@code null} on timeout. Caller owns the frame and must close it. */
    Frame acquire() throws CaptureException;

    /** Subscribe a push listener. Only one listener per session in MVP. */
    void onFrame(FrameListener listener, Backpressure backpressure);

    void clearListener();

    SessionMetrics metrics();

    DisplayId display();

    CaptureConfig config();

    @Override
    void close();
}
