package balbucio.capturegraphics.core;

import balbucio.capturegraphics.api.CaptureBackend;
import balbucio.capturegraphics.api.CaptureConfig;
import balbucio.capturegraphics.api.CaptureException;
import balbucio.capturegraphics.api.CaptureSession;

import java.util.List;
import java.util.ServiceLoader;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Backend registry used by applications that want explicit backend selection
 * instead of {@code Capture.openDefault()}.
 */
public final class BackendRegistry {
    private final List<CaptureBackend> backends = new CopyOnWriteArrayList<>();

    public static BackendRegistry loadAll() {
        BackendRegistry r = new BackendRegistry();
        for (CaptureBackend b : ServiceLoader.load(CaptureBackend.class)) {
            r.register(b);
        }
        return r;
    }

    public void register(CaptureBackend backend) {
        backends.add(backend);
    }

    public List<CaptureBackend> all() {
        return List.copyOf(backends);
    }

    public CaptureBackend byId(String id) {
        return backends.stream().filter(b -> b.id().equals(id)).findFirst().orElse(null);
    }

    public CaptureSession open(String backendId, CaptureConfig config) throws CaptureException {
        CaptureBackend b = byId(backendId);
        if (b == null) {
            throw new CaptureException(CaptureException.Reason.NATIVE_ERROR, "Unknown backend: " + backendId);
        }
        return b.open(config);
    }
}
