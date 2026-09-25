package balbucio.capturegraphics.api;

/**
 * Opaque display identifier. Origin ({@code x},{@code y}) is in desktop coordinates,
 * so multi-monitor layouts compose without ambiguity. {@code hdr} reports an HDR
 * colorspace on the output (informational: frames are still delivered as BGRA_8 SDR).
 */
public record DisplayId(String id, String name, int x, int y, int width, int height, boolean hdr) {
    public static DisplayId primary(int width, int height) {
        return new DisplayId("primary", "Primary Display", 0, 0, width, height, false);
    }
}
