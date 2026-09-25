// capture_abi.h — Stable C ABI for the DXGI Desktop Duplication backend (Phase 2).
//
// Design: COM/D3D11 details never cross this boundary. Java owns pooled direct
// buffers; native code copies staging -> Java buffer row by row (1 copy total,
// GPU-side CopyResource + single memcpy, no format conversion, BGRA throughout).
//
// Threading: a CgHandle is NOT thread-safe. The Java session serializes all calls.
// Status codes: 0 = ok, 1 = timeout (no new frame), <0 = fatal, reopen required.
//
// NOTE: pre-1.0 the acquire entry point was extended in place (rects + pointer
// outputs). Consumers must match this header, not the Phase-1 one.
#pragma once

#include <stdint.h>

#ifdef _WIN32
#ifdef CAPTURE_DXGI_BUILD
#define CG_API __declspec(dllexport)
#else
#define CG_API __declspec(dllimport)
#endif
#else
#define CG_API
#endif

#ifdef __cplusplus
extern "C" {
#endif

typedef struct CgContext CgContext;
typedef CgContext *CgHandle;

typedef struct {
    int32_t width;  // framebuffer width in pixels
    int32_t height; // framebuffer height in pixels
    int32_t stride; // bytes per row in the Java-side buffer (width * 4)
} CgInfo;

// Output enumeration (no open handle required).
typedef struct {
    int32_t x, y;          // origin in desktop coordinates
    int32_t width, height; // size in pixels
    int32_t hdr;           // 0 = SDR, 1 = HDR colorspace reported
} CgOutputDesc;

// Dirty region, output-relative (0,0 = top-left of this output).
typedef struct {
    int32_t x, y, width, height;
} CgRect;

// Move region: pixels copied from src to dst by the compositor.
typedef struct {
    int32_t srcX, srcY, dstX, dstY, width, height;
} CgMove;

// Fixed caps: DXGI rarely reports more; counts are capped, never overflowed.
enum {
    CG_MAX_MOVES = 128,
    CG_MAX_DIRTY = 128,
    CG_PTR_SHAPE_CAP = 262144 // 256 KiB: enough for any 256x256x32bpp cursor
};

// Pointer shape types (subset of DXGI_OUTDUPL_POINTER_SHAPE_TYPE).
enum {
    CG_PTR_NONE = 0,
    CG_PTR_MONO = 1,   // 1bpp AND mask over 1bpp XOR mask, height = h/2 each
    CG_PTR_COLOR = 2,  // 32-bit ARGB, straight alpha
    CG_PTR_MASKED = 3  // 32-bit color + 1bpp mask (legacy; Java falls back to color)
};

// Output of cg_dxgi_acquire_full. All buffers are caller-owned; native code only
// writes up to the given caps and reports (possibly capped) counts.
typedef struct {
    uint64_t qpc; // QPC timestamp of the acquired frame
    int32_t width, height;
    int32_t moveCount; // valid entries in moves (capped at CG_MAX_MOVES)
    CgMove *moves;
    int32_t dirtyCount; // valid entries in dirty (capped at CG_MAX_DIRTY)
    CgRect *dirty;
    // Pointer state for this frame (output-relative position, hotspot-excluded).
    int32_t ptrVisible; // 0/1: a shape is currently cached
    int32_t ptrX, ptrY; // cursor position, output-relative
    int32_t ptrW, ptrH, ptrHotX, ptrHotY, ptrType;
    void *ptrShape;         // caller buffer, receives cached shape when visible
    int32_t ptrShapeCap;    // capacity of ptrShape in bytes
    int32_t ptrShapeSize;   // bytes written (0 when not visible)
} CgAcquireOut;

enum {
    CG_OK = 0,
    CG_TIMEOUT = 1,
    CG_ERR_OPEN = -1,
    CG_ERR_ACCESS_LOST = -2, // need reopen (mode change, session switch, DRM)
    CG_ERR_DEVICE_REMOVED = -3,
    CG_ERR_INVALID_ARG = -4,
    CG_ERR_COPY = -5,
    CG_ERR_NO_SLOT = -6 // GPU pool exhausted: close frames promptly, fail-fast by design
};

// Output of cg_dxgi_acquire_gpu. Rect buffers are caller-owned (same contract as
// cg_dxgi_acquire_full); the shared texture + fence handles are session-owned and
// valid only until cg_dxgi_release_gpu (slot) or cg_dxgi_close (everything).
typedef struct {
    int32_t slot;          // pool slot index (pass to cg_dxgi_release_gpu)
    uint64_t sharedHandle; // NT shared handle value (DXGI_SHARED_RESOURCE_READ)
    uint64_t fenceHandle;  // shared fence HANDLE value (constant per session)
    uint64_t fenceValue;   // fence value signaled for this frame (monotonic)
    int32_t width, height;
    uint64_t qpc;
    int32_t moveCount; // valid entries in moves (capped at CG_MAX_MOVES)
    CgMove *moves;
    int32_t dirtyCount; // valid entries in dirty (capped at CG_MAX_DIRTY)
    CgRect *dirty;
    // Pointer position only (output-relative, hotspot-excluded). Shape bytes are
    // intentionally NOT delivered here: with no CPU buffer there is nothing to
    // composite onto — the consumer draws the cursor itself if wanted.
    int32_t ptrVisible, ptrX, ptrY;
} CgGpuOut;

CG_API int32_t cg_dxgi_open(int adapter, int output, CgHandle *out);
CG_API int32_t cg_dxgi_info(CgHandle h, CgInfo *out);
CG_API int32_t cg_dxgi_output_count(int adapter, int32_t *out);
CG_API int32_t cg_dxgi_output_desc(int adapter, int output, CgOutputDesc *out);

// Full acquisition: frame copy + dirty/move rects + pointer state, in one call.
// Rect/shape buffers may be NULL (with count 0) to skip those outputs.
CG_API int32_t cg_dxgi_acquire_full(CgHandle h, void *dst, int32_t dstStride, int32_t dstCap,
                                    int timeoutMs, CgAcquireOut *out);
// Initializes the GPU shared-texture pool (idempotent; grows never, fixed count).
// Requires ID3D11Fence support (Windows 10 1703+); else CG_ERR_OPEN.
CG_API int32_t cg_dxgi_gpu_init(CgHandle h, int32_t slots);
// Zero-copy acquisition: desktop -> owned shared texture, fence signaled.
// Fails fast with CG_ERR_NO_SLOT when every slot is still checked out.
//
// Consumer protocol (mandatory, in order):
//   1. Open sharedHandle (OpenSharedResource1) and fenceHandle (D3D12 open).
//   2. Wait fenceValue on the fence (producer signals AFTER its copy).
//   3. QI IDXGIKeyedMutex on the opened texture, AcquireSync(0, timeout).
//      (SHARED_KEYEDMUTEX textures read as zeros without this. Single key 0
//      on both sides; the pool's busy flags order the turns. NOTE: contention
//      surfaces as WAIT_TIMEOUT, which is a *success* HRESULT — compare it
//      explicitly, FAILED() never catches it.)
//   4. Use/copy, then ReleaseSync(0) to hand the slot back to the producer.
// A consumer that never releases wedges only its own slot, never the session.
CG_API int32_t cg_dxgi_acquire_gpu(CgHandle h, int timeoutMs, CgGpuOut *out);
CG_API int32_t cg_dxgi_release_gpu(CgHandle h, int32_t slot);
CG_API int64_t cg_qpc_frequency(void);
CG_API const char *cg_dxgi_last_error(CgHandle h);
CG_API void cg_dxgi_close(CgHandle h);

#ifdef __cplusplus
}
#endif
