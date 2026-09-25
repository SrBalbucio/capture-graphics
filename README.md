# capture-graphics

Java desktop frame acquisition API with interchangeable native backends and a
minimum-copy design. A `Robot`-style capture without the classic `Robot` problems:
no per-frame `BufferedImage` allocation, no GDI `BitBlt` bottleneck, no color
conversions in the hot path, pooled direct buffers, monotonic presentation
timestamps, dirty-region metadata and explicit backpressure for recorders and
streaming protocols.

## Requirements

- Windows 10/11 x64, JDK 21+
- GPU zero-copy additionally needs Windows 10 1703+ (ID3D11Fence)
- No VC++ Redistributable: `capture_dxgi.dll` links the CRT statically
- Linux/macOS backends: not yet (see Roadmap)

## Modules

| Module | Contents |
|---|---|
| `capture-api` | `Frame`, `FrameMeta`, `CaptureSession`, `CaptureBackend` SPI, `GpuFrame`, `GpuCaptureSession`, config, capabilities |
| `capture-core` | `BackendRegistry`, pooled direct `FramePool`, lock-free metrics |
| `capture-backend-robot` | Pure-JDK fallback + performance baseline (`robot`) |
| `capture-backend-win-dxgi` | DXGI Desktop Duplication: CPU readback + GPU zero-copy (`win-dxgi`, preferred) |
| `capture-compat-awt` | Optional `BufferedImage`/Swing adapters (copy cost, keep off the hot path) |
| `capture-bench` | JMH benchmarks |
| `capture-native` | C ABI + DXGI bridge + GPU shared-texture pool (MSVC, CMakeLists for CI) |
| `capture-all` | Multiplatform aggregator: one dependency, runtime backend pick + fallback |

## Quickstart

For applications, depend on the aggregator only:

```xml
<dependency>
  <groupId>balbucio.capturegraphics</groupId>
  <artifactId>capture-all</artifactId>
  <version>1.0</version>
</dependency>
```

```java
// Pull model (recorders): mirrors DXGI, caller owns each frame.
try (CaptureSession s = Capture.openDefault(CaptureConfig.bgra())) {
    try (Frame f = s.acquire()) {           // null on timeout
        ByteBuffer pixels = f.data();       // direct, rowStride() bytes/row
        long pts = f.meta().ptsNanos();     // nanoTime domain
        var dirty = f.meta().dirty();       // changed regions for delta encode
    }
}

// Push model (streaming): one listener thread per session.
session.onFrame(frame -> { try (frame) { send(frame); } }, Backpressure.DROP_OLDEST);

// GPU zero-copy: pixels stay on the GPU as a shared NT handle + fence.
try (GpuCaptureSession s = new DxgiBackend().openGpu(CaptureConfig.bgra())) {
    try (GpuFrame f = s.acquireGpu()) {
        long handle = f.nativeHandle();     // open via OpenSharedResource1
        long fenceValue = f.fenceValue();   // wait on fenceHandle first
    }
}
```

See `GpuFrame` javadoc for the full consumer protocol (D3D12 fence open,
keyed mutex key 0, `ReleaseSync(0)` before `close()`).

## Performance (measured)

Machine: RTX 3050, 3440x1440 BGRA, Windows 11, JDK 27.

| Path | Throughput (JMH, 1 fork) | Manual loop |
|---|---|---|
| Robot | 10.5 ops/s | ~10 fps, ~102 ms/frame |
| DXGI CPU (1 copy) | 101.4 ops/s | ~115 fps, ~8.7 ms/frame |
| DXGI GPU (0 copies) | — | ~329 fps, ~3.0 ms/frame |

JMH: run `SequentialBench` in `capture-bench`
(`-wi 1 -i 2 -f 1 -r 3 -w 3 -jvmArgs "--enable-native-access=ALL-UNNAMED"`).

## Building

```sh
mvn test                                   # Java (all modules)
capture-native\build-win-x64.cmd           # rebuilds + stages capture_dxgi.dll
```

The DLL is bundled under
`capture-backend-win-dxgi/src/main/resources/natives/win-x64/` and extracted to
a temp dir at runtime (`-Djava.library.path=capture-native/build` also works).
Modular runs on JDK 23+ need `--enable-native-access` (surefire already sets it
for tests).

Logging goes through `System.Logger` (JUL by default, no dependencies). To route
it into SLF4J/Logback, add `jul-to-slf4j` and install its bridge handler.

## Roadmap

- Consumer bridges (Vulkan/OpenGL/NVENC) importing `GpuFrame` handles — owned by
  the future renderer project, this repo is the contract side
- Linux (PipeWire) / macOS (ScreenCaptureKit) backends via the same SPI
- Encode samples (kept out of the library core by design)

## License

MIT — see [LICENSE](LICENSE).
