package balbucio.capturegraphics.awt;

import balbucio.capturegraphics.api.DisplayId;
import balbucio.capturegraphics.api.FrameFormat;
import balbucio.capturegraphics.api.FrameMeta;
import balbucio.capturegraphics.core.FramePool;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AwtFramesTest {
    @Test
    void copyToReusableTarget() {
        FramePool pool = new FramePool(4, 2, FrameFormat.BGRA_8, 1);
        try (var f = pool.take()) {
            var buf = f.writable();
            while (buf.hasRemaining()) {
                buf.put((byte) 0x11); // B
                buf.put((byte) 0x22); // G
                buf.put((byte) 0x33); // R
                buf.put((byte) 0xFF); // A
            }
            buf.flip();
            f.withMeta(new FrameMeta(0, System.nanoTime(), 0,
                    DisplayId.primary(4, 2), 0, List.of(), true));
            var img = AwtFrames.newCompatibleImage(4, 2);
            AwtFrames.copyTo(f, img); // must not throw; reusable target keeps zero alloc/frame
            assertEquals(4, img.getWidth());
            // R=0x33 G=0x22 B=0x11 A=0xFF
            assertEquals(0xFF332211, img.getRGB(0, 0));
        }
    }
}
