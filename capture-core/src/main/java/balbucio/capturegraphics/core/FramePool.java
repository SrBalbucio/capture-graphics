package balbucio.capturegraphics.core;

import balbucio.capturegraphics.api.Frame;
import balbucio.capturegraphics.api.FrameFormat;
import balbucio.capturegraphics.api.FrameMeta;

import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Pool of direct BGRA buffers shared by backends. Each {@link PooledFrame} wraps one
 * slot; {@link #close()} returns the slot without allocation.
 */
public final class FramePool {
    private final int width;
    private final int height;
    private final int rowStride;
    private final FrameFormat format;
    private final ArrayBlockingQueue<ByteBuffer> free;

    public FramePool(int width, int height, FrameFormat format, int size) {
        this.width = width;
        this.height = height;
        this.format = format;
        this.rowStride = width * format.bytesPerPixel();
        this.free = new ArrayBlockingQueue<>(size);
        for (int i = 0; i < size; i++) {
            free.add(ByteBuffer.allocateDirect(rowStride * height));
        }
    }

    public PooledFrame take() {
        ByteBuffer buf = free.poll();
        if (buf == null) {
            buf = ByteBuffer.allocateDirect(rowStride * height); // pool exhausted: overflow, still direct
        } else {
            buf.clear();
        }
        return new PooledFrame(buf, false);
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public int rowStride() {
        return rowStride;
    }

    public FrameFormat format() {
        return format;
    }

    public int freeCount() {
        return free.size();
    }

    public final class PooledFrame implements Frame {
        private ByteBuffer buf;
        private FrameMeta meta;
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final boolean overflow;

        private PooledFrame(ByteBuffer buf, boolean overflow) {
            this.buf = buf;
            this.overflow = overflow;
        }

        public PooledFrame withMeta(FrameMeta meta) {
            this.meta = meta;
            return this;
        }

        public ByteBuffer writable() {
            return buf;
        }

        @Override
        public ByteBuffer data() {
            return buf.asReadOnlyBuffer();
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
        public int rowStride() {
            return rowStride;
        }

        @Override
        public FrameFormat format() {
            return format;
        }

        @Override
        public FrameMeta meta() {
            return meta;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true) && !overflow && buf != null) {
                buf.clear();
                free.offer(buf);
                buf = null;
            }
        }
    }
}
