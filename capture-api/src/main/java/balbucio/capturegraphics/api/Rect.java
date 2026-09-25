package balbucio.capturegraphics.api;

/** Integer rectangle, used for dirty regions reported by DXGI. */
public record Rect(int x, int y, int width, int height) {
    public static Rect full(int width, int height) {
        return new Rect(0, 0, width, height);
    }
}
