package balbucio.capturegraphics.api;

import java.nio.ByteBuffer;

/**
 * A single captured frame. Hot-path object: pooled and reused.
 *
 * <p>The pixel buffer is a read-only direct {@link ByteBuffer} in {@link #format()} order
 * with {@link #rowStride()} bytes per row (may exceed {@code width * bytesPerPixel}).
 * Never retain the buffer after {@link #close()}: it is returned to the pool.
 */
public interface Frame extends AutoCloseable {
    ByteBuffer data();

    int width();

    int height();

    int rowStride();

    FrameFormat format();

    FrameMeta meta();

    /** Returns the buffer to the pool. Must be idempotent. */
    @Override
    void close();
}
