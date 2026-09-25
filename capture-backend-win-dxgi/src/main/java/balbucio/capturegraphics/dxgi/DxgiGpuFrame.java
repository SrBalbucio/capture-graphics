package balbucio.capturegraphics.dxgi;

import balbucio.capturegraphics.api.FrameFormat;
import balbucio.capturegraphics.api.FrameMeta;
import balbucio.capturegraphics.api.GpuApi;
import balbucio.capturegraphics.api.GpuFrame;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * D3D11 shared-texture frame. The NT handle is valid only until {@link #close()};
 * see {@link GpuFrame} for the consumer protocol (fence wait + keyed mutex).
 */
final class DxgiGpuFrame implements GpuFrame {
    private final long handle;
    private final int slot;
    private final long sharedHandle;
    private final long fenceHandle;
    private final long fenceValue;
    private final int width;
    private final int height;
    private final FrameMeta meta;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    DxgiGpuFrame(long handle, int slot, long sharedHandle, long fenceHandle, long fenceValue,
                 int width, int height, FrameMeta meta) {
        this.handle = handle;
        this.slot = slot;
        this.sharedHandle = sharedHandle;
        this.fenceHandle = fenceHandle;
        this.fenceValue = fenceValue;
        this.width = width;
        this.height = height;
        this.meta = meta;
    }

    @Override
    public GpuApi api() {
        return GpuApi.D3D11;
    }

    @Override
    public long nativeHandle() {
        return sharedHandle;
    }

    @Override
    public long fenceHandle() {
        return fenceHandle;
    }

    @Override
    public long fenceValue() {
        return fenceValue;
    }

    @Override
    public int width() {
        return width;
    }

    @Override
    public int height() {
        return height;
    }

    @Override
    public FrameFormat format() {
        return FrameFormat.BGRA_8;
    }

    @Override
    public FrameMeta meta() {
        return meta;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            DxgiNative.nReleaseGpu(handle, slot);
        }
    }
}
