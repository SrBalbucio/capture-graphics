package balbucio.capturegraphics.api;

/**
 * GPU API backing a {@link GpuFrame}. Only APIs with a concrete producer in this
 * repository are listed; Vulkan/OpenGL/Metal consumers attach through the future
 * renderer bridge by importing the shared handle (see {@link GpuFrame}).
 */
public enum GpuApi {
    /** Direct3D 11 shared NT handle ({@code IDXGIResource1::CreateSharedHandle}). */
    D3D11
}
