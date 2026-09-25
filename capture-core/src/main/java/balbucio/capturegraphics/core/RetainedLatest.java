package balbucio.capturegraphics.core;

import balbucio.capturegraphics.api.Frame;
import balbucio.capturegraphics.api.FrameFormat;
import balbucio.capturegraphics.api.FrameMeta;

import java.nio.ByteBuffer;
import java.util.Optional;

/**
 * Session-owned snapshot of the last delivered frame for
 * {@code CaptureSession#latest()}. Keeps one private direct buffer and copies each
 * delivered frame into it (one bulk copy; only used when the session is configured
 * with {@code retainLast}). Views are read-only and must NOT be closed — they stay
 * valid until the next delivered frame or session close.
 */
public final class RetainedLatest {
    private ByteBuffer buf;
    private FrameMeta meta;
    private int width;
    private int height;
    private int stride;

    /** Copies the delivered frame payload; no-op when {@code enabled} is false. */
    public synchronized void offer(boolean enabled, ByteBuffer src, int w, int h, int stride,
                                   FrameMeta meta) {
        if (!enabled) {
            return;
        }
        int need = stride * h;
        if (buf == null || buf.capacity() != need) {
            buf = ByteBuffer.allocateDirect(need);
        }
        buf.clear();
        ByteBuffer dup = src.duplicate();
        dup.clear();
        buf.put(dup);
        buf.flip();
        this.meta = meta;
        this.width = w;
        this.height = h;
        this.stride = stride;
    }

    public synchronized Optional<Frame> view() {
        if (buf == null || meta == null) {
            return Optional.empty();
        }
        final ByteBuffer snapshot = buf.asReadOnlyBuffer();
        final FrameMeta m = meta;
        final int w = width;
        final int h = height;
        final int s = stride;
        return Optional.of(new Frame() {
            @Override
            public ByteBuffer data() {
                return snapshot.duplicate();
            }

            @Override
            public int width() {
                return w;
            }

            @Override
            public int height() {
                return h;
            }

            @Override
            public int rowStride() {
                return s;
            }

            @Override
            public FrameFormat format() {
                return FrameFormat.BGRA_8;
            }

            @Override
            public FrameMeta meta() {
                return m;
            }

            @Override
            public void close() {
                // Session-owned: no-op by contract, safe in try-with-resources.
            }
        });
    }
}
