package balbucio.capturegraphics.awt;

import balbucio.capturegraphics.api.Backpressure;
import balbucio.capturegraphics.api.Capture;
import balbucio.capturegraphics.api.CaptureConfig;
import balbucio.capturegraphics.api.CaptureException;
import balbucio.capturegraphics.api.CaptureSession;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.image.BufferedImage;

/**
 * Drop-in helpers mimicking {@code Robot.createScreenCapture} and a throttled Swing preview.
 */
public final class RobotCapture {
    private RobotCapture() {
    }

    /** One-shot capture returning a BufferedImage. Simple, with copy cost. */
    public static BufferedImage take() throws CaptureException {
        try (CaptureSession s = Capture.openDefault(CaptureConfig.bgra());
             var f = s.acquire()) {
            if (f == null) {
                throw new CaptureException(CaptureException.Reason.TIMEOUT, "No frame within timeout");
            }
            return AwtFrames.toBufferedImage(f);
        }
    }

    /** Throttled preview panel: converts at most maxFps frames/s, downscaled. */
    public static JComponent previewPanel(CaptureSession session, int maxFps, double scale) {
        return new PreviewPanel(session, maxFps, scale);
    }

    private static final class PreviewPanel extends JPanel {
        private final CaptureSession session;
        private final long minIntervalNanos;
        private final double scale;
        private volatile BufferedImage current;

        PreviewPanel(CaptureSession session, int maxFps, double scale) {
            this.session = session;
            this.minIntervalNanos = 1_000_000_000L / Math.max(1, maxFps);
            this.scale = scale;
            setPreferredSize(new Dimension(
                    (int) (session.display().width() * scale), (int) (session.display().height() * scale)));
            session.onFrame(frame -> {
                long now = System.nanoTime();
                try (frame) {
                    current = AwtFrames.toBufferedImage(frame);
                } catch (Exception ignored) {
                }
                SwingUtilities.invokeLater(this::repaint);
                try {
                    Thread.sleep(Math.max(0, minIntervalNanos / 1_000_000));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, Backpressure.DROP_NEWEST);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            BufferedImage img = current;
            if (img != null) {
                g.drawImage(img, 0, 0, getWidth(), getHeight(), null);
            }
        }
    }
}
