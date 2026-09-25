package balbucio.capturegraphics.dxgi;

import java.io.IOException;
import java.io.InputStream;
import java.lang.System.Logger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Loads {@code capture_dxgi.dll}: first from the classpath resource
 * ({@code natives/win-x64/capture_dxgi.dll}, extracted to a temp dir), then
 * falls back to {@link System#loadLibrary}. No-op after the first success.
 */
final class NativeLibLoader {
    private static final Logger LOG = System.getLogger(NativeLibLoader.class.getName());
    private static final AtomicBoolean LOADED = new AtomicBoolean(false);

    private NativeLibLoader() {
    }

    static boolean isWindowsX64() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        return os.contains("win") && (arch.contains("64") || arch.contains("amd64"));
    }

    static synchronized void ensureLoaded() throws IllegalStateException {
        if (LOADED.get()) {
            return;
        }
        if (!isWindowsX64()) {
            throw new IllegalStateException("win-dxgi backend requires Windows x64");
        }
        // 1) bundled resource
        try (InputStream in = NativeLibLoader.class.getResourceAsStream(
                "/natives/win-x64/capture_dxgi.dll")) {
            if (in != null) {
                Path tmp = Files.createTempDirectory("capture-dxgi-");
                tmp.toFile().deleteOnExit();
                Path dll = tmp.resolve("capture_dxgi.dll");
                Files.copy(in, dll, StandardCopyOption.REPLACE_EXISTING);
                dll.toFile().deleteOnExit();
                System.load(dll.toAbsolutePath().toString());
                LOADED.set(true);
                LOG.log(Logger.Level.INFO, "Loaded bundled capture_dxgi.dll");
                return;
            }
        } catch (IOException | UnsatisfiedLinkError e) {
            LOG.log(Logger.Level.WARNING, "Bundled DLL load failed, trying loadLibrary: {0}", e);
        }
        // 2) system library path (dev loop: -Djava.library.path=capture-native/build)
        try {
            System.loadLibrary("capture_dxgi");
            LOADED.set(true);
            LOG.log(Logger.Level.INFO, "Loaded capture_dxgi via library path");
        } catch (UnsatisfiedLinkError e) {
            throw new IllegalStateException("capture_dxgi.dll not found (resource nor library path)", e);
        }
    }
}
