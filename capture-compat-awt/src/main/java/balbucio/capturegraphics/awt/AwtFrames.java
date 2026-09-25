package balbucio.capturegraphics.awt;

import balbucio.capturegraphics.api.Frame;

import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.nio.ByteOrder;

/**
 * Optional AWT/Swing adapters. Every method here performs one bulk copy into a
 * {@code TYPE_INT_ARGB} image: BGRA bytes read as little-endian ints are already
 * {@code 0xAARRGGBB}, so no per-pixel swizzle is needed. Still slower than consuming
 * {@link Frame#data()} directly (one full copy + image allocation); keep off the hot
 * path and prefer {@link #copyTo(Frame, BufferedImage)} with a reusable target.
 */
public final class AwtFrames {
    private AwtFrames() {
    }

    /** Compatible reusable target for {@link #copyTo}. */
    public static BufferedImage newCompatibleImage(int w, int h) {
        return new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
    }

    /** Full copy into a fresh image. Allocates per call; avoid in loops. */
    public static BufferedImage toBufferedImage(Frame frame) {
        BufferedImage img = newCompatibleImage(frame.width(), frame.height());
        copyTo(frame, img);
        return img;
    }

    /**
     * Copies a BGRA frame into a {@code TYPE_INT_ARGB} image, reusing the target.
     * Caller must ensure dimensions match. Single bulk transfer, no swizzle.
     */
    public static void copyTo(Frame frame, BufferedImage target) {
        if (target.getType() != BufferedImage.TYPE_INT_ARGB) {
            throw new IllegalArgumentException(
                    "target must be TYPE_INT_ARGB (see newCompatibleImage)");
        }
        if (target.getWidth() != frame.width() || target.getHeight() != frame.height()) {
            throw new IllegalArgumentException("target dimensions must match the frame");
        }
        int[] bank = ((DataBufferInt) target.getRaster().getDataBuffer()).getData();
        frame.data().duplicate().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
                .get(bank, 0, bank.length);
    }

    /** Draw helper for Swing previews (call on EDT). */
    public static void paint(Frame frame, Graphics2D g, Rectangle dest) {
        BufferedImage img = toBufferedImage(frame);
        g.drawImage(img, dest.x, dest.y, dest.width, dest.height, null);
    }
}
