package balbucio.capturegraphics.api;

import java.util.List;
import java.util.Set;

/** Static capabilities advertised by a backend. Used for format negotiation. */
public record Capabilities(
        Set<FrameFormat> formats,
        boolean dirtyRects,
        boolean cursorCapture,
        int maxWidth,
        int maxHeight,
        boolean hdr,
        boolean gpuSharedTextures) {
    public boolean supports(FrameFormat f) {
        return formats.contains(f);
    }

    public static Capabilities robotFallback(int w, int h) {
        return new Capabilities(Set.of(FrameFormat.BGRA_8), false, false, w, h, false, false);
    }
}
