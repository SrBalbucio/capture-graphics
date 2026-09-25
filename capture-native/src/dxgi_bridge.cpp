// dxgi_bridge.cpp — Windows DXGI Desktop Duplication backend (Phase 2).
//
// Captures one output via IDXGIOutputDuplication into a CPU-readable staging
// texture, then copies BGRA rows into the Java-owned direct buffer. In the same
// native round-trip it also reports dirty/move rects and pointer state, so the
// Java hot path stays allocation-free with a single JNI transition per frame.
//
// Error recovery contract: ACCESS_LOST / DEVICE_REMOVED mean the Java session
// must close and reopen the handle (mode change, display switch, protected
// content). TIMEOUT is normal (no new frame within timeoutMs).
//
// Rect coordinates are output-relative (0,0 = top-left of the captured output).

#define CAPTURE_DXGI_BUILD 1

#include "capture_abi.h"

#include <windows.h>
#include <d3d11.h>
#include <d3d11_4.h>
#include <dxgi1_2.h>
#include <dxgi1_6.h>

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <new>

#ifdef __GNUC__
#define CG_UNUSED __attribute__((unused))
#else
#define CG_UNUSED
#endif

struct CgGpuSlot {
    ID3D11Texture2D *tex = nullptr;
    HANDLE shared = nullptr; // NT handle value (owned by the texture)
    IDXGIKeyedMutex *km = nullptr; // exclusive-access protocol (see acquire_gpu)
    bool busy = false;
};

struct CgContext {
    ID3D11Device *device = nullptr;
    ID3D11DeviceContext *ctx = nullptr;
    IDXGIOutputDuplication *dupl = nullptr;
    ID3D11Texture2D *staging = nullptr;
    int stagingW = 0;
    int stagingH = 0;
    int width = 0;
    int height = 0;
    int originX = 0;
    int originY = 0;
    // Cached pointer shape (updated only when DXGI reports a new one).
    uint8_t *ptrShape = nullptr;
    int32_t ptrShapeSize = 0;
    int32_t ptrW = 0, ptrH = 0, ptrHotX = 0, ptrHotY = 0, ptrType = CG_PTR_NONE;
    // GPU shared-texture pool (lazy: allocated by cg_dxgi_gpu_init).
    CgGpuSlot *slots = nullptr;
    int32_t slotCount = 0;
    int slotW = 0;
    int slotH = 0;
    ID3D11Fence *fence = nullptr;
    ID3D11DeviceContext4 *ctx4 = nullptr; // for fence Signal
    HANDLE fenceShared = nullptr;
    uint64_t fenceValue = 0;
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

// --- Output enumeration (no device needed) ----------------------------------

static IDXGIOutput *enumOutput(int adapter, int output, DXGI_OUTPUT_DESC *odesc) {
    IDXGIFactory1 *factory = nullptr;
    if (FAILED(CreateDXGIFactory1(__uuidof(IDXGIFactory1), (void **)&factory)) || !factory) {
        return nullptr;
    }
    IDXGIAdapter *dxgiAdapter = nullptr;
    HRESULT hr = factory->EnumAdapters((UINT)(adapter < 0 ? 0 : adapter), &dxgiAdapter);
    factory->Release();
    if (FAILED(hr) || !dxgiAdapter) {
        return nullptr;
    }
    IDXGIOutput *dxgiOutput = nullptr;
    hr = dxgiAdapter->EnumOutputs((UINT)output, &dxgiOutput);
    dxgiAdapter->Release();
    if (FAILED(hr) || !dxgiOutput) {
        return nullptr;
    }
    if (odesc) {
        memset(odesc, 0, sizeof(*odesc));
        dxgiOutput->GetDesc(odesc);
    }
    return dxgiOutput; // caller releases
}

int32_t cg_dxgi_output_count(int adapter, int32_t *out) {
    if (!out) {
        return CG_ERR_INVALID_ARG;
    }
    int32_t n = 0;
    for (;; n++) {
        IDXGIOutput *o = enumOutput(adapter, n, nullptr);
        if (!o) {
            break;
        }
        o->Release();
    }
    *out = n;
    return CG_OK;
}

int32_t cg_dxgi_output_desc(int adapter, int output, CgOutputDesc *out) {
    if (!out || adapter < 0 || output < 0) {
        return CG_ERR_INVALID_ARG;
    }
    DXGI_OUTPUT_DESC odesc = {};
    IDXGIOutput *dxgiOutput = enumOutput(adapter, output, &odesc);
    if (!dxgiOutput) {
        return CG_ERR_OPEN;
    }
    out->x = (int32_t)odesc.DesktopCoordinates.left;
    out->y = (int32_t)odesc.DesktopCoordinates.top;
    out->width = (int32_t)(odesc.DesktopCoordinates.right - odesc.DesktopCoordinates.left);
    out->height = (int32_t)(odesc.DesktopCoordinates.bottom - odesc.DesktopCoordinates.top);
    out->hdr = 0;
    IDXGIOutput6 *output6 = nullptr;
    if (SUCCEEDED(dxgiOutput->QueryInterface(__uuidof(IDXGIOutput6), (void **)&output6)) &&
        output6) {
        DXGI_OUTPUT_DESC1 d1 = {};
        if (SUCCEEDED(output6->GetDesc1(&d1))) {
            if (d1.ColorSpace != DXGI_COLOR_SPACE_RGB_FULL_G22_NONE_P709) {
                out->hdr = 1;
            }
        }
        output6->Release();
    }
    dxgiOutput->Release();
    return CG_OK;
}

// --- Open / info / close -----------------------------------------------------

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

    CgOutputDesc odesc = {};
    if (cg_dxgi_output_desc(adapter, output, &odesc) != CG_OK) {
        setError(c, "output index out of range (see cg_dxgi_output_count)");
        cg_dxgi_close(c);
        return CG_ERR_OPEN;
    }
    c->originX = odesc.x;
    c->originY = odesc.y;
    c->width = odesc.width;
    c->height = odesc.height;

    // Bind the duplication session to the same adapter/output pair.
    IDXGIDevice *dxgiDevice = nullptr;
    hr = c->device->QueryInterface(__uuidof(IDXGIDevice), (void **)&dxgiDevice);
    if (FAILED(hr) || !dxgiDevice) {
        setErrorHr(c, "QueryInterface(IDXGIDevice) failed", hr);
        cg_dxgi_close(c);
        return CG_ERR_OPEN;
    }
    IDXGIAdapter *dxgiAdapter = nullptr;
    if (adapter > 0) {
        IDXGIFactory1 *factory = nullptr;
        if (SUCCEEDED(CreateDXGIFactory1(__uuidof(IDXGIFactory1), (void **)&factory)) &&
            factory) {
            factory->EnumAdapters((UINT)adapter, &dxgiAdapter);
            factory->Release();
        }
    }
    if (!dxgiAdapter) {
        hr = dxgiDevice->GetParent(__uuidof(IDXGIAdapter), (void **)&dxgiAdapter);
    }
    dxgiDevice->Release();
    if (!dxgiAdapter) {
        setErrorHr(c, "adapter lookup failed", hr);
        cg_dxgi_close(c);
        return CG_ERR_OPEN;
    }
    IDXGIOutput *dxgiOutput = nullptr;
    hr = dxgiAdapter->EnumOutputs((UINT)output, &dxgiOutput);
    dxgiAdapter->Release();
    if (FAILED(hr) || !dxgiOutput) {
        setErrorHr(c, "EnumOutputs failed", hr);
        cg_dxgi_close(c);
        return CG_ERR_OPEN;
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

// --- Rect + pointer helpers ---------------------------------------------------

// Fetches move rects (capped). Always safe to call while a frame is acquired.
// NOTE: the "required size" out-param is in BYTES, and a zero-size probe is
// rejected with E_INVALIDARG — so fetch with a stack buffer first and grow on
// MORE_DATA instead of probing with NULL.
static void fetchMoves(CgContext *c, CgAcquireOut *out) {
    out->moveCount = 0;
    if (!out->moves) {
        return;
    }
    DXGI_OUTDUPL_MOVE_RECT stack[64];
    UINT gotBytes = 0;
    HRESULT hr = c->dupl->GetFrameMoveRects(sizeof(stack), stack, &gotBytes);
    const DXGI_OUTDUPL_MOVE_RECT *src = stack;
    UINT count = 0;
    DXGI_OUTDUPL_MOVE_RECT *heap = nullptr;
    if (hr == DXGI_ERROR_MORE_DATA && gotBytes > 0 && gotBytes <= 4 * 1024 * 1024) {
        heap = (DXGI_OUTDUPL_MOVE_RECT *)malloc(gotBytes);
        if (heap) {
            UINT got2 = 0;
            if (SUCCEEDED(c->dupl->GetFrameMoveRects(gotBytes, heap, &got2))) {
                src = heap;
                count = got2 / (UINT)sizeof(*heap);
            }
        }
    } else if (SUCCEEDED(hr)) {
        count = gotBytes / (UINT)sizeof(*stack);
    }
    UINT n = count < (UINT)CG_MAX_MOVES ? count : (UINT)CG_MAX_MOVES;
    for (UINT i = 0; i < n; i++) {
        CgMove *m = &out->moves[out->moveCount++];
        m->srcX = src[i].SourcePoint.x;
        m->srcY = src[i].SourcePoint.y;
        m->dstX = src[i].DestinationRect.left;
        m->dstY = src[i].DestinationRect.top;
        m->width = src[i].DestinationRect.right - src[i].DestinationRect.left;
        m->height = src[i].DestinationRect.bottom - src[i].DestinationRect.top;
    }
    free(heap);
}

static void fetchDirties(CgContext *c, CgAcquireOut *out) {
    out->dirtyCount = 0;
    if (!out->dirty) {
        return;
    }
    RECT stack[64];
    UINT gotBytes = 0;
    HRESULT hr = c->dupl->GetFrameDirtyRects(sizeof(stack), stack, &gotBytes);
    const RECT *src = stack;
    UINT count = 0;
    RECT *heap = nullptr;
    if (hr == DXGI_ERROR_MORE_DATA && gotBytes > 0 && gotBytes <= 4 * 1024 * 1024) {
        heap = (RECT *)malloc(gotBytes);
        if (heap) {
            UINT got2 = 0;
            if (SUCCEEDED(c->dupl->GetFrameDirtyRects(gotBytes, heap, &got2))) {
                src = heap;
                count = got2 / (UINT)sizeof(*heap);
            }
        }
    } else if (SUCCEEDED(hr)) {
        count = gotBytes / (UINT)sizeof(*stack);
    }
    UINT n = count < (UINT)CG_MAX_DIRTY ? count : (UINT)CG_MAX_DIRTY;
    for (UINT i = 0; i < n; i++) {
        CgRect *r = &out->dirty[out->dirtyCount++];
        r->x = src[i].left;
        r->y = src[i].top;
        r->width = src[i].right - src[i].left;
        r->height = src[i].bottom - src[i].top;
    }
    free(heap);
}

static int mapPtrType(UINT t) {
    switch (t) {
        case (UINT)DXGI_OUTDUPL_POINTER_SHAPE_TYPE_MONOCHROME:
            return CG_PTR_MONO;
        case (UINT)DXGI_OUTDUPL_POINTER_SHAPE_TYPE_COLOR:
            return CG_PTR_COLOR;
        case (UINT)DXGI_OUTDUPL_POINTER_SHAPE_TYPE_MASKED_COLOR:
            return CG_PTR_MASKED;
        default:
            return CG_PTR_NONE;
    }
}

// Refreshes the cached shape when DXGI reports a new one; position/visibility
// come from the current frame info. Must be called while a frame is held.
static void cacheShape(CgContext *c, const uint8_t *data, UINT size,
                       const DXGI_OUTDUPL_POINTER_SHAPE_INFO *si) {
    if (!data || size == 0 || size > 4 * 1024 * 1024 || !si || si->Width == 0 ||
        si->Height == 0) {
        return;
    }
    uint8_t *tmp = (uint8_t *)malloc(size);
    if (!tmp) {
        return;
    }
    memcpy(tmp, data, size);
    free(c->ptrShape);
    c->ptrShape = tmp;
    c->ptrShapeSize = (int32_t)size;
    c->ptrW = (int32_t)si->Width;
    c->ptrH = (int32_t)si->Height;
    c->ptrHotX = (int32_t)si->HotSpot.x;
    c->ptrHotY = (int32_t)si->HotSpot.y;
    c->ptrType = mapPtrType(si->Type);
    if (c->ptrType == CG_PTR_MONO) {
        c->ptrH /= 2; // mono buffer packs AND mask over XOR mask
    }
}

// Refreshes the cached shape when DXGI reports a new one, then publishes the
// cache into the caller's shape buffer. NOTE: like the rect APIs, a zero-size
// probe is rejected — always fetch with a real buffer. Must be called while a
// frame is held.
static void fetchPointer(CgContext *c, const DXGI_OUTDUPL_FRAME_INFO *info, CgAcquireOut *out) {
    out->ptrVisible = 0;
    out->ptrX = info ? (info->PointerPosition.Position.x - c->originX) : 0;
    out->ptrY = info ? (info->PointerPosition.Position.y - c->originY) : 0;
    out->ptrW = c->ptrW;
    out->ptrH = c->ptrH;
    out->ptrHotX = c->ptrHotX;
    out->ptrHotY = c->ptrHotY;
    out->ptrType = c->ptrType;
    out->ptrShapeSize = 0;

    bool fresh = false; // caller buffer already holds the current shape
    if (out->ptrShape && out->ptrShapeCap > 0) {
        UINT required = 0;
        DXGI_OUTDUPL_POINTER_SHAPE_INFO si = {};
        HRESULT hr = c->dupl->GetFramePointerShape((UINT)out->ptrShapeCap, out->ptrShape,
                                                   &required, &si);
        if (SUCCEEDED(hr) && required > 0 && required <= (UINT)out->ptrShapeCap) {
            cacheShape(c, (const uint8_t *)out->ptrShape, required, &si);
            out->ptrShapeSize = (int32_t)required;
            fresh = true;
        } else if (hr == DXGI_ERROR_MORE_DATA && required > 0 && required <= 4 * 1024 * 1024) {
            uint8_t *tmp = (uint8_t *)malloc(required);
            if (tmp) {
                UINT got = required;
                DXGI_OUTDUPL_POINTER_SHAPE_INFO si2 = {};
                if (SUCCEEDED(c->dupl->GetFramePointerShape(required, tmp, &got, &si2)) &&
                    got > 0) {
                    cacheShape(c, tmp, got, &si2);
                }
                free(tmp);
            }
        }
        // required == 0 (or failure): shape unchanged — fall through to cache.
    } else {
        // No caller buffer: still track shape updates via a small scratch fetch.
        uint8_t scratch[4096];
        UINT required = 0;
        DXGI_OUTDUPL_POINTER_SHAPE_INFO si = {};
        HRESULT hr = c->dupl->GetFramePointerShape(sizeof(scratch), scratch, &required, &si);
        if (SUCCEEDED(hr) && required > 0 && required <= sizeof(scratch)) {
            cacheShape(c, scratch, required, &si);
        } else if (hr == DXGI_ERROR_MORE_DATA && required > sizeof(scratch) &&
                   required <= 4 * 1024 * 1024) {
            uint8_t *tmp = (uint8_t *)malloc(required);
            if (tmp) {
                UINT got = required;
                DXGI_OUTDUPL_POINTER_SHAPE_INFO si2 = {};
                if (SUCCEEDED(c->dupl->GetFramePointerShape(required, tmp, &got, &si2)) &&
                    got > 0) {
                    cacheShape(c, tmp, got, &si2);
                }
                free(tmp);
            }
        }
    }
    if (c->ptrShape && c->ptrW > 0) {
        if (info && info->PointerPosition.Visible) {
            out->ptrVisible = 1;
        }
        out->ptrW = c->ptrW;
        out->ptrH = c->ptrH;
        out->ptrHotX = c->ptrHotX;
        out->ptrHotY = c->ptrHotY;
        out->ptrType = c->ptrType;
        if (!fresh && out->ptrShape && out->ptrShapeCap > 0) {
            int32_t n = c->ptrShapeSize < out->ptrShapeCap ? c->ptrShapeSize : out->ptrShapeCap;
            memcpy(out->ptrShape, c->ptrShape, (size_t)n);
            out->ptrShapeSize = n;
        }
    }
}

// --- Shared acquisition prologue ------------------------------------------------
//
// Acquires the next duplication frame and resolves it to a BGRA texture.
// On success the caller owns `*frameOut` AND the held duplication frame and must
// ReleaseFrame exactly once on every path (the helper releases it for errors).
static int32_t acquireTexture(CgContext *h, int timeoutMs, ID3D11Texture2D **frameOut,
                              DXGI_OUTDUPL_FRAME_INFO *infoOut, int *wOut, int *hOut) {
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
                 "Unexpected desktop format %d (only B8G8R8A8 supported)", (int)fdesc.Format);
        frame->Release();
        h->dupl->ReleaseFrame();
        return CG_ERR_COPY;
    }
    *frameOut = frame;
    if (infoOut) {
        *infoOut = info;
    }
    if (wOut) {
        *wOut = (int)fdesc.Width;
    }
    if (hOut) {
        *hOut = (int)fdesc.Height;
    }
    return CG_OK;
}

// --- Full acquisition ----------------------------------------------------------

int32_t cg_dxgi_acquire_full(CgHandle h, void *dst, int32_t dstStride, int32_t dstCap,
                             int timeoutMs, CgAcquireOut *out) {
    if (!h || !dst || !out || dstStride <= 0 || dstCap <= 0) {
        return CG_ERR_INVALID_ARG;
    }
    if (!h->dupl || !h->device || !h->ctx) {
        setError(h, "handle not open");
        return CG_ERR_OPEN;
    }
    // NOTE: never memset *out — it carries caller-owned buffer pointers.
    out->qpc = 0;
    out->width = 0;
    out->height = 0;
    out->moveCount = 0;
    out->dirtyCount = 0;
    out->ptrVisible = 0;
    out->ptrX = out->ptrY = 0;
    out->ptrW = out->ptrH = out->ptrHotX = out->ptrHotY = out->ptrType = 0;
    out->ptrShapeSize = 0;

    DXGI_OUTDUPL_FRAME_INFO info = {};
    int w = 0, bh = 0;
    ID3D11Texture2D *frame = nullptr;
    int32_t acq = acquireTexture(h, timeoutMs, &frame, &info, &w, &bh);
    if (acq != CG_OK) {
        return acq; // TIMEOUT or fatal; helper already released on errors
    }

    int32_t rc = CG_OK;
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
    HRESULT hr = h->ctx->Map(h->staging, 0, D3D11_MAP_READ, 0, &mapped);
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

    fetchMoves(h, out);
    fetchDirties(h, out);
    fetchPointer(h, &info, out);

    LARGE_INTEGER qpc;
    QueryPerformanceCounter(&qpc);
    out->qpc = (uint64_t)qpc.QuadPart;
    out->width = w;
    out->height = bh;

    h->dupl->ReleaseFrame();
    return rc;
}

// --- GPU shared-texture path ----------------------------------------------------
//
// Zero-copy acquisition for GPU consumers (encoders, renderers): the desktop is
// copied GPU-side into a session-owned shared texture and handed out as an NT
// handle together with a signaled fence value. No Map, no memcpy, no CPU touch.

static void freeGpu(CgContext *h) {
    if (h->slots) {
        for (int32_t i = 0; i < h->slotCount; i++) {
            if (h->slots[i].km) {
                h->slots[i].km->Release();
            }
            if (h->slots[i].tex) {
                h->slots[i].tex->Release();
            }
            // NOTE: h->slots[i].shared is owned by the texture; no CloseHandle.
        }
        free(h->slots);
        h->slots = nullptr;
    }
    h->slotCount = 0;
    if (h->fence) {
        h->fence->Release();
        h->fence = nullptr;
    }
    if (h->ctx4) {
        h->ctx4->Release();
        h->ctx4 = nullptr;
    }
    if (h->fenceShared) {
        CloseHandle(h->fenceShared);
        h->fenceShared = nullptr;
    }
    h->fenceValue = 0;
}

int32_t cg_dxgi_gpu_init(CgHandle h, int32_t slots) {
    if (!h || slots < 2 || slots > 16) {
        return CG_ERR_INVALID_ARG;
    }
    if (!h->dupl || !h->device || !h->ctx) {
        setError(h, "handle not open");
        return CG_ERR_OPEN;
    }
    if (h->slots) {
        return CG_OK; // idempotent
    }
    int w = h->width > 0 ? h->width : 0;
    int bh = h->height > 0 ? h->height : 0;
    if (w <= 0 || bh <= 0) {
        // Geometry unknown until the first frame; init lazily on first acquire.
        // Record the request; slots are allocated once a frame reports its size.
        h->slotCount = -slots; // negative = pending
        return CG_OK;
    }
    // Fence first: without cross-device sync the handles are unusable.
    // (CreateFence on Device5, Signal on DeviceContext4.)
    ID3D11Device5 *dev5 = nullptr;
    if (FAILED(h->device->QueryInterface(__uuidof(ID3D11Device5), (void **)&dev5)) || !dev5) {
        setError(h, "ID3D11Fence unsupported (need Windows 10 1703+)");
        return CG_ERR_OPEN;
    }
    if (FAILED(h->ctx->QueryInterface(__uuidof(ID3D11DeviceContext4), (void **)&h->ctx4)) ||
        !h->ctx4) {
        setError(h, "ID3D11DeviceContext4 unsupported (need Windows 10 1703+)");
        dev5->Release();
        return CG_ERR_OPEN;
    }
    HRESULT hr = dev5->CreateFence(0, D3D11_FENCE_FLAG_SHARED, __uuidof(ID3D11Fence),
                                   (void **)&h->fence);
    dev5->Release();
    if (FAILED(hr) || !h->fence) {
        setErrorHr(h, "CreateFence failed", hr);
        return CG_ERR_OPEN;
    }
    hr = h->fence->CreateSharedHandle(nullptr, GENERIC_ALL, nullptr, &h->fenceShared);
    if (FAILED(hr) || !h->fenceShared) {
        setErrorHr(h, "fence CreateSharedHandle failed", hr);
        freeGpu(h);
        return CG_ERR_OPEN;
    }

    h->slots = (CgGpuSlot *)calloc((size_t)slots, sizeof(CgGpuSlot));
    if (!h->slots) {
        freeGpu(h);
        return CG_ERR_OPEN;
    }
    for (int32_t i = 0; i < slots; i++) {
        D3D11_TEXTURE2D_DESC d = {};
        d.Width = (UINT)w;
        d.Height = (UINT)bh;
        d.MipLevels = 1;
        d.ArraySize = 1;
        d.Format = DXGI_FORMAT_B8G8R8A8_UNORM;
        d.SampleDesc.Count = 1;
        d.Usage = D3D11_USAGE_DEFAULT;
        d.BindFlags = D3D11_BIND_SHADER_RESOURCE;
        d.CPUAccessFlags = 0;
        // NOTE: SHARED_NTHANDLE alone is rejected (E_INVALIDARG) on real drivers;
        // it must ride with SHARED_KEYEDMUTEX, which additionally gives consumers
        // the classic AcquireSync/ReleaseSync handoff via IDXGIKeyedMutex.
        d.MiscFlags = D3D11_RESOURCE_MISC_SHARED_NTHANDLE | D3D11_RESOURCE_MISC_SHARED_KEYEDMUTEX;
        ID3D11Texture2D *tex = nullptr;
        hr = h->device->CreateTexture2D(&d, nullptr, &tex);
        if (FAILED(hr) || !tex) {
            setErrorHr(h, "shared texture creation failed", hr);
            freeGpu(h);
            return CG_ERR_OPEN;
        }
        IDXGIResource1 *r1 = nullptr;
        hr = tex->QueryInterface(__uuidof(IDXGIResource1), (void **)&r1);
        if (FAILED(hr) || !r1) {
            setErrorHr(h, "texture is not shareable", hr);
            tex->Release();
            freeGpu(h);
            return CG_ERR_OPEN;
        }
        HANDLE shared = nullptr;
        hr = r1->CreateSharedHandle(nullptr, DXGI_SHARED_RESOURCE_READ, nullptr, &shared);
        r1->Release();
        if (FAILED(hr) || !shared) {
            setErrorHr(h, "CreateSharedHandle failed", hr);
            tex->Release();
            freeGpu(h);
            return CG_ERR_OPEN;
        }
        IDXGIKeyedMutex *km = nullptr;
        hr = tex->QueryInterface(__uuidof(IDXGIKeyedMutex), (void **)&km);
        if (FAILED(hr) || !km) {
            setErrorHr(h, "keyed mutex unavailable", hr);
            tex->Release();
            freeGpu(h);
            return CG_ERR_OPEN;
        }
        h->slots[i].tex = tex;
        h->slots[i].shared = shared;
        h->slots[i].km = km;
    }
    h->slotCount = slots;
    h->slotW = w;
    h->slotH = bh;
    return CG_OK;
}

// Completes a pending gpu_init once the first frame reveals the geometry.
static int32_t ensureGpuSize(CgContext *h, int w, int bh) {
    if (h->slotCount >= 0) {
        if (h->slotW != w || h->slotH != bh) {
            // Resolution changed after init: rebuild the pool (all slots must be
            // free; a checked-out slot keeps its texture alive via Java's handle
            // only in the logical sense — physical rebuild while busy would
            // invalidate it, so refuse instead and let the session reopen).
            for (int32_t i = 0; i < h->slotCount; i++) {
                if (h->slots[i].busy) {
                    setError(h, "resolution changed with frames in flight; reopen required");
                    return CG_ERR_ACCESS_LOST;
                }
            }
            int32_t n = h->slotCount;
            freeGpu(h);
            h->slotCount = -n;
        } else {
            return CG_OK;
        }
    }
    if (h->slotCount < 0) {
        int32_t n = -h->slotCount;
        h->slotCount = 0;
        h->width = w;
        h->height = bh;
        int32_t rc = cg_dxgi_gpu_init(h, n);
        if (rc != CG_OK) {
            h->slotCount = -n; // stay pending
            return rc;
        }
    }
    return CG_OK;
}

int32_t cg_dxgi_acquire_gpu(CgHandle h, int timeoutMs, CgGpuOut *out) {
    if (!h || !out) {
        return CG_ERR_INVALID_ARG;
    }
    if (!h->dupl || !h->device || !h->ctx) {
        setError(h, "handle not open");
        return CG_ERR_OPEN;
    }
    if (h->slotCount == 0) {
        setError(h, "gpu pool not initialized (call cg_dxgi_gpu_init)");
        return CG_ERR_INVALID_ARG;
    }
    // NOTE: never memset *out — it carries caller-owned rect buffers.
    out->slot = -1;
    out->sharedHandle = 0;
    out->fenceHandle = (uint64_t)(uintptr_t)h->fenceShared;
    out->fenceValue = 0;
    out->width = 0;
    out->height = 0;
    out->qpc = 0;
    out->moveCount = 0;
    out->dirtyCount = 0;
    out->ptrVisible = 0;
    out->ptrX = out->ptrY = 0;

    ID3D11Texture2D *frame = nullptr;
    DXGI_OUTDUPL_FRAME_INFO info = {};
    int w = 0, bh = 0;
    int32_t acq = acquireTexture(h, timeoutMs, &frame, &info, &w, &bh);
    if (acq != CG_OK) {
        return acq;
    }
    int32_t rc = ensureGpuSize(h, w, bh);
    if (rc != CG_OK) {
        frame->Release();
        h->dupl->ReleaseFrame();
        return rc;
    }
    int32_t slot = -1;
    for (int32_t i = 0; i < h->slotCount; i++) {
        if (!h->slots[i].busy) {
            slot = i;
            break;
        }
    }
    if (slot < 0) {
        setError(h, "no free gpu slot (close frames promptly)");
        frame->Release();
        h->dupl->ReleaseFrame();
        return CG_ERR_NO_SLOT;
    }

    // Exclusive-access protocol (mandatory for SHARED_KEYEDMUTEX textures:
    // without it cross-device reads see zeros). Single key 0 on BOTH sides —
    // the pool's busy flags already order producer/consumer turns, so the mutex
    // only provides GPU-cache coherency, never blocking in legitimate flows.
    // NOTE: AcquireSync reports contention as WAIT_TIMEOUT (a *success* code!),
    // so it must be compared explicitly — FAILED() never catches it.
    HRESULT mhr = h->slots[slot].km->AcquireSync(0, 500);
    if (mhr == WAIT_TIMEOUT || FAILED(mhr)) {
        setErrorHr(h, "slot still owned by its consumer (protocol violation?)", mhr);
        frame->Release();
        h->dupl->ReleaseFrame();
        return CG_ERR_COPY;
    }

    h->ctx->CopyResource(h->slots[slot].tex, frame);
    frame->Release();
    h->width = w;
    h->height = bh;
    CgAcquireOut compat = {};
    compat.moves = out->moves;
    compat.dirty = out->dirty;
    fetchMoves(h, &compat);
    fetchDirties(h, &compat);
    out->moveCount = compat.moveCount;
    out->dirtyCount = compat.dirtyCount;
    out->ptrVisible = info.PointerPosition.Visible ? 1 : 0;
    out->ptrX = info.PointerPosition.Position.x - h->originX;
    out->ptrY = info.PointerPosition.Position.y - h->originY;

    h->ctx4->Signal(h->fence, ++h->fenceValue);
    h->slots[slot].km->ReleaseSync(0);

    LARGE_INTEGER qpc;
    QueryPerformanceCounter(&qpc);
    h->slots[slot].busy = true;
    out->slot = slot;
    out->sharedHandle = (uint64_t)(uintptr_t)h->slots[slot].shared;
    out->fenceValue = h->fenceValue;
    out->width = w;
    out->height = bh;
    out->qpc = (uint64_t)qpc.QuadPart;

    h->dupl->ReleaseFrame();
    return CG_OK;
}

int32_t cg_dxgi_release_gpu(CgHandle h, int32_t slot) {
    if (!h || slot < 0 || slot >= h->slotCount || !h->slots) {
        return CG_ERR_INVALID_ARG;
    }
    h->slots[slot].busy = false;
    return CG_OK;
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
    free(h->ptrShape);
    freeGpu(h);
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

JNIEXPORT jint JNICALL JNI_FN(nOutputCount)(JNIEnv *env CG_UNUSED, jclass CG_UNUSED cls,
                                            jint adapter) {
    int32_t n = 0;
    if (cg_dxgi_output_count((int)adapter, &n) != CG_OK) {
        return 0;
    }
    return (jint)n;
}

JNIEXPORT jint JNICALL JNI_FN(nOutputDesc)(JNIEnv *env, jclass CG_UNUSED cls, jint adapter,
                                           jint output, jintArray out) {
    if (!out || env->GetArrayLength(out) < 5) {
        return CG_ERR_INVALID_ARG;
    }
    CgOutputDesc d = {};
    int32_t rc = cg_dxgi_output_desc((int)adapter, (int)output, &d);
    if (rc == CG_OK) {
        jint v[5] = {d.x, d.y, d.width, d.height, d.hdr};
        env->SetIntArrayRegion(out, 0, 5, v);
    }
    return rc;
}

JNIEXPORT jint JNICALL JNI_FN(nAcquire)(JNIEnv *env, jclass CG_UNUSED cls, jlong handle,
                                        jobject dst, jint stride, jint timeoutMs, jlongArray qpc,
                                        jintArray meta, jobject moves, jobject dirties, jintArray ptr,
                                        jobject shape) {
    CgHandle h = (CgHandle)(intptr_t)handle;
    if (!h || !dst || stride <= 0) {
        return CG_ERR_INVALID_ARG;
    }
    void *pixels = env->GetDirectBufferAddress(dst);
    jlong cap = env->GetDirectBufferCapacity(dst);
    if (!pixels || cap <= 0 || (int64_t)stride > cap) {
        return CG_ERR_INVALID_ARG;
    }

    CgMove *moveBuf = nullptr;
    if (moves) {
        moveBuf = (CgMove *)env->GetDirectBufferAddress(moves);
    }
    CgRect *dirtyBuf = nullptr;
    if (dirties) {
        dirtyBuf = (CgRect *)env->GetDirectBufferAddress(dirties);
    }
    void *shapeBuf = nullptr;
    jlong shapeCap = 0;
    if (shape) {
        shapeBuf = env->GetDirectBufferAddress(shape);
        shapeCap = env->GetDirectBufferCapacity(shape);
    }

    CgAcquireOut o = {};
    o.moves = moveBuf;
    o.dirty = dirtyBuf;
    o.ptrShape = shapeBuf;
    o.ptrShapeCap = (int32_t)(shapeCap > 0x7fffffff ? 0x7fffffff : shapeCap);

    int32_t rc = cg_dxgi_acquire_full(h, pixels, (int32_t)stride, (int32_t)cap, (int)timeoutMs, &o);
    if (rc != CG_OK) {
        return rc;
    }
    if (qpc) {
        jlong v = (jlong)o.qpc;
        env->SetLongArrayRegion(qpc, 0, 1, &v);
    }
    if (meta) {
        jint v[4] = {o.width, o.height, o.moveCount, o.dirtyCount};
        env->SetIntArrayRegion(meta, 0, 4, v);
    }
    if (ptr) {
        jint v[9] = {o.ptrVisible, o.ptrX, o.ptrY, o.ptrW, o.ptrH,
                     o.ptrHotX, o.ptrHotY, o.ptrType, o.ptrShapeSize};
        env->SetIntArrayRegion(ptr, 0, 9, v);
    }
    return rc;
}

JNIEXPORT jlong JNICALL JNI_FN(nQpcFrequency)(JNIEnv *env CG_UNUSED, jclass CG_UNUSED cls) {
    return (jlong)cg_qpc_frequency();
}

JNIEXPORT jint JNICALL JNI_FN(nGpuInit)(JNIEnv *env CG_UNUSED, jclass CG_UNUSED cls,
                                        jlong handle, jint slots) {
    CgHandle h = (CgHandle)(intptr_t)handle;
    if (!h) {
        return CG_ERR_INVALID_ARG;
    }
    return cg_dxgi_gpu_init(h, (int32_t)slots);
}

// gpuMeta (long[7]): {slot, sharedHandle, fenceHandle, fenceValue, width, height, qpc}
// meta (int[4]): {width, height, moveCount, dirtyCount}
// ptr (int[3]): {visible, x, y}
JNIEXPORT jint JNICALL JNI_FN(nAcquireGpu)(JNIEnv *env, jclass CG_UNUSED cls, jlong handle,
                                           jint timeoutMs, jlongArray gpuMeta, jintArray meta,
                                           jobject moves, jobject dirties, jintArray ptr) {
    CgHandle h = (CgHandle)(intptr_t)handle;
    if (!h) {
        return CG_ERR_INVALID_ARG;
    }
    CgMove *moveBuf = moves ? (CgMove *)env->GetDirectBufferAddress(moves) : nullptr;
    CgRect *dirtyBuf = dirties ? (CgRect *)env->GetDirectBufferAddress(dirties) : nullptr;

    CgGpuOut o = {};
    o.moves = moveBuf;
    o.dirty = dirtyBuf;
    int32_t rc = cg_dxgi_acquire_gpu(h, (int)timeoutMs, &o);
    if (rc != CG_OK) {
        return rc;
    }
    if (gpuMeta) {
        jlong v[7] = {(jlong)o.slot, (jlong)o.sharedHandle, (jlong)o.fenceHandle,
                      (jlong)o.fenceValue, (jlong)o.width, (jlong)o.height, (jlong)o.qpc};
        env->SetLongArrayRegion(gpuMeta, 0, 7, v);
    }
    if (meta) {
        jint v[4] = {o.width, o.height, o.moveCount, o.dirtyCount};
        env->SetIntArrayRegion(meta, 0, 4, v);
    }
    if (ptr) {
        jint v[3] = {o.ptrVisible, o.ptrX, o.ptrY};
        env->SetIntArrayRegion(ptr, 0, 3, v);
    }
    return rc;
}

JNIEXPORT jint JNICALL JNI_FN(nReleaseGpu)(JNIEnv *env CG_UNUSED, jclass CG_UNUSED cls,
                                           jlong handle, jint slot) {
    CgHandle h = (CgHandle)(intptr_t)handle;
    if (!h) {
        return CG_ERR_INVALID_ARG;
    }
    return cg_dxgi_release_gpu(h, (int32_t)slot);
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
