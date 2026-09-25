package balbucio.capturegraphics.api;

/** Session configuration. Immutable; use {@link #builder()}. */
public final class CaptureConfig {
    private final DisplayId display;
    private final FrameFormat format;
    private final int timeoutMs;
    private final int framePoolSize;
    private final long targetFps;
    private final boolean cursor;

    private CaptureConfig(Builder b) {
        this.display = b.display;
        this.format = b.format;
        this.timeoutMs = b.timeoutMs;
        this.framePoolSize = b.framePoolSize;
        this.targetFps = b.targetFps;
        this.cursor = b.cursor;
    }

    public DisplayId display() {
        return display;
    }

    public FrameFormat format() {
        return format;
    }

    public int timeoutMs() {
        return timeoutMs;
    }

    public int framePoolSize() {
        return framePoolSize;
    }

    public long targetFps() {
        return targetFps;
    }

    /** Whether to composite the cursor onto captured frames (backend-dependent, default off). */
    public boolean cursor() {
        return cursor;
    }

    /** BGRA 60fps, 16ms timeout, pool sized for ~150ms of buffering. */
    public static CaptureConfig bgra() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private DisplayId display;
        private FrameFormat format = FrameFormat.BGRA_8;
        private int timeoutMs = 16;
        private int framePoolSize = 11; // ceil(60 * 0.15) + 2
        private long targetFps = 60;
        private boolean cursor = false;

        public Builder display(DisplayId d) {
            this.display = d;
            return this;
        }

        public Builder format(FrameFormat f) {
            this.format = f;
            return this;
        }

        public Builder timeoutMs(int ms) {
            this.timeoutMs = ms;
            return this;
        }

        public Builder framePoolSize(int n) {
            this.framePoolSize = n;
            return this;
        }

        public Builder targetFps(long fps) {
            this.targetFps = fps;
            return this;
        }

        public Builder cursor(boolean enabled) {
            this.cursor = enabled;
            return this;
        }

        public CaptureConfig build() {
            return new CaptureConfig(this);
        }
    }
}
