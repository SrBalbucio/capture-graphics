package balbucio.capturegraphics.bench;

import balbucio.capturegraphics.api.CaptureConfig;
import balbucio.capturegraphics.api.CaptureSession;
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

import java.util.concurrent.TimeUnit;

/**
 * Baseline: Robot acquire+close throughput. The DXGI backend must beat this
 * in fps and p99 while allocating ~0 bytes/frame in steady state.
 *
 * <p>Run: {@code mvn -pl capture-bench -am verify && java -jar ...} (exec wired in Phase 1).
 * For now: {@code mvn test} compiles it; run via IDE or {@code mvn exec:java} if needed.
 */
@BenchmarkMode(Mode.Throughput)
@Warmup(iterations = 2, time = 2)
@Measurement(iterations = 3, time = 3)
@Fork(1)
@State(Scope.Benchmark)
public class AcquireBench {
    private CaptureSession session;

    @Setup(Level.Trial)
    public void open() throws Exception {
        session = new RobotBackend().open(CaptureConfig.bgra());
    }

    @TearDown(Level.Trial)
    public void close() {
        session.close();
    }

    @Benchmark
    public int acquireClose() throws Exception {
        try (var f = session.acquire()) {
            return f == null ? 0 : f.data().remaining();
        }
    }
}
