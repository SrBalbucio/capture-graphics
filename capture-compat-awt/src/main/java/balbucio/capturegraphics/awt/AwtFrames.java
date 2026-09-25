package balbucio.capturegraphics.awt;

import balbucio.capturegraphics.api.Frame;

import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;

/**
 * Optional AWT/Swing adapters. Every method here performs a full copy +
 * BGRA-to-ABGR swizzle: convenient, but 30-50% slower than consuming
 * {@link Frame#data()} directly. Keep off the hot path; prefer
 * {@link #copyTo(Frame, BufferedImage)} with a reusable target for previews.
 */
public final class AwtFrames {
    private AwtFrames() {
    }

    /** Compatible reusable target for {@link #copyTo}. */
    public static BufferedImage newCompatibleImage(int w, int h) {
        return new BufferedImage(w, h, BufferedImage.TYPE_4BYTE_ABGR);
    }

    /** Full copy into a fresh image. Allocates per call; avoid in loops. */
    public static BufferedImage toBufferedImage(Frame frame) {
        BufferedImage img = newCompatibleImage(frame.width(), frame.height());
        copyTo(frame, img);
        return img;
    }

    /**
     * Copies BGRA direct buffer into a {@code TYPE_4BYTE_ABGR} image, reusing the target.
     * Caller must ensure dimensions match.
     */
    public static void copyTo(Frame frame, BufferedImage target) {
        int w = frame.width();
        int h = frame.height();
        ByteBuffer src = frame.data();
        src.rewind();
        byte[] row = new byte[w * 4];
        // Frame BGRA -> ABGR image: bytes per pixel rearranged (B,G,R,A) -> (A,B,G,R).
        for (int y = 0; y < h; y++) {
            src.get(row);
            for (int x = 0; x < w; x++) {
                int i = x * 4;
                byte b = row[i], g = row[i + 1], r = row[i + 2], a = row[i + 3];
                row[i] = a;
                row[i + 1] = b;
                row[i + 2] = g;
                row[i + 3] = r;
            }
            target.getRaster().getDataBuffer();
            // Write via setRGB row to stay correct regardless of raster layout.
            int[] argb = new int[w];
            for (int x = 0; x < w; x++) {
                int i = x * 4;
                int a = row[i] & 0xFF, b = row[i + 1] & 0xFF, g = row[i + 2] & 0xFF, r = row[i + 3] & 0xFF;
                argb[x] = (a << 24) | (r << 16) | (g << 8) | b;
            }
            target.setRGB(0, y, w, 1, argb, 0, w);
        }
        src.rewind();
    }

    /** Draw helper for Swing previews (call on EDT). */
    public static void paint(Frame frame, Graphics2D g, Rectangle dest) {
        BufferedImage img = toBufferedImage(frame);
        g.drawImage(img, dest.x, dest.y, dest.width, dest.height, null);
    }
}
