// dxgi_bridge.cpp — Windows DXGI Desktop Duplication backend (MVP).
//
// Captures the primary output via IDXGIOutputDuplication into a CPU-readable
// staging texture, then copies BGRA rows into the Java-owned direct buffer.
// Single output, BGRA_8 only, cursor excluded, full-frame only.
//
// Error recovery contract: ACCESS_LOST / DEVICE_REMOVED mean the Java session
// must close and reopen the handle (mode change, display switch, protected content).
// TIMEOUT is normal (no new frame within timeoutMs) and keeps the handle usable.

#define CAPTURE_DXGI_BUILD 1

#include "capture_abi.h"

#include <windows.h>
#include <d3d11.h>
#include <dxgi1_2.h>

#include <cstdio>
#include <cstring>
#include <string>

#ifdef __GNUC__
#define CG_UNUSED __attribute__((unused))
#else
#define CG_UNUSED
#endif

struct CgContext {
    ID3D11Device *device = nullptr;
    ID3D11DeviceContext *ctx = nullptr;
    IDXGIOutputDuplication *dupl = nullptr;
    ID3D11Texture2D *staging = nullptr;
    int stagingW = 0;
    int stagingH = 0;
    int width = 0;
    int height = 0;
    char lastError[512] = {0};
};

static void setError(CgContext *c, const char *msg) {
    if (c) {
        snprintf(c->lastError, sizeof(c->lastError), "%s", msg ? msg : "unknown");
    }
}

static void setErrorHr(CgContext *c, const char *what, HRESULT hr) {
    if (c) {
        snprintf(c->lastError, sizeof(c->lastError), "%s (hr=0x%08lX)", what,
                 (unsigned long)hr);
    }
}

// Best-effort per-monitor DPI awareness so dimensions match the real framebuffer.
static void enableDpiAwareness(void) {
    HMODULE shcore = LoadLibraryW(L"shcore.dll");
    if (shcore) {
        typedef HRESULT(WINAPI * SetProc)(int);
        SetProc fn = (SetProc)GetProcAddress(shcore, "SetProcessDpiAwareness");
        if (fn) {
            fn(2 /*PROCESS_PER_MONITOR_DPI_AWARE*/);
        }
        FreeLibrary(shcore);
    } else {
        SetProcessDPIAware();
    }
}

static bool ensureStaging(CgContext *c, int w, int h) {
    if (c->staging && c->stagingW == w && c->stagingH == h) {
        return true;
    }
    if (c->staging) {
        c->staging->Release();
        c->staging = nullptr;
    }
    D3D11_TEXTURE2D_DESC d = {};
    d.Width = (UINT)w;
    d.Height = (UINT)h;
    d.MipLevels = 1;
    d.ArraySize = 1;
    d.Format = DXGI_FORMAT_B8G8R8A8_UNORM;
    d.SampleDesc.Count = 1;
    d.Usage = D3D11_USAGE_STAGING;
    d.BindFlags = 0;
    d.CPUAccessFlags = D3D11_CPU_ACCESS_READ;
    d.MiscFlags = 0;
    HRESULT hr = c->device->CreateTexture2D(&d, nullptr, &c->staging);
    if (FAILED(hr) || !c->staging) {
        setErrorHr(c, "CreateTexture2D(staging) failed", hr);
        return false;
    }
    c->stagingW = w;
    c->stagingH = h;
    return true;
}

int32_t cg_dxgi_open(int adapter, int output, CgHandle *out) {
    if (!out || adapter < 0 || output < 0) {
        return CG_ERR_INVALID_ARG;
    }
    enableDpiAwareness();

    CgContext *c = new (std::nothrow) CgContext();
    if (!c) {
        return CG_ERR_OPEN;
    }

    UINT flags = D3D11_CREATE_DEVICE_BGRA_SUPPORT;
#if !defined(NDEBUG)
    // Keep release behavior; debug layer only when explicitly available.
#endif
    D3D_FEATURE_LEVEL levels[] = {D3D_FEATURE_LEVEL_11_1, D3D_FEATURE_LEVEL_11_0,
                                  D3D_FEATURE_LEVEL_10_1, D3D_FEATURE_LEVEL_10_0};
    D3D_FEATURE_LEVEL picked = D3D_FEATURE_LEVEL_11_0;
    HRESULT hr = D3D11CreateDevice(nullptr, D3D_DRIVER_TYPE_HARDWARE, nullptr, flags, levels,
                                   (UINT)(sizeof(levels) / sizeof(levels[0])),
                                   D3D11_SDK_VERSION, &c->device, &picked, &c->ctx);
    if (FAILED(hr) || !c->device) {
        setErrorHr(c, "D3D11CreateDevice failed", hr);
        delete c;
        return CG_ERR_OPEN;
    }

    IDXGIDevice *dxgiDevice = nullptr;
    hr = c->device->QueryInterface(__uuidof(IDXGIDevice), (void **)&dxgiDevice);
    if (FAILED(hr) || !dxgiDevice) {
        setErrorHr(c, "QueryInterface(IDXGIDevice) failed", hr);
        cg_dxgi_close(c);
        return CG_ERR_OPEN;
    }
    IDXGIAdapter *dxgiAdapter = nullptr;
    hr = dxgiDevice->GetParent(__uuidof(IDXGIAdapter), (void **)&dxgiAdapter);
    dxgiDevice->Release();
    if (FAILED(hr) || !dxgiAdapter) {
        setErrorHr(c, "GetParent(IDXGIAdapter) failed", hr);
        cg_dxgi_close(c);
        return CG_ERR_OPEN;
    }
    // NOTE MVP: `adapter` selects among enumerated adapters; 0 = default.
    if (adapter > 0) {
        IDXGIFactory1 *factory = nullptr;
        if (SUCCEEDED(CreateDXGIFactory1(__uuidof(IDXGIFactory1), (void **)&factory)) &&
            factory) {
            IDXGIAdapter *alt = nullptr;
            if (SUCCEEDED(factory->EnumAdapters((UINT)adapter, &alt)) && alt) {
                dxgiAdapter->Release();
                dxgiAdapter = alt;
            }
            factory->Release();
        }
    }

    IDXGIOutput *dxgiOutput = nullptr;
    hr = dxgiAdapter->EnumOutputs((UINT)output, &dxgiOutput);
    dxgiAdapter->Release();
    if (FAILED(hr) || !dxgiOutput) {
        setErrorHr(c, "EnumOutputs failed (output index out of range?)", hr);
        cg_dxgi_close(c);
        return CG_ERR_OPEN;
    }
    DXGI_OUTPUT_DESC odesc = {};
    if (SUCCEEDED(dxgiOutput->GetDesc(&odesc))) {
        c->width = (int)(odesc.DesktopCoordinates.right - odesc.DesktopCoordinates.left);
        c->height = (int)(odesc.DesktopCoordinates.bottom - odesc.DesktopCoordinates.top);
    }

    IDXGIOutput1 *output1 = nullptr;
    hr = dxgiOutput->QueryInterface(__uuidof(IDXGIOutput1), (void **)&output1);
    dxgiOutput->Release();
    if (FAILED(hr) || !output1) {
        setErrorHr(c, "Output does not support IDXGIOutput1 (need Win8+)", hr);
        cg_dxgi_close(c);
        return CG_ERR_OPEN;
    }
    hr = output1->DuplicateOutput(c->device, &c->dupl);
    output1->Release();
    if (FAILED(hr) || !c->dupl) {
        setErrorHr(c, "DuplicateOutput failed", hr);
        cg_dxgi_close(c);
        return CG_ERR_OPEN;
    }
    if (c->width <= 0 || c->height <= 0) {
        // Fall back to probing on first acquire; keep handle usable.
        c->width = 0;
        c->height = 0;
    }
    *out = c;
    return CG_OK;
}

int32_t cg_dxgi_info(CgHandle h, CgInfo *out) {
    if (!h || !out) {
        return CG_ERR_INVALID_ARG;
    }
    out->width = h->width;
    out->height = h->height;
    out->stride = h->width * 4;
    return CG_OK;
}

int32_t cg_dxgi_acquire(CgHandle h, void *dst, int32_t dstStride, int32_t dstCap,
                        int timeoutMs, uint64_t *qpcOut) {
    if (!h || !dst || dstStride <= 0 || dstCap <= 0) {
        return CG_ERR_INVALID_ARG;
    }
    if (!h->dupl || !h->device || !h->ctx) {
        setError(h, "handle not open");
        return CG_ERR_OPEN;
    }

    IDXGIResource *res = nullptr;
    DXGI_OUTDUPL_FRAME_INFO info = {};
    HRESULT hr = h->dupl->AcquireNextFrame((UINT)(timeoutMs < 0 ? 0 : timeoutMs), &info, &res);
    if (hr == DXGI_ERROR_WAIT_TIMEOUT) {
        return CG_TIMEOUT;
    }
    if (hr == DXGI_ERROR_ACCESS_LOST) {
        setErrorHr(h, "AccessLost (mode change / session switch); reopen required", hr);
        return CG_ERR_ACCESS_LOST;
    }
    if (hr == DXGI_ERROR_DEVICE_REMOVED || hr == DXGI_ERROR_DEVICE_RESET ||
        hr == DXGI_ERROR_DRIVER_INTERNAL_ERROR) {
        setErrorHr(h, "Device removed/reset; reopen required", hr);
        return CG_ERR_DEVICE_REMOVED;
    }
    if (FAILED(hr) || !res) {
        setErrorHr(h, "AcquireNextFrame failed", hr);
        return CG_ERR_OPEN;
    }

    int32_t rc = CG_OK;
    ID3D11Texture2D *frame = nullptr;
    hr = res->QueryInterface(__uuidof(ID3D11Texture2D), (void **)&frame);
    res->Release();
    if (FAILED(hr) || !frame) {
        setErrorHr(h, "QueryInterface(ID3D11Texture2D) failed", hr);
        h->dupl->ReleaseFrame();
        return CG_ERR_COPY;
    }

    D3D11_TEXTURE2D_DESC fdesc = {};
    frame->GetDesc(&fdesc);
    if (fdesc.Format != DXGI_FORMAT_B8G8R8A8_UNORM) {
        snprintf(h->lastError, sizeof(h->lastError),
                 "Unexpected desktop format %d (MVP supports B8G8R8A8 only)", (int)fdesc.Format);
        frame->Release();
        h->dupl->ReleaseFrame();
        return CG_ERR_COPY;
    }
    int w = (int)fdesc.Width;
    int bh = (int)fdesc.Height;
    if ((int64_t)dstStride * (int64_t)bh > (int64_t)dstCap) {
        setError(h, "destination buffer too small");
        frame->Release();
        h->dupl->ReleaseFrame();
        return CG_ERR_INVALID_ARG;
    }
    if (!ensureStaging(h, w, bh)) {
        frame->Release();
        h->dupl->ReleaseFrame();
        return CG_ERR_COPY;
    }

    h->ctx->CopyResource(h->staging, frame);
    frame->Release();

    D3D11_MAPPED_SUBRESOURCE mapped = {};
    hr = h->ctx->Map(h->staging, 0, D3D11_MAP_READ, 0, &mapped);
    if (FAILED(hr) || !mapped.pData) {
        setErrorHr(h, "Map(staging) failed", hr);
        h->dupl->ReleaseFrame();
        return CG_ERR_COPY;
    }
    const uint8_t *src = (const uint8_t *)mapped.pData;
    uint8_t *d = (uint8_t *)dst;
    int rowBytes = w * 4;
    int copyBytes = rowBytes < dstStride ? rowBytes : dstStride;
    for (int y = 0; y < bh; y++) {
        memcpy(d + (size_t)y * (size_t)dstStride, src + (size_t)y * (size_t)mapped.RowPitch,
               (size_t)copyBytes);
    }
    h->ctx->Unmap(h->staging, 0);

    h->width = w;
    h->height = bh;

    LARGE_INTEGER qpc;
    QueryPerformanceCounter(&qpc);
    if (qpcOut) {
        *qpcOut = (uint64_t)qpc.QuadPart;
    }
    h->dupl->ReleaseFrame();
    return rc;
}

int64_t cg_qpc_frequency(void) {
    LARGE_INTEGER f;
    if (!QueryPerformanceFrequency(&f) || f.QuadPart <= 0) {
        return 0;
    }
    return (int64_t)f.QuadPart;
}

const char *cg_dxgi_last_error(CgHandle h) {
    if (!h || !h->lastError[0]) {
        return "";
    }
    return h->lastError;
}

void cg_dxgi_close(CgHandle h) {
    if (!h) {
        return;
    }
    if (h->staging) {
        h->staging->Release();
    }
    if (h->dupl) {
        h->dupl->Release();
    }
    if (h->ctx) {
        h->ctx->Release();
    }
    if (h->device) {
        h->device->Release();
    }
    delete h;
}

// ---------------------------------------------------------------------------
// JNI wrappers (same DLL; keeps the C ABI above testable on its own).
// ---------------------------------------------------------------------------
#include <jni.h>

#define JNI_FN(name) Java_balbucio_capturegraphics_dxgi_DxgiNative_##name

extern "C" {

JNIEXPORT jlong JNICALL JNI_FN(nOpen)(JNIEnv *env, jclass CG_UNUSED cls, jint adapter,
                                      jint output) {
    CgHandle h = nullptr;
    int32_t rc = cg_dxgi_open((int)adapter, (int)output, &h);
    if (rc != CG_OK || !h) {
        const char *msg = (h && h->lastError[0]) ? h->lastError : "cg_dxgi_open failed";
        if (h) {
            cg_dxgi_close(h);
        }
        jclass ex = env->FindClass("java/lang/IllegalStateException");
        if (ex) {
            env->ThrowNew(ex, msg);
        }
        return 0;
    }
    return (jlong)(intptr_t)h;
}

JNIEXPORT jintArray JNICALL JNI_FN(nInfo)(JNIEnv *env, jclass CG_UNUSED cls, jlong handle) {
    CgHandle h = (CgHandle)(intptr_t)handle;
    CgInfo info = {};
    if (!h || cg_dxgi_info(h, &info) != CG_OK) {
        return nullptr;
    }
    jintArray arr = env->NewIntArray(3);
    if (arr) {
        jint v[3] = {info.width, info.height, info.stride};
        env->SetIntArrayRegion(arr, 0, 3, v);
    }
    return arr;
}

JNIEXPORT jint JNICALL JNI_FN(nAcquire)(JNIEnv *env, jclass CG_UNUSED cls, jlong handle,
                                        jobject dst, jint stride, jint timeoutMs,
                                        jlongArray qpcOut) {
    CgHandle h = (CgHandle)(intptr_t)handle;
    if (!h || !dst || stride <= 0) {
        return CG_ERR_INVALID_ARG;
    }
    void *ptr = env->GetDirectBufferAddress(dst);
    jlong cap = env->GetDirectBufferCapacity(dst);
    if (!ptr || cap <= 0 || (int64_t)stride > cap) {
        return CG_ERR_INVALID_ARG;
    }
    uint64_t qpc = 0;
    int32_t rc =
        cg_dxgi_acquire(h, ptr, (int32_t)stride, (int32_t)cap, (int)timeoutMs, &qpc);
    if (rc == CG_OK && qpcOut) {
        jlong v = (jlong)qpc;
        env->SetLongArrayRegion(qpcOut, 0, 1, &v);
    }
    return rc;
}

JNIEXPORT jlong JNICALL JNI_FN(nQpcFrequency)(JNIEnv *env CG_UNUSED, jclass CG_UNUSED cls) {
    return (jlong)cg_qpc_frequency();
}

JNIEXPORT jstring JNICALL JNI_FN(nLastError)(JNIEnv *env, jclass CG_UNUSED cls, jlong handle) {
    CgHandle h = (CgHandle)(intptr_t)handle;
    const char *m = cg_dxgi_last_error(h);
    return env->NewStringUTF(m ? m : "");
}

JNIEXPORT void JNICALL JNI_FN(nClose)(JNIEnv *env CG_UNUSED, jclass CG_UNUSED cls,
                                      jlong handle) {
    CgHandle h = (CgHandle)(intptr_t)handle;
    cg_dxgi_close(h);
}

} // extern "C"
