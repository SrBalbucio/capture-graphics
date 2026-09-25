package balbucio.capturegraphics.api;

/** Pixel format of a {@link Frame}. MVP supports only BGRA_8. */
public enum FrameFormat {
    /** 8-bit Blue-Green-Red-Alpha, 4 bytes per pixel. Native DXGI/D3D11 order. */
    BGRA_8(4);

    private final int bytesPerPixel;

    FrameFormat(int bytesPerPixel) {
        this.bytesPerPixel = bytesPerPixel;
    }

    public int bytesPerPixel() {
        return bytesPerPixel;
    }
}
