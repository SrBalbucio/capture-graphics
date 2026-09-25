module balbucio.capturegraphics.robot {
    requires balbucio.capturegraphics.api;
    requires balbucio.capturegraphics.core;
    requires java.desktop;
    provides balbucio.capturegraphics.api.CaptureBackend
        with balbucio.capturegraphics.robot.RobotBackend;
}
