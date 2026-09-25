package balbucio.capturegraphics.api;

/** Failure modes. Native errors map to these reasons. */
public class CaptureException extends Exception {
    public enum Reason {
        TIMEOUT,
        DEVICE_LOST,
        MODE_CHANGED,
        ACCESS_DENIED,
        UNSUPPORTED_FORMAT,
        UNSUPPORTED_OPERATION,
        INVALID_ARG,
        CLOSED,
        NATIVE_ERROR
    }

    private final Reason reason;

    public CaptureException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public CaptureException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
