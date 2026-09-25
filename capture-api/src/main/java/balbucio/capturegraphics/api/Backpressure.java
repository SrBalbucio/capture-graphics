package balbucio.capturegraphics.api;

/** Backpressure policy for push-mode ({@link CaptureSession#onFrame}). */
public enum Backpressure {
    /** Drop the oldest queued frame to make room. Best for low-latency streaming. */
    DROP_OLDEST,
    /** Drop the incoming frame when the consumer lags. Best for live preview. */
    DROP_NEWEST,
    /** Block the capture thread until the consumer catches up. Best for recorders. */
    BLOCK
}
