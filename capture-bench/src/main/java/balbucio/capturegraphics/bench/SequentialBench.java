package balbucio.capturegraphics.bench;

import balbucio.capturegraphics.api.CaptureConfig;
import balbucio.capturegraphics.api.CaptureSession;
import balbucio.capturegraphics.dxgi.DxgiBackend;
import balbucio.capturegraphics.robot.RobotBackend;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Sequential throughput: Robot vs DXGI acquire+close.
 * DXGI runs only on Windows x64 (assumption failure aborts its benchmark).
 */
@BenchmarkMode(Mode.Throughput)
@Warmup(iterations = 2, time = 2)
@Measurement(iterations = 3, time = 3)
@Fork(1)
public class SequentialBench {

    @State(Scope.Thread)
    public static class RobotState {
        CaptureSession session;

        @Setup(Level.Trial)
        public void open() throws Exception {
            session = new RobotBackend().open(CaptureConfig.bgra());
        }

        @TearDown(Level.Trial)
        public void close() {
            session.close();
        }
    }

    @State(Scope.Thread)
    public static class DxgiState {
        CaptureSession session;

        @Setup(Level.Trial)
        public void open() throws Exception {
            session = new DxgiBackend().open(CaptureConfig.bgra());
        }

        @TearDown(Level.Trial)
        public void close() {
            session.close();
        }
    }

    @Benchmark
    public int robotAcquireClose(RobotState s) throws Exception {
        try (var f = s.session.acquire()) {
            return f == null ? 0 : f.data().remaining();
        }
    }

    @Benchmark
    public int dxgiAcquireClose(DxgiState s) throws Exception {
        try (var f = s.session.acquire()) {
            return f == null ? 0 : f.data().remaining();
        }
    }
}
