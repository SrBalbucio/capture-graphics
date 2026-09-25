package balbucio.capturegraphics.dxgi;

import java.nio.ByteBuffer;

/**
 * JNI boundary for {@code capture_dxgi.dll}. Signatures must match
 * {@code dxgi_bridge.cpp} ({@code Java_balbucio_capturegraphics_dxgi_DxgiNative_*}).
 *
 * <p>Status codes mirror {@code capture_abi.h}: 0 ok, 1 timeout, negative fatal.
 */
final class DxgiNative {
    static final int CG_OK = 0;
    static final int CG_TIMEOUT = 1;
    static final int CG_ERR_ACCESS_LOST = -2;
    static final int CG_ERR_DEVICE_REMOVED = -3;

    private DxgiNative() {
    }

    static native long nOpen(int adapter, int output);

    /** Returns {width, height, stride} or null. */
    static native int[] nInfo(long handle);

    static native int nAcquire(long handle, ByteBuffer dst, int stride, int timeoutMs, long[] qpcOut);

    static native long nQpcFrequency();

    static native String nLastError(long handle);

    static native void nClose(long handle);
}
