package balbucio.capturegraphics.dxgi;

import java.nio.ByteBuffer;

/**
 * JNI boundary for {@code capture_dxgi.dll}. Signatures must match
 * {@code dxgi_bridge.cpp} ({@code Java_balbucio_capturegraphics_dxgi_DxgiNative_*}).
 *
 * <p>Status codes mirror {@code capture_abi.h}: 0 ok, 1 timeout, negative fatal.
 * Rect buffers are direct {@link ByteBuffer}s holding little-endian int32s:
 * moves as 6-int groups (srcX,srcY,dstX,dstY,w,h), dirties as 4-int groups (x,y,w,h).
 */
final class DxgiNative {
    static final int CG_OK = 0;
    static final int CG_TIMEOUT = 1;
    static final int CG_ERR_ACCESS_LOST = -2;
    static final int CG_ERR_DEVICE_REMOVED = -3;

    static final int MAX_MOVES = 128;
    static final int MAX_DIRTY = 128;
    static final int PTR_SHAPE_CAP = 262144;

    // ptr[] layout: [visible,x,y,w,h,hotX,hotY,type,shapeSize]
    static final int PTR_VISIBLE = 0;
    static final int PTR_X = 1;
    static final int PTR_Y = 2;
    static final int PTR_W = 3;
    static final int PTR_H = 4;
    static final int PTR_HOT_X = 5;
    static final int PTR_HOT_Y = 6;
    static final int PTR_TYPE = 7;
    static final int PTR_SHAPE_SIZE = 8;

    static final int PTR_NONE = 0;
    static final int PTR_MONO = 1;
    static final int PTR_COLOR = 2;
    static final int PTR_MASKED = 3;

    private DxgiNative() {
    }

    static native long nOpen(int adapter, int output);

    /** Returns {width, height, stride} or null. */
    static native int[] nInfo(long handle);

    static native int nOutputCount(int adapter);

    /** Fills {@code out} with {x,y,width,height,hdr}; returns status. */
    static native int nOutputDesc(int adapter, int output, int[] out);

    static native int nAcquire(long handle, ByteBuffer dst, int stride, int timeoutMs,
                               long[] qpc, int[] meta, ByteBuffer moves, ByteBuffer dirties,
                               int[] ptr, ByteBuffer shape);

    static native long nQpcFrequency();

    static native String nLastError(long handle);

    static native void nClose(long handle);
}
