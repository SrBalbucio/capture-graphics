package balbucio.capturegraphics.api;

/** Non-blocking frame consumer for push-mode. Called on the capture thread: do not block. */
@FunctionalInterface
public interface FrameListener {
    void onFrame(Frame frame);
}
