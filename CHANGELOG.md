# Changelog

## Unreleased (1.1.0)

- New `capture-all` aggregator module: single dependency with every bundled
  backend; runtime pick + graceful fallback via ServiceLoader.

## 1.0.0 — first release

Foundation (Phase 0): multi-module Maven build (Java 21, JPMS), stable `capture-api`
(`Frame` pooled direct buffers, sequential `FrameMeta` with pts/drops/dirty rects,
pull + push sessions with `Backpressure`), `capture-core` pool/metrics/registry,
pure-JDK `robot` fallback backend, optional AWT/Swing compat module, JMH baseline.

Windows DXGI (Phase 1): minimal JNI bridge (`capture_abi.h`, 5 functions),
D3D11 Desktop Duplication into pooled buffers, QPC presentation timestamps,
`win-dxgi` backend preferred over `robot` via ServiceLoader.

Hardening (Phase 2): multi-output enumeration with desktop origins + HDR flag,
dirty/move rect union per frame, optional cursor compositing (color/mono/masked),
transparent reopen on mode-change/device-loss, comparative JMH bench, Windows CI,
`--enable-native-access` in surefire. Proven live: dirty rects down to 34x34,
cursor shape/position end-to-end.

GPU zero-copy (Phase 3): `GpuFrame`/`GpuCaptureSession`/`openGpu` API, native
shared-texture pool (NT handles) with `ID3D11Fence` signaling, fail-fast
`NO_SLOT` pool semantics, cross-device C self-test (second-device pixel verify +
fence wait + exhaustion). Measured 3440x1440: GPU ~329 fps / 3 ms vs CPU ~115 fps
/ 8.7 ms vs Robot ~10 fps. Documented driver findings: `SHARED_NTHANDLE` requires
`SHARED_KEYEDMUTEX`, fences open on the D3D12 side, keyed mutex mandatory for
coherency (single key 0 both sides), `WAIT_TIMEOUT` is a success code.

Release: self-contained DLL (static CRT, system deps only), README, MIT license.
