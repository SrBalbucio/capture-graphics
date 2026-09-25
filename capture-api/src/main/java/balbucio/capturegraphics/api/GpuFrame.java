package balbucio.capturegraphics.api;

/**
 * A captured frame whose pixels stay on the GPU: zero CPU copies.
 *
 * <p>For {@link GpuApi#D3D11}, {@link #nativeHandle()} is the value of an NT shared
 * handle ({@code DXGI_SHARED_RESOURCE_READ}) to a {@code B8G8R8A8_UNORM} texture owned
 * by the capture session. A consumer in the same process opens it with
 * {@code ID3D11Device::OpenSharedResource1}; another process duplicates it first.
 * The handle is valid only until {@link #close()}: the slot returns to the pool and
 * the next frame may reuse the texture. Consumers MUST therefore finish (or copy)
 * before closing, and MUST wait on the fence (below) before reading.
 *
 * <p>Synchronization has two layers, both mandatory, in order:
 * <ol>
 * <li>Fence: wait for {@link #fenceValue()} on {@link #fenceHandle()} — the producer
 * signals after its copy. The fence opens on the D3D12 side
 * ({@code ID3D12Device::OpenSharedHandle} as {@code ID3D12Fence}) — D3D11 has no
 * fence-open API; D3D11 and D3D12 fences are the same underlying object. Vulkan
 * consumers import {@link #fenceHandle()} as a {@code D3D12_FENCE} external
 * semaphore. Same-device D3D11 consumers sharing the producer's immediate context
 * may rely on command ordering instead, but fence-waiting is always correct.</li>
 * <li>Keyed mutex: {@code QueryInterface} {@code IDXGIKeyedMutex} on the opened
 * texture and {@code AcquireSync(0, timeout)} before reading — shared-keyed-mutex
 * textures read as zeros without this — then {@code ReleaseSync(0)}. Single key 0
 * on both sides; the pool's busy flags order producer/consumer turns. Caution:
 * contention surfaces as {@code WAIT_TIMEOUT}, which is a <em>success</em>
 * {@code HRESULT} — compare it explicitly, {@code FAILED()} never catches it.</li>
 * </ol>
 *
 * <p>Lifecycle mirrors {@link Frame}: pooled, idempotent {@link #close()}, never
 * retained afterwards. {@link #meta()} carries the same sequential metadata
 * (sequence, pts, dirty rects) so recorders can mix CPU and GPU frames.
 */
public interface GpuFrame extends AutoCloseable {
    GpuApi api();

    /** Opaque native handle value (D3D11: NT shared handle). Valid until {@link #close()}. */
    long nativeHandle();

    /**
     * Shared fence handle value, constant per session. D3D11: open with
     * {@code OpenSharedHandle} as {@code ID3D11Fence}; Vulkan: import as
     * {@code VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_D3D12_FENCE_BIT}.
     */
    long fenceHandle();

    /** Fence value signaled by the producer for THIS frame. Always increasing. */
    long fenceValue();

    int width();

    int height();

    FrameFormat format();

    FrameMeta meta();

    /** Returns the GPU slot to the pool. Must be idempotent. */
    @Override
    void close();
}
