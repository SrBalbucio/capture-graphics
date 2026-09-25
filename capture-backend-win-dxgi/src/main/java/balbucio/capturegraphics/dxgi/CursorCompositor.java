package balbucio.capturegraphics.dxgi;

import java.nio.ByteBuffer;

/**
 * Composites a DXGI pointer shape onto a BGRA frame buffer (output-relative coords).
 *
 * <p>Only touches the cursor rectangle (at most 256x256), so the cost is negligible
 * next to the full-frame copy. Uses absolute buffer access: safe regardless of position.
 */
final class CursorCompositor {
    private CursorCompositor() {
    }

    /**
     * @param frame  BGRA frame buffer (position/limit ignored, absolute access)
     * @param ox     horizontal offset of the frame origin in output coordinates
     *               (region capture; 0 for full-frame)
     * @param oy     vertical offset of the frame origin in output coordinates
     * @param ptr    {@code DxgiNative} ptr[] layout (9 ints, output-relative position)
     * @param shape  cached shape bytes, little-endian (must be a {@code LITTLE_ENDIAN} buffer,
     *               matching the native x86 writer; multi-byte reads depend on it)
     */
    static void composite(ByteBuffer frame, int frameW, int frameH, int stride,
                          int ox, int oy, int[] ptr, ByteBuffer shape) {
        if (ptr[DxgiNative.PTR_VISIBLE] == 0) {
            return;
        }
        int w = ptr[DxgiNative.PTR_W];
        int h = ptr[DxgiNative.PTR_H];
        if (w <= 0 || h <= 0 || w > 512 || h > 512) {
            return;
        }
        int left = ptr[DxgiNative.PTR_X] - ptr[DxgiNative.PTR_HOT_X] - ox;
        int top = ptr[DxgiNative.PTR_Y] - ptr[DxgiNative.PTR_HOT_Y] - oy;
        if (left >= frameW || top >= frameH || left + w <= 0 || top + h <= 0) {
            return; // fully off-output
        }
        int type = ptr[DxgiNative.PTR_TYPE];
        if (type == DxgiNative.PTR_COLOR) {
            compositeColor(frame, frameW, frameH, stride, shape, left, top, w, h);
        } else if (type == DxgiNative.PTR_MONO) {
            compositeMono(frame, frameW, frameH, stride, shape, left, top, w, h);
        } else if (type == DxgiNative.PTR_MASKED) {
            compositeMasked(frame, frameW, frameH, stride, shape, left, top, w, h);
        }
    }

    private static void compositeColor(ByteBuffer frame, int frameW, int frameH, int stride,
                                       ByteBuffer shape, int left, int top, int w, int h) {
        for (int y = 0; y < h; y++) {
            int fy = top + y;
            if (fy < 0 || fy >= frameH) {
                continue;
            }
            for (int x = 0; x < w; x++) {
                int fx = left + x;
                if (fx < 0 || fx >= frameW) {
                    continue;
                }
                int s = shape.getInt((y * w + x) * 4); // ARGB little-endian
                int a = (s >>> 24);
                if (a == 0) {
                    continue;
                }
                int di = fy * stride + fx * 4;
                if (a == 255) {
                    frame.put(di, (byte) (s));
                    frame.put(di + 1, (byte) (s >> 8));
                    frame.put(di + 2, (byte) (s >> 16));
                    frame.put(di + 3, (byte) 255);
                    continue;
                }
                // src-over blend in BGRA order
                int inv = 255 - a;
                int db = frame.get(di) & 0xFF;
                int dg = frame.get(di + 1) & 0xFF;
                int dr = frame.get(di + 2) & 0xFF;
                frame.put(di, (byte) ((db * inv + (s & 0xFF) * a + 127) / 255));
                frame.put(di + 1, (byte) ((dg * inv + ((s >> 8) & 0xFF) * a + 127) / 255));
                frame.put(di + 2, (byte) ((dr * inv + ((s >> 16) & 0xFF) * a + 127) / 255));
                frame.put(di + 3, (byte) 255);
            }
        }
    }

    private static void compositeMono(ByteBuffer frame, int frameW, int frameH, int stride,
                                      ByteBuffer shape, int left, int top, int w, int h) {
        int pitch = ((w + 31) / 32) * 4;
        for (int y = 0; y < h; y++) {
            int fy = top + y;
            if (fy < 0 || fy >= frameH) {
                continue;
            }
            for (int x = 0; x < w; x++) {
                int fx = left + x;
                if (fx < 0 || fx >= frameW) {
                    continue;
                }
                int andBit = (shape.get(y * pitch + x / 8) >> (7 - (x % 8))) & 1;
                int xorBit = (shape.get((y + h) * pitch + x / 8) >> (7 - (x % 8))) & 1;
                int di = fy * stride + fx * 4;
                if (andBit == 1 && xorBit == 0) {
                    continue; // keep destination
                } else if (andBit == 0 && xorBit == 0) {
                    putBgra(frame, di, 0, 0, 0); // black
                } else if (andBit == 0) {
                    putBgra(frame, di, 255, 255, 255); // white
                } else {
                    frame.put(di, (byte) (~frame.get(di)));
                    frame.put(di + 1, (byte) (~frame.get(di + 1)));
                    frame.put(di + 2, (byte) (~frame.get(di + 2)));
                    frame.put(di + 3, (byte) 255);
                }
            }
        }
    }

    private static void putBgra(ByteBuffer frame, int di, int b, int g, int r) {
        frame.put(di, (byte) b);
        frame.put(di + 1, (byte) g);
        frame.put(di + 2, (byte) r);
        frame.put(di + 3, (byte) 255);
    }

    /**
     * Legacy masked-color fallback: 32-bit color image followed by a 1bpp mask;
     * masked pixels invert the destination RGB. Slightly approximate for exotic
     * legacy cursors; modern alpha cursors arrive as {@code COLOR} instead.
     */
    private static void compositeMasked(ByteBuffer frame, int frameW, int frameH, int stride,
                                        ByteBuffer shape, int left, int top, int w, int h) {
        int maskPitch = ((w + 31) / 32) * 4;
        int maskBase = w * h * 4;
        for (int y = 0; y < h; y++) {
            int fy = top + y;
            if (fy < 0 || fy >= frameH) {
                continue;
            }
            for (int x = 0; x < w; x++) {
                int fx = left + x;
                if (fx < 0 || fx >= frameW) {
                    continue;
                }
                int di = fy * stride + fx * 4;
                int maskBit = (shape.get(maskBase + y * maskPitch + x / 8) >> (7 - (x % 8))) & 1;
                if (maskBit == 1) {
                    frame.put(di, (byte) (~frame.get(di)));
                    frame.put(di + 1, (byte) (~frame.get(di + 1)));
                    frame.put(di + 2, (byte) (~frame.get(di + 2)));
                } else {
                    int s = shape.getInt((y * w + x) * 4);
                    frame.put(di, (byte) (s));
                    frame.put(di + 1, (byte) (s >> 8));
                    frame.put(di + 2, (byte) (s >> 16));
                    frame.put(di + 3, (byte) 255);
                }
            }
        }
    }
}
