package balbucio.capturegraphics.api;

import java.util.List;

/** SPI implemented by backends (Robot, DXGI, ...). Discovered via {@link java.util.ServiceLoader}. */
public interface CaptureBackend {
    /** Stable id, e.g. "robot", "win-dxgi". */
    String id();

    Capabilities capabilities();

    List<DisplayId> displays();

    CaptureSession open(CaptureConfig config) throws CaptureException;

    /**
     * Open a GPU-resident session. Default: unsupported (e.g. pure-CPU backends).
     * Advertise support via {@link Capabilities#gpuSharedTextures()}.
     */
    default GpuCaptureSession openGpu(CaptureConfig config) throws CaptureException {
        throw new CaptureException(CaptureException.Reason.UNSUPPORTED_OPERATION,
                "Backend '" + id() + "' has no GPU session");
    }
}
