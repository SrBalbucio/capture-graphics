package balbucio.capturegraphics.api;

import java.util.List;

/**
 * Sequential metadata carried by every frame. Designed for recorders and
 * streaming protocols: monotonic sequence, presentation timestamp, drop accounting.
 *
 * @param sequence        monotonically increasing frame number per session, starting at 0
 * @param ptsNanos        presentation timestamp in {@link System#nanoTime()} domain
 * @param durationNanos   expected frame duration (e.g. 16_666_666 for 60 fps), hint only
 * @param display         display the frame was captured from
 * @param droppedSinceLast number of frames dropped between the previous delivered frame and this one
 * @param dirty           dirty rectangles; empty = full frame. MVP always reports full frame.
 * @param fullFrame       true when the buffer holds a complete image (MVP always true)
 */
public record FrameMeta(
        long sequence,
        long ptsNanos,
        long durationNanos,
        DisplayId display,
        long droppedSinceLast,
        List<Rect> dirty,
        boolean fullFrame) {
}
