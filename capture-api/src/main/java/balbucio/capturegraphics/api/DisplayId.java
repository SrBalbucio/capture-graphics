package balbucio.capturegraphics.api;

/** Opaque display identifier. MVP resolves to the primary monitor. */
public record DisplayId(String id, String name, int width, int height) {
    public static DisplayId primary(int width, int height) {
        return new DisplayId("primary", "Primary Display", width, height);
    }
}
