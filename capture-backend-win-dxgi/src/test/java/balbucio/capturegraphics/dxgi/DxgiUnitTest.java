package balbucio.capturegraphics.dxgi;

import balbucio.capturegraphics.api.DisplayId;
import balbucio.capturegraphics.api.Rect;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Pure-Java unit tests: no native library, runs on any OS. */
class DxgiUnitTest {
    /** Mirrors production: native x86 writes are little-endian. */
    private static ByteBuffer le(int bytes) {
        return ByteBuffer.allocateDirect(bytes).order(ByteOrder.LITTLE_ENDIAN);
    }
    @Test
    void outputIndexParsing() {
        assertEquals(0, DxgiBackend.outputIndex(null));
        assertEquals(0, DxgiBackend.outputIndex(DisplayId.primary(800, 600)));
        assertEquals(2, DxgiBackend.outputIndex(
                new DisplayId("dxgi-output-2", "n", 0, 0, 800, 600, false)));
        assertEquals(0, DxgiBackend.outputIndex(
                new DisplayId("dxgi-output-x", "n", 0, 0, 800, 600, false)));
    }

    @Test
    void unionDirtyMergesAndClips() {
        // 1 move (dst 10,10 50x50) + 2 dirties (one half off-frame).
        ByteBuffer moves = le(128 * 6 * 4);
        moves.putInt(0, 0).putInt(4, 0).putInt(8, 10).putInt(12, 10).putInt(16, 50).putInt(20, 50);
        ByteBuffer dirties = le(128 * 4 * 4);
        dirties.putInt(0, 0).putInt(4, 0).putInt(8, 100).putInt(12, 100);
        dirties.putInt(16, 1900).putInt(20, 1000).putInt(24, 200).putInt(28, 200);
        int[] meta = {1920, 1080, 1, 2};
        List<Rect> rects = DxgiBackend.unionDirty(meta, moves, dirties, 1920, 1080);
        assertEquals(3, rects.size());
        assertEquals(new Rect(10, 10, 50, 50), rects.get(0));
        assertEquals(new Rect(0, 0, 100, 100), rects.get(1));
        assertEquals(new Rect(1900, 1000, 20, 80), rects.get(2)); // clipped
    }

    @Test
    void unionDirtyFallsBackToFullFrame() {
        ByteBuffer moves = le(128 * 6 * 4);
        ByteBuffer dirties = le(128 * 4 * 4);
        List<Rect> rects = DxgiBackend.unionDirty(new int[]{64, 64, 0, 0}, moves, dirties, 64, 64);
        assertEquals(List.of(Rect.full(64, 64)), rects);
    }

    @Test
    void cursorColorBlendsOpaquePixel() {
        int w = 8, h = 8, stride = w * 4;
        ByteBuffer frame = le(stride * h); // transparent black
        ByteBuffer shape = le(256);
        shape.putInt(0, 0xFFFF0000); // opaque red ARGB at cursor (0,0)
        int[] ptr = {1, 4, 4, 1, 1, 0, 0, DxgiNative.PTR_COLOR, 4};
        CursorCompositor.composite(frame, w, h, stride, ptr, shape);
        int di = 4 * stride + 4 * 4;
        assertEquals((byte) 0x00, frame.get(di));     // B
        assertEquals((byte) 0x00, frame.get(di + 1)); // G
        assertEquals((byte) 0xFF, frame.get(di + 2)); // R
        assertEquals((byte) 0xFF, frame.get(di + 3)); // A
        // Untouched pixel stays zero.
        assertEquals(0, frame.get(0));
    }

    @Test
    void cursorInvisibleIsNoop() {
        ByteBuffer frame = le(64);
        ByteBuffer shape = le(64);
        int[] ptr = {0, 0, 0, 2, 2, 0, 0, DxgiNative.PTR_COLOR, 16};
        CursorCompositor.composite(frame, 4, 4, 16, ptr, shape);
        for (int i = 0; i < 64; i++) {
            assertEquals(0, frame.get(i));
        }
    }
}
