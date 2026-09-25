package balbucio.capturegraphics.api;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

/** Entry point: backend discovery + open. Prefers DXGI over Robot when both present. */
public final class Capture {
    private Capture() {
    }

    public static List<CaptureBackend> backends() {
        List<CaptureBackend> out = new ArrayList<>();
        for (CaptureBackend b : ServiceLoader.load(CaptureBackend.class)) {
            out.add(b);
        }
        out.sort((a, b) -> rank(a) - rank(b));
        return out;
    }

    private static int rank(CaptureBackend b) {
        return switch (b.id()) {
            case "win-dxgi" -> 0;
            case "robot" -> 100;
            default -> 50;
        };
    }

    public static CaptureSession openDefault(CaptureConfig config) throws CaptureException {
        List<CaptureBackend> all = backends();
        if (all.isEmpty()) {
            throw new CaptureException(CaptureException.Reason.NATIVE_ERROR, "No capture backend on classpath");
        }
        CaptureException last = null;
        for (CaptureBackend b : all) {
            try {
                return b.open(config);
            } catch (CaptureException e) {
                last = e;
            }
        }
        throw last;
    }
}
