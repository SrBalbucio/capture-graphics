package balbucio.capturegraphics.api;

/** Live counters for recorders/streaming adaptation (bitrate, drop warnings). */
public record SessionMetrics(double fps, long delivered, long dropped, double avgAcquireMs) {
}
