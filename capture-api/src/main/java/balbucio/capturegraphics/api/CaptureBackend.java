package balbucio.capturegraphics.api;

import java.util.List;

/** SPI implemented by backends (Robot, DXGI, ...). Discovered via {@link java.util.ServiceLoader}. */
public interface CaptureBackend {
    /** Stable id, e.g. "robot", "win-dxgi". */
    String id();

    Capabilities capabilities();

    List<DisplayId> displays();

    CaptureSession open(CaptureConfig config) throws CaptureException;
}
