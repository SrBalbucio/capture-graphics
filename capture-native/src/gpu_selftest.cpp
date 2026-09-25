// gpu_selftest.cpp — Proves the zero-copy path without any Java involvement:
//
//  1. Opens duplication + gpu pool (3 slots).
//  2. On a SECOND D3D11 device: OpenSharedResource1(sharedHandle) -> copy to a
//     private texture -> staging -> Map, verifying pixels arrive cross-device.
//  3. Opens the shared fence on device 2 and waits for each frame's fence value,
//     proving GPU-GPU sync crosses devices.
//  4. Checks fence values are strictly increasing and slots recycle.
//
// Build (Developer Prompt / build-win-x64 env):
//   cl /nologo /EHsc /MD /I <sdk>\um /I <sdk>\shared /I <sdk>\ucrt gpu_selftest.cpp
//      capture_dxgi.lib d3d11.lib dxgi.lib user32.lib
// Or simply compile together with ../src/dxgi_bridge.cpp (needs jni.h include path).
#include "capture_abi.h"

#include <windows.h>
#include <d3d11.h>
#include <d3d11_1.h>
#include <d3d11_4.h>
#include <d3d12.h>
#include <dxgi1_2.h>

#include <cstdio>
#include <cstdlib>

#define CHECK(cond, msg)                                        \
    do {                                                        \
        if (!(cond)) {                                          \
            printf("FAIL: %s\n", msg);                          \
            return 1;                                           \
        }                                                       \
    } while (0)

int main() {
    SetProcessDPIAware();
    CgHandle h = nullptr;
    CHECK(cg_dxgi_open(0, 0, &h) == CG_OK, cg_dxgi_last_error(h));
    CHECK(cg_dxgi_gpu_init(h, 3) == CG_OK, cg_dxgi_last_error(h));

    // Second, independent device = the "consumer" (encoder/renderer process view).
    ID3D11Device *dev2 = nullptr;
    ID3D11DeviceContext *ctx2 = nullptr;
    D3D_FEATURE_LEVEL lv[] = {D3D_FEATURE_LEVEL_11_0};
    CHECK(SUCCEEDED(D3D11CreateDevice(nullptr, D3D_DRIVER_TYPE_HARDWARE, nullptr,
                                      D3D11_CREATE_DEVICE_BGRA_SUPPORT, lv, 1,
                                      D3D11_SDK_VERSION, &dev2, nullptr, &ctx2)),
          "device2 creation");

    // Consumer-side staging for pixel verification.
    CgInfo info = {};
    cg_dxgi_info(h, &info);
    int w = info.width > 0 ? info.width : 1920;
    int bh = info.height > 0 ? info.height : 1080;
    D3D11_TEXTURE2D_DESC sd = {};
    sd.Width = w;
    sd.Height = bh;
    sd.MipLevels = sd.ArraySize = 1;
    sd.Format = DXGI_FORMAT_B8G8R8A8_UNORM;
    sd.SampleDesc.Count = 1;
    sd.Usage = D3D11_USAGE_STAGING;
    sd.CPUAccessFlags = D3D11_CPU_ACCESS_READ;
    ID3D11Texture2D *priv = nullptr; // private copy on device 2
    D3D11_TEXTURE2D_DESC pd = sd;
    pd.Usage = D3D11_USAGE_DEFAULT;
    pd.BindFlags = D3D11_BIND_SHADER_RESOURCE;
    CHECK(SUCCEEDED(dev2->CreateTexture2D(&pd, nullptr, &priv)), "device2 texture");
    ID3D11Texture2D *staging = nullptr;
    CHECK(SUCCEEDED(dev2->CreateTexture2D(&sd, nullptr, &staging)), "device2 staging");

    ID3D11Device1 *dev21 = nullptr;
    CHECK(SUCCEEDED(dev2->QueryInterface(__uuidof(ID3D11Device1), (void **)&dev21)) && dev21,
          "device2 has no ID3D11Device1");
    // Fences open on the D3D12 side (same underlying object; the path Vulkan
    // and encoders use). One D3D12 device reuses for every frame.
    ID3D12Device *dev12 = nullptr;
    CHECK(SUCCEEDED(D3D12CreateDevice(nullptr, D3D_FEATURE_LEVEL_11_0,
                                      __uuidof(ID3D12Device), (void **)&dev12)) &&
                  dev12,
          "D3D12 device creation");

    CgMove *moves = (CgMove *)malloc(sizeof(CgMove) * CG_MAX_MOVES);
    CgRect *dirty = (CgRect *)malloc(sizeof(CgRect) * CG_MAX_DIRTY);
    uint64_t lastFence = 0;
    int frames = 0;
    for (int i = 0; i < 8; i++) {
        CgGpuOut o = {};
        o.moves = moves;
        o.dirty = dirty;
        int32_t rc = cg_dxgi_acquire_gpu(h, 2000, &o);
        if (rc == CG_TIMEOUT) {
            i--;
            continue;
        }
        CHECK(rc == CG_OK, cg_dxgi_last_error(h));
        CHECK(o.slot >= 0 && o.slot < 3, "slot range");
        CHECK(o.sharedHandle != 0, "shared handle");
        CHECK(o.fenceHandle != 0, "fence handle");
        CHECK(o.fenceValue > lastFence, "fence monotonic");
        lastFence = o.fenceValue;
        printf("frame slot=%d fence=%llu %dx%d moves=%d dirties=%d ptr=%d\n", o.slot,
               (unsigned long long)o.fenceValue, o.width, o.height, o.moveCount, o.dirtyCount,
               o.ptrVisible);

        // Consumer protocol, in order: open shared objects, wait the fence
        // (the producer's copy may still sit in its command queue), take the
        // keyed mutex at key 0 (single-key protocol, see capture_abi.h), use,
        // hand back at key 0.
        ID3D11Texture2D *opened = nullptr;
        HRESULT hr = dev21->OpenSharedResource1((HANDLE)(uintptr_t)o.sharedHandle,
                                                __uuidof(ID3D11Texture2D), (void **)&opened);
        CHECK(SUCCEEDED(hr) && opened, "OpenSharedResource1");
        IDXGIKeyedMutex *km = nullptr;
        CHECK(SUCCEEDED(opened->QueryInterface(__uuidof(IDXGIKeyedMutex), (void **)&km)) && km,
              "consumer keyed mutex");
        ID3D12Fence *f = nullptr;
        hr = dev12->OpenSharedHandle((HANDLE)(uintptr_t)o.fenceHandle,
                                     __uuidof(ID3D12Fence), (void **)&f);
        CHECK(SUCCEEDED(hr) && f, "OpenSharedHandle(fence)");
        HANDLE ev = CreateEvent(nullptr, FALSE, FALSE, nullptr);
        CHECK(ev != nullptr, "event");
        CHECK(SUCCEEDED(f->SetEventOnCompletion(o.fenceValue, ev)), "SetEventOnCompletion");
        DWORD wr = WaitForSingleObject(ev, 2000);
        CloseHandle(ev);
        f->Release();
        CHECK(wr == WAIT_OBJECT_0, "fence wait timed out");

        CHECK(SUCCEEDED(km->AcquireSync(0, 2000)), "consumer mutex acquire");
        ctx2->CopyResource(priv, opened);
        opened->Release();
        ctx2->CopyResource(staging, priv);
        D3D11_MAPPED_SUBRESOURCE m = {};
        CHECK(SUCCEEDED(ctx2->Map(staging, 0, D3D11_MAP_READ, 0, &m)) && m.pData, "map");
        // Variance over a stride sample: a real desktop is never perfectly flat.
        const uint8_t *px = (const uint8_t *)m.pData;
        uint64_t sum = 0, sum2 = 0;
        const int N = 4096;
        for (int k = 0; k < N; k++) {
            uint8_t v = px[(size_t)k * (size_t)m.RowPitch / N * 4];
            sum += v;
            sum2 += (uint64_t)v * v;
        }
        ctx2->Unmap(staging, 0);
        double mean = (double)sum / N;
        double var = (double)sum2 / N - mean * mean;
        printf("  pixels mean=%.1f var=%.1f\n", mean, var);
        CHECK(mean > 0.0 || var > 0.0, "pixels are all zero (sync/copy broken)");

        CHECK(SUCCEEDED(km->ReleaseSync(0)), "consumer mutex release");
        km->Release();

        CHECK(cg_dxgi_release_gpu(h, o.slot) == CG_OK, "release");
        frames++;
        if (frames >= 5) {
            break;
        }
    }
    CHECK(frames >= 3, "too few frames acquired");

    // Pool exhaustion is fail-fast, not a hang.
    int held[3] = {-1, -1, -1};
    for (int i = 0; i < 3; i++) {
        CgGpuOut o = {};
        int32_t rc = cg_dxgi_acquire_gpu(h, 2000, &o);
        if (rc == CG_TIMEOUT) {
            i--;
            continue;
        }
        CHECK(rc == CG_OK, cg_dxgi_last_error(h));
        held[i] = o.slot;
    }
    {
        CgGpuOut o = {};
        CHECK(cg_dxgi_acquire_gpu(h, 500, &o) == CG_ERR_NO_SLOT, "expected NO_SLOT, got frame");
    }
    for (int i = 0; i < 3; i++) {
        CHECK(cg_dxgi_release_gpu(h, held[i]) == CG_OK, "release held");
    }
    printf("exhaustion behaves (NO_SLOT + recycle ok)\n");

    free(moves);
    free(dirty);
    dev12->Release();
    dev21->Release();
    priv->Release();
    staging->Release();
    ctx2->Release();
    dev2->Release();
    cg_dxgi_close(h);
    printf("PASS: gpu zero-copy path verified cross-device\n");
    return 0;
}
