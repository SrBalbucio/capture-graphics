module balbucio.capturegraphics.dxgi {
    requires balbucio.capturegraphics.api;
    requires balbucio.capturegraphics.core;
    requires java.desktop;
    provides balbucio.capturegraphics.api.CaptureBackend
        with balbucio.capturegraphics.dxgi.DxgiBackend;
}
