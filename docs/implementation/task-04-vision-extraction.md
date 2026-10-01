# Task 4 — Characterized Vision extraction

The portable `VisionProcessor` now accepts `CameraFrame` and `VisionConfiguration`, and returns a `VisionResult` containing camera identity, capture time, source sequence, processed dimensions, raw/grayscale/threshold/edge stages, ROI and detected edges, optional accepted measurement, effective threshold, timings and detection error.

## Preserved behavior

The operation order is source metadata normalization, configured geometry, grayscale, threshold, edge map, row detection, median pixel width, millimetre conversion and confidence. Source normalization applies `CameraFrame.mirrored` as a horizontal mirror, then `orientationDegrees` as rotation. Configured geometry applies its mirrors and rotation to that normalized image. ROI coordinates refer to the final processed image. The row candidate nearest the ROI centre is selected; confidence remains row coverage multiplied by width consistency using population standard deviation. Manual threshold and sensitivity clamps, BT.601 rounding, Otsu first-best selection and its `+1` threshold convention follow the reference formulas.

The committed root fixture is exposed as a Core **test resource**, without a production dependency on fixtures or the reference module. Its literals were not modified:

| Output | Frozen value |
|---|---|
| Frame | A, timestamp 99, sequence 3, 8×4 |
| Grayscale row | 100, 100, 20, 20, 20, 20, 100, 100 |
| Threshold row | 255, 255, 0, 0, 0, 0, 255, 255 |
| Edge row | 0, 255, 255, 0, 0, 255, 255, 0 |
| ROI / edges | [0,0,8,4), left 2, right 6 |
| Measurement | 4.0 pixels, 0.4 mm, confidence 1.0, no error |
| Effective threshold | manual 40; automatic on background 200 / strip 20: 21 |

An all-100 image returns `NO_EDGES`, preserves ROI, and publishes neither a measurement nor overlay edges. Invalid calibration and ROI preserve their existing errors. A rejected dark run—including one touching the ROI boundary or failing edge sensitivity—retains the reference `WIDTH_OUT_OF_RANGE` classification. Low confidence preserves detected overlay edges but publishes no measurement. Confidence exactly equal to the minimum is accepted.

The geometry tests cover an asymmetric 2×3 image through rotations and both mirror axes, plus the frozen 90°/vertical-mirror case whose stages are all 4×8. Source-only orientation and horizontal mirroring have asymmetric literal tests. A combined case checks source normalization before configured mirroring and verifies the raw, grayscale, threshold and edge stages. Zero orientation and false source mirroring preserve all reference/configured-only behavior. Metadata normalization and configured transforms are separate sequential operations; they are not interchangeable.

## Data ownership and timing

Geometry creates an owned ARGB output, including for identity geometry, allowing diagnostic output to outlive the input lease. When both source normalization and configured geometry are non-identity, each sequential transform creates its own output; the normalized intermediate is not retained. If either transform is identity, its extra copy is skipped, while preserving one owned final output. Grayscale, threshold and edge processing each allocate their required output exactly once. Their `PixelBuffer` views expose indexed reads and no mutable arrays. The same computed stage buffers used by detection are retained in `VisionResult`; there is no copy per consumer and no preview packing, image encoding, or desktop API. Core does not close the input frame; its owner remains responsible for releasing it after processing.

The stages are full-resolution Core diagnostic data. Intensity stages contain scalar 0–255 values rather than the Android preview's packed grayscale ARGB values; the frozen golden tests check those exact scalar values. Android's 480-pixel UI thumbnail resizing and encoding remain adapter/presentation concerns and are not exposed as Core processing behavior.

`NanoClock` defaults to `System.nanoTime()` and is injected in tests. All six measured stage intervals are 10 ns for a clock advancing 10 ns per read; with 7 ns supplied decode time, total is exactly 137 ns. The legacy `previewNanos` timing slot now measures diagnostic/overlay assembly, without creating UI previews. Receive monotonic time is not subtracted from capture epoch time.

## Verification

Each implementation slice followed a failing test and a minimal port: fixed golden, automatic Otsu, no edges, geometry, invalid calibration, invalid ROI, rejected dark runs, low confidence and BT.601. Review fix round 1 added three failing tests for source orientation, source mirror and source-before-configured composition before implementing metadata normalization. The initial test resource lookup needed a class-loader correction; no expected numeric value changed. Core Vision has 14 tests across three classes.

Run from this repository with the established offline Gradle/JDK environment:

```powershell
$env:GRADLE_USER_HOME=(Join-Path (Get-Location) '.gradle')
$env:JAVA_HOME='E:\Study_Software\Android\.gradle\jdks\eclipse_adoptium-21-amd64-windows.2'
.\gradlew.bat :core:test --tests "com.filamentvision.core.vision.*" :reference-characterization:test --offline --rerun-tasks
.\gradlew.bat :core:test :core:verifyCorePlatformBoundary :backend:test :reference-characterization:test --offline --rerun-tasks
```

The reference module is test-only and still uses the pre-existing read-only E: source/compiler paths. Production modules remain independent of it. The Task 4 execution report records command outcomes, counts, diff checks and the commit.

## Scope and follow-up

No Android, Fusion, Alarm, Statistics, simulator, runtime, persistence, API, networking or React implementation changes are included. `CameraMeasurement` retains source sequence for the later Fusion/pairing work. Existing validation semantics were ported, including their current handling of non-finite calibration values; broader configuration validation belongs to the planned configuration task. The installed JDK 21 targets Java 17 bytecode, as established by Task 2.
