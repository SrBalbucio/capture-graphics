// capture_abi.h — Minimal stable C ABI for the DXGI Desktop Duplication backend.
//
// Design: COM/D3D11 details never cross this boundary. Java owns pooled direct
// buffers; native code copies staging -> Java buffer row by row (1 copy total,
// GPU-side CopyResource + single memcpy, no format conversion, BGRA throughout).
//
// Threading: a CgHandle is NOT thread-safe. The Java session serializes all calls.
// Status codes: 0 = ok, 1 = timeout (no new frame), <0 = fatal, reopen required.
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

enum {
    CG_OK = 0,
    CG_TIMEOUT = 1,
    CG_ERR_OPEN = -1,
    CG_ERR_ACCESS_LOST = -2, // need reopen (mode change, session switch, DRM)
    CG_ERR_DEVICE_REMOVED = -3,
    CG_ERR_INVALID_ARG = -4,
    CG_ERR_COPY = -5
};

CG_API int32_t cg_dxgi_open(int adapter, int output, CgHandle *out);
CG_API int32_t cg_dxgi_info(CgHandle h, CgInfo *out);

// Copies the next desktop frame into `dst` (capacity `dstCap` bytes, row stride
// `dstStride` bytes). On success writes the QPC timestamp to `qpcOut`.
// Returns CG_OK, CG_TIMEOUT, or a negative error.
CG_API int32_t cg_dxgi_acquire(CgHandle h, void *dst, int32_t dstStride, int32_t dstCap,
                               int timeoutMs, uint64_t *qpcOut);
CG_API int64_t cg_qpc_frequency(void);
CG_API const char *cg_dxgi_last_error(CgHandle h);
CG_API void cg_dxgi_close(CgHandle h);

#ifdef __cplusplus
}
#endif
