# FilamentVision Phase 2 Backend Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a headless, simulator-driven Kotlin backend that preserves the current Vision/Fusion/Alarm/Statistics behavior, owns exactly one monitoring producer, persists through repository adapters, and exposes versioned REST plus bounded WebSocket streams without coupling the portable core to any platform framework.

**Architecture:** Keep the Android `:app` operational while adding a pure Kotlin/JVM `:core` and a Ktor `:backend`. Encoded images, network packets, JDBC, JSON, HTTP, WebSocket, and platform codecs terminate in adapters; only canonical frames and domain commands cross into Core. Establish characterization fixtures before extraction, then migrate in reviewable slices with golden equivalence tests and a final image-to-API end-to-end test.

**Tech Stack:** Kotlin 2.2.10/JVM 17, Gradle, kotlinx-coroutines 1.9.0, JUnit 4.13.2, Ktor 3.3.1, Jackson Ktor serialization, Xerial SQLite JDBC 3.51.1.0.

**Spec:** `docs/web-migration/16-phase2-backend-foundation.md`

## Approved execution-path override

This plan is executed from the standalone repository root
`E:\Study\Monash\Y2S2\ENG5105\webiste2.0` (with a sandbox edit mirror that is
fast-forward synchronized after every task checkpoint). The Android repository
`E:\Study\Monash\Y2S2\ENG5105\interface` is read-only reference evidence.

- All production `:core`, `:backend`, simulator, fixture, database, API and
  documentation files are created in this standalone repository.
- Task 1 runs the existing Android tests read-only and commits their fixed
  inputs/outputs as fixtures here; it does not add tests to `interface`.
- Task 2 creates this standalone project's Gradle root plus `:core` and
  `:backend`; it does not modify the Android Gradle build.
- Tasks 4 and 5 compare Core results against committed Task 1 golden values
  and the read-only Android baseline test output. No permanent Gradle dependency
  on `interface` is allowed.
- Task 14 is a compatibility verification checkpoint. It runs Android tests
  read-only and compares fixture behavior, but does not switch or edit Android
  production consumers in Phase 2.
- Task 15 proves this repository can build, test and run after the Android
  repository path is unavailable.
- `frontend/` is reserved with documentation only. Final React UI work starts
  in Phase 3 and is outside this plan.

## Global Constraints

- `:core` may depend only on Kotlin/JDK-neutral language utilities and `kotlinx-coroutines-core`; it must not depend on Android, Ktor, SQLite/JDBC, Room, Compose, Jackson/JSON, AWT, Swing, JavaFX, `ImageIO`, or desktop-only image APIs.
- JPEG, PNG, network packet, database row, JSON and WebSocket representations are adapter types and must be converted before entering Core.
- No real Bluetooth, Wi-Fi, CameraX, OpenCV or Raspberry Pi protocol is implemented or inferred.
- Characterization/golden tests must pass against the current code before any Vision, Fusion, Alarm or Statistics implementation is extracted.
- Backend runtime ownership, not observer count or UI lifecycle, guarantees `activeProducerCount <= 1`.
- Preview delivery is bounded and latest-frame-first. It may drop old previews but may not share a lossy queue with measurement, alarm, session or configuration events.
- Camera ID, capture timestamp and source sequence survive ingestion, processing, fusion, persistence and event publication.
- Frame pairing is a Core strategy interface. Transport, simulator and Web API code do not implement pairing policy.
- SQLite exists only under a backend repository adapter. Core/application/runtime code imports no SQL or JDBC types.
- Current alarm semantics remain immediate 2% warning / 5% critical with no newly invented hysteresis, debounce or acknowledgement.
- The existing Android unit suite and `assembleDebug` must remain green at every migration checkpoint.
- Do not begin React/Web UI work or delete the Android app in Phase 2.

## File and responsibility map

### Build and dependency boundary

- Modify `settings.gradle.kts` — register `:core` and `:backend`.
- Modify `build.gradle.kts` — declare Kotlin/JVM and serialization plugins without applying them globally.
- Create `core/build.gradle.kts` — minimal platform-neutral dependencies and `verifyCorePlatformBoundary` task.
- Create `backend/build.gradle.kts` — Ktor/Jackson/JDBC dependencies; nothing leaks through Core APIs.

### Core files

- `core/src/main/kotlin/com/filamentvision/core/frame/CameraFrame.kt` — canonical decoded frame metadata and immutable pixels.
- `core/src/main/kotlin/com/filamentvision/core/frame/FrameLease.kt` — explicit frame lifetime/release contract.
- `core/src/main/kotlin/com/filamentvision/core/input/ImageInputSource.kt` — protocol-independent source lifecycle.
- `core/src/main/kotlin/com/filamentvision/core/config/MonitoringConfiguration.kt` — revisioned domain configuration.
- `core/src/main/kotlin/com/filamentvision/core/vision/*` — extracted preprocessing, detection and processing logic.
- `core/src/main/kotlin/com/filamentvision/core/fusion/*` — extracted fusion plus pluggable pairing strategy.
- `core/src/main/kotlin/com/filamentvision/core/alarm/*` — evaluator/state/event separation.
- `core/src/main/kotlin/com/filamentvision/core/session/*` — session model and incremental statistics.
- `core/src/main/kotlin/com/filamentvision/core/repository/*` — platform-neutral repository contracts.
- `core/src/main/kotlin/com/filamentvision/core/runtime/*` — serialized runtime state machine, finalization and buffers.
- `core/src/main/kotlin/com/filamentvision/core/event/*` — lossless authoritative domain event contract.
- `core/src/main/kotlin/com/filamentvision/core/preview/*` — preview model and latest-frame publisher contract.
- `core/src/main/kotlin/com/filamentvision/core/metrics/*` — counters/rates/latency snapshots.

### Backend files

- `backend/src/main/kotlin/com/filamentvision/backend/simulator/*` — deterministic image-level scenarios.
- `backend/src/main/kotlin/com/filamentvision/backend/persistence/sqlite/*` — migrations and JDBC repository adapters.
- `backend/src/main/kotlin/com/filamentvision/backend/api/dto/*` — JSON DTOs and explicit domain mapping.
- `backend/src/main/kotlin/com/filamentvision/backend/api/Routes.kt` — `/api/v1` REST routes only.
- `backend/src/main/kotlin/com/filamentvision/backend/api/EventWebSocket.kt` — reliable sequenced domain events.
- `backend/src/main/kotlin/com/filamentvision/backend/api/PreviewWebSocket.kt` — bounded lossy preview transport.
- `backend/src/main/kotlin/com/filamentvision/backend/BackendApplication.kt` — composition root.
- `backend/src/main/kotlin/com/filamentvision/backend/DiagnosticClient.kt` — minimal non-React test client.

---

### Task 1: Freeze the pre-extraction behavior

**Files:**
- Create: `app/src/test/java/com/filamentvision/characterization/VisionGoldenCharacterizationTest.kt`
- Create: `app/src/test/java/com/filamentvision/characterization/FusionGoldenCharacterizationTest.kt`
- Create: `app/src/test/java/com/filamentvision/characterization/AlarmStatisticsGoldenCharacterizationTest.kt`
- Create: `app/src/test/resources/characterization/vision-dark-strip-v1.properties`
- Modify: none under `app/src/main`

**Interfaces:**
- Consumes: current `ArgbFrame`, `VisionProcessor`, `FusionEngine`, `AlarmEvaluator`, `SessionStatisticsAccumulator`.
- Produces: fixed fixtures and hard-coded expected behavior used again by Core extraction tests.

- [ ] **Step 1: Add deterministic Vision characterization assertions**

Use an 8×4 frame with background intensity 100 and a four-pixel dark strip at x=2..5. The committed properties file records inputs and expected values, not values generated during the test:

```properties
width=8
height=4
background=100
filament=20
stripLeft=2
stripRightExclusive=6
manualThreshold=40
edgeSensitivity=30
mmPerPixel=0.1
expectedPixelWidth=4.0
expectedDiameterMm=0.4
expectedConfidence=1.0
expectedEffectiveThreshold=40
expectedSequence=3
expectedTimestamp=99
```

The test must assert the exact grayscale, binary and edge arrays already proven by `VisionProcessorTest`, plus `cameraId`, timestamp, sequence, pixel width, diameter, confidence, edges and absence of error.

- [ ] **Step 2: Add Fusion characterization cases**

```kotlin
@Test fun weightedDualCameraAndFallbacksAreFrozen() {
    val profile = FusionProfile(0.25, 0.75, 0.7, 100)
    val dual = FusionEngine().fuseDetailed(m("A", 1.7, .9, 1_000), m("B", 1.8, .8, 1_050), profile)!!
    assertEquals(1.775, dual.measurement.fusedDiameter, 0.000_001)
    assertEquals(FusionReason.BOTH_CAMERAS, dual.reason)
    val stale = FusionEngine().fuseDetailed(m("A", 1.7, .9, 1_000), m("B", 1.8, .9, 1_500), profile)!!
    assertEquals(1.8, stale.measurement.fusedDiameter, 0.000_001)
    assertEquals(FusionReason.TIMESTAMP_MISMATCH, stale.reason)
}
```

Also freeze low-confidence A/B, missing A/B, zero-weight averaging, shape difference and raw confidence values.

- [ ] **Step 3: Add Alarm and Statistics characterization cases**

Assert values immediately below/above 2% and 5%, transition timestamps, no repeat transition within the same state, Welford mean/min/max/population deviation and average confidence.

- [ ] **Step 4: Run the characterization tests against the untouched implementation**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.filamentvision.characterization.*"
```

Expected: PASS. If an asserted value differs, inspect the current implementation and commit the observed current behavior only after confirming it is deterministic; do not modify production logic to satisfy the fixture.

- [ ] **Step 5: Run the complete baseline**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

Expected: all existing tests and assembly pass. Record test counts and SHA-256 hashes of the three characterization test files in the implementation report notes.

- [ ] **Step 6: Commit the behavior freeze**

```powershell
git add app/src/test/java/com/filamentvision/characterization app/src/test/resources/characterization
git commit -m "test: freeze monitoring algorithm behavior"
```

**Rollback boundary:** Revert this commit only; no production files or dependencies changed.

---

### Task 2: Add modules and enforce the Core platform firewall

**Files:**
- Modify: `settings.gradle.kts`
- Modify: `build.gradle.kts`
- Create: `core/build.gradle.kts`
- Create: `core/src/test/kotlin/com/filamentvision/core/architecture/CorePlatformBoundaryTest.kt`
- Create: `backend/build.gradle.kts`
- Create: `backend/src/test/kotlin/com/filamentvision/backend/BackendSmokeTest.kt`

**Interfaces:**
- Consumes: root Gradle repository configuration and Kotlin 2.2.10.
- Produces: isolated `:core` and `:backend` compilation/test boundaries.

- [ ] **Step 1: Write a failing source-boundary test before adding Core sources**

The test reads `core/src/main/kotlin` and rejects imports matching:

```kotlin
private val forbidden = listOf(
    "android.", "androidx.", "io.ktor.", "java.awt.", "javax.imageio.",
    "java.sql.", "org.sqlite.", "androidx.room.", "androidx.compose.",
    "com.fasterxml.jackson.", "kotlinx.serialization.",
)
```

It must also assert that `core/build.gradle.kts` contains no forbidden group/artifact tokens. Keep this test in Core so every Core build runs the firewall.

- [ ] **Step 2: Run the intended module test and verify RED**

Run:

```powershell
.\gradlew.bat :core:test
```

Expected: FAIL because project `:core` does not exist.

- [ ] **Step 3: Register modules and minimal dependencies**

Add `include(":app", ":core", ":backend")`. Apply `org.jetbrains.kotlin.jvm` version `2.2.10` to Core and Backend. Core dependencies are exactly Kotlin standard library, `kotlinx-coroutines-core:1.9.0`, JUnit and coroutines-test. Backend owns Ktor 3.3.1, Jackson plugin, SQLite JDBC 3.51.1.0 and `implementation(project(":core"))`.

Configure JVM toolchain 17 in both modules. Do not add `api(...)` dependencies from Core.

- [ ] **Step 4: Add the smallest compiling smoke classes/tests**

```kotlin
// backend test
@Test fun backendCanReferenceCoreWithoutCoreReferencingBackend() {
    assertTrue(Class.forName("com.filamentvision.core.CoreMarker") != null)
}
```

`CoreMarker` is an `object` in `core/src/main/kotlin/com/filamentvision/core/CoreMarker.kt` and has no framework imports.

- [ ] **Step 5: Verify GREEN and inspect the dependency graph**

Run:

```powershell
.\gradlew.bat :core:test :core:dependencies --configuration runtimeClasspath :backend:test
```

Expected: Core tests pass; Core runtime classpath lists only Kotlin/coroutines and excludes Ktor, SQLite/JDBC adapters, AndroidX, Jackson and image libraries.

- [ ] **Step 6: Verify Android remains unchanged**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

- [ ] **Step 7: Commit the module firewall**

```powershell
git add settings.gradle.kts build.gradle.kts core backend
git commit -m "build: add isolated core and backend modules"
```

**Rollback boundary:** Revert module registration and the two new directories; `:app` returns to the original single-module build.

---

### Task 3: Define the canonical decoded frame and adapter boundary

**Files:**
- Create: `core/src/main/kotlin/com/filamentvision/core/frame/CameraFrame.kt`
- Create: `core/src/main/kotlin/com/filamentvision/core/frame/FrameLease.kt`
- Create: `core/src/main/kotlin/com/filamentvision/core/frame/PixelBuffer.kt`
- Create: `core/src/main/kotlin/com/filamentvision/core/input/ImageInputSource.kt`
- Create: `core/src/test/kotlin/com/filamentvision/core/frame/CameraFrameTest.kt`
- Create: `backend/src/main/kotlin/com/filamentvision/backend/adapter/DecodedFrameAdapter.kt`
- Create: `backend/src/test/kotlin/com/filamentvision/backend/adapter/DecodedFrameAdapterTest.kt`

**Interfaces:**
- Produces: `CameraFrame`, `FrameLease`, `PixelBuffer`, `ImageInputSource`.
- `ImageInputSource.frames: Flow<CameraFrame>`, `suspend start()`, `suspend stop()`, `activeProducerCount: Int`.
- Consumes in later tasks: simulator, pairing strategy, vision pipeline, snapshot/preview cache.

- [ ] **Step 1: Write failing frame invariant and lease tests**

```kotlin
@Test fun frameRejectsWrongPixelCountAndReleasesOnce() {
    assertFailsWith<IllegalArgumentException> {
        CameraFrame(CameraId("A"), 99, 100, 8, 4, PixelFormat.ARGB32, PixelBuffer.ints(IntArray(31)), 0, false, 3, FrameDiagnostics())
    }
    var releases = 0
    val lease = FrameLease { releases++ }
    lease.close(); lease.close()
    assertEquals(1, releases)
}
```

Also assert allowed rotations are 0/90/180/270 and metadata retains `cameraId`, capture timestamp, receive monotonic timestamp and sequence.

- [ ] **Step 2: Run RED**

```powershell
.\gradlew.bat :core:test --tests "com.filamentvision.core.frame.CameraFrameTest"
```

Expected: compile failure because frame types do not exist.

- [ ] **Step 3: Implement immutable Core frame types**

`PixelBuffer` exposes read-only indexed access and exact element count. It accepts an owned `IntArray` for ARGB32 in Phase 2, never exposes the mutable array, and documents one ownership transfer at adapter ingress. Core contains no JPEG/PNG or packet type.

- [ ] **Step 4: Add backend decoded-frame mapping test and implementation**

The backend-only `DecodedImage(width, height, argb: IntArray)` maps to `CameraFrame`. Its test verifies one defensive ownership copy by mutating the adapter input after conversion and asserting the Core frame did not change. This is where a future JPEG/PNG/network decoder ends; it never appears in Core.

- [ ] **Step 5: Run boundary and frame tests**

```powershell
.\gradlew.bat :core:test :backend:test --tests "*DecodedFrameAdapterTest" :core:verifyCorePlatformBoundary
```

- [ ] **Step 6: Commit**

```powershell
git add core backend
git commit -m "feat: define platform-neutral frame boundary"
```

**Rollback boundary:** Remove only canonical frame/adapter types; no current Android call site has switched.

---

### Task 4: Extract Vision logic with golden equivalence

**Files:**
- Create: `core/src/main/kotlin/com/filamentvision/core/config/VisionConfiguration.kt`
- Create: `core/src/main/kotlin/com/filamentvision/core/vision/IntensityFrame.kt`
- Create: `core/src/main/kotlin/com/filamentvision/core/vision/GrayscaleProcessor.kt`
- Create: `core/src/main/kotlin/com/filamentvision/core/vision/ThresholdProcessor.kt`
- Create: `core/src/main/kotlin/com/filamentvision/core/vision/EdgeMapProcessor.kt`
- Create: `core/src/main/kotlin/com/filamentvision/core/vision/ImageGeometryTransformer.kt`
- Create: `core/src/main/kotlin/com/filamentvision/core/vision/DetectionPipeline.kt`
- Create: `core/src/main/kotlin/com/filamentvision/core/vision/VisionProcessor.kt`
- Create: `core/src/main/kotlin/com/filamentvision/core/vision/VisionResult.kt`
- Create: `core/src/test/kotlin/com/filamentvision/core/vision/VisionGoldenTest.kt`
- Modify: `app/build.gradle.kts`
- Create: `app/src/test/java/com/filamentvision/characterization/LegacyCoreVisionEquivalenceTest.kt`

**Interfaces:**
- Consumes: `CameraFrame`, `VisionConfiguration`.
- Produces: `VisionResult(cameraId, timestampMillis, sequence, measurement, stages, overlay, timings, error)`.
- Timings use injected `NanoClock`; algorithms never call Android or desktop image APIs.

- [ ] **Step 1: Copy the frozen fixture into a failing Core golden test**

The Core test recreates exactly the Task 1 frame/configuration and expects pixel width `4.0`, diameter `0.4`, confidence `1.0`, threshold `40`, sequence `3`, timestamp `99`, plus exact stage arrays.

- [ ] **Step 2: Run RED**

```powershell
.\gradlew.bat :core:test --tests "com.filamentvision.core.vision.VisionGoldenTest"
```

Expected: missing Core vision classes.

- [ ] **Step 3: Extract the minimum algorithm code without redesigning math**

Port the existing operation order and formulas. Replace `ArgbFrame` with `CameraFrame`, replace `System.nanoTime()` with injected `NanoClock`, and keep preview stage pixels as Core diagnostic data without encoding them.

- [ ] **Step 4: Run Core golden test GREEN**

```powershell
.\gradlew.bat :core:test --tests "com.filamentvision.core.vision.VisionGoldenTest"
```

- [ ] **Step 5: Add a temporary side-by-side equivalence test in Android**

Add `testImplementation(project(":core"))`. For each frozen fixture, run legacy and Core processors and compare camera ID, timestamp, sequence, dimensions, effective threshold, measurement fields, error, overlay and every stage pixel. Ignore timing durations except that each is non-negative.

```kotlin
assertEquals(legacy.measurement!!.pixelWidth, extracted.measurement!!.pixelWidth, 0.0)
assertEquals(legacy.measurement!!.diameterMm, extracted.measurement!!.diameterMm, 0.0)
assertArrayEquals(legacy.grayscalePreview.pixels, extracted.stages.grayscale.toArgbArray())
```

- [ ] **Step 6: Run both implementations and full Android suite**

```powershell
.\gradlew.bat :core:test :app:testDebugUnitTest --tests "com.filamentvision.characterization.*" :app:assembleDebug
```

Expected: all golden and side-by-side comparisons pass.

- [ ] **Step 7: Commit extraction without switching production composition**

```powershell
git add core app/build.gradle.kts app/src/test/java/com/filamentvision/characterization
git commit -m "refactor: extract characterized vision core"
```

**Rollback boundary:** Android production still uses its legacy processor; reverting Core files and test dependency restores the prior state.

---

### Task 5: Extract Fusion, Alarm and Statistics with equivalence tests

**Files:**
- Create: `core/src/main/kotlin/com/filamentvision/core/model/MeasurementModels.kt`
- Create: `core/src/main/kotlin/com/filamentvision/core/fusion/FusionEngine.kt`
- Create: `core/src/main/kotlin/com/filamentvision/core/fusion/FramePairingStrategy.kt`
- Create: `core/src/main/kotlin/com/filamentvision/core/fusion/LatestCompatiblePairingStrategy.kt`
- Create: `core/src/main/kotlin/com/filamentvision/core/alarm/AlarmEvaluator.kt`
- Create: `core/src/main/kotlin/com/filamentvision/core/session/SessionStatisticsAccumulator.kt`
- Create: `core/src/test/kotlin/com/filamentvision/core/fusion/FusionGoldenTest.kt`
- Create: `core/src/test/kotlin/com/filamentvision/core/alarm/AlarmGoldenTest.kt`
- Create: `core/src/test/kotlin/com/filamentvision/core/session/StatisticsGoldenTest.kt`
- Create: `app/src/test/java/com/filamentvision/characterization/LegacyCoreDomainEquivalenceTest.kt`

**Interfaces:**
- `FramePairingStrategy.offer(CameraMeasurement): List<MeasurementPair>`.
- `LatestCompatiblePairingStrategy` preserves current latest-counterpart behavior but is injected into pipeline/runtime.
- `FusionEngine.fuseDetailed(cameraA, cameraB, FusionConfiguration): FusionResult?`.
- `AlarmEvaluator.evaluate(OfficialMeasurement): AlarmEvaluation`.
- `SessionStatisticsAccumulator.add(OfficialMeasurement)` and `snapshot(abnormalEventCount)`.

- [ ] **Step 1: Write failing Core golden tests from Task 1 expectations**

Tests cover dual weighted fusion, missing/low-confidence cameras, timestamp mismatch, nullable API-facing camera values, exact alarm boundaries and statistics.

- [ ] **Step 2: Write a failing pairing-policy isolation test**

```kotlin
@Test fun pipelineDelegatesPairSelectionToInjectedStrategy() {
    val strategy = RecordingPairingStrategy()
    coordinator.accept(cameraMeasurementA)
    assertEquals(listOf(cameraMeasurementA), strategy.offers)
}
```

The test proves transport/source classes and event/API classes are not referenced by the strategy.

- [ ] **Step 3: Run RED**

```powershell
.\gradlew.bat :core:test --tests "com.filamentvision.core.fusion.*" --tests "com.filamentvision.core.alarm.*" --tests "com.filamentvision.core.session.*"
```

- [ ] **Step 4: Port formulas and isolate pairing policy**

Keep existing fusion behavior. Domain may retain explicit absence as nullable values; conversion from legacy `Double.NaN` is tested at the compatibility boundary. Do not put pairing logic in simulator, transport adapters, Ktor routes or WebSocket publishers.

- [ ] **Step 5: Add and run legacy-versus-Core comparisons**

Compare all fixture outputs field-by-field, including fusion reason, active cameras, timestamp compatibility, deviations, transition timestamps and population standard deviation.

```powershell
.\gradlew.bat :core:test :app:testDebugUnitTest --tests "com.filamentvision.characterization.*"
```

- [ ] **Step 6: Commit**

```powershell
git add core app/src/test/java/com/filamentvision/characterization
git commit -m "refactor: extract fusion alarm and statistics core"
```

**Rollback boundary:** Revert extracted classes/equivalence tests; Android production implementation remains authoritative until a separate switch checkpoint.

---

### Task 6: Build deterministic image-level Simulator scenarios

**Files:**
- Create: `backend/src/main/kotlin/com/filamentvision/backend/simulator/SimulationScenario.kt`
- Create: `backend/src/main/kotlin/com/filamentvision/backend/simulator/SimulatorImageInputSource.kt`
- Create: `backend/src/main/kotlin/com/filamentvision/backend/simulator/SimulatorFrameFactory.kt`
- Create: `backend/src/test/kotlin/com/filamentvision/backend/simulator/SimulatorImageInputSourceTest.kt`
- Create: `backend/src/test/kotlin/com/filamentvision/backend/simulator/SimulatorScenarioVisionTest.kt`

**Interfaces:**
- Implements Core `ImageInputSource`.
- Produces canonical `CameraFrame` only; never produces final measurements.
- Accepts `SimulationScenario`, injected `Clock`, `CoroutineDispatcher` and frame period.

- [ ] **Step 1: Write failing lifecycle and scenario tests**

```kotlin
@Test fun concurrentStartsOwnOneProducerAndStopJoinsIt() = runTest {
    val source = source(NORMAL)
    List(20) { async { source.start() } }.awaitAll()
    assertEquals(1, source.activeProducerCount)
    source.stop()
    assertEquals(0, source.activeProducerCount)
}
```

Image assertions cover Normal, High Diameter, Low Diameter, Camera A/B only, mismatch, timestamp mismatch, low confidence, dropout and connection failure/recovery. Validate camera ID/timestamp/sequence before Vision processing.

- [ ] **Step 2: Run RED**

```powershell
.\gradlew.bat :backend:test --tests "com.filamentvision.backend.simulator.*"
```

- [ ] **Step 3: Implement source ownership with a Mutex and one producer Job**

`start()` checks/creates the job within the same mutex; `stop()` clears acceptance, cancels and joins outside the locked mutation path without exposing an interval where another job can coexist. Frame flows are bounded/latest-first per camera.

- [ ] **Step 4: Prove scenarios pass through Core Vision**

`SimulatorScenarioVisionTest` must feed generated frames into Core `VisionProcessor` and assert scenario direction/outcome rather than reading precomputed diameter values from the simulator.

- [ ] **Step 5: Run GREEN and a virtual-time restart cycle**

```powershell
.\gradlew.bat :backend:test --tests "com.filamentvision.backend.simulator.*"
```

Assert `start → stop → start` yields total starts 2 but simultaneous count never exceeds 1 and sequence remains monotonic per new source epoch contract.

- [ ] **Step 6: Commit**

```powershell
git add backend/src/main/kotlin/com/filamentvision/backend/simulator backend/src/test/kotlin/com/filamentvision/backend/simulator
git commit -m "feat: add image-level monitoring simulator"
```

**Rollback boundary:** Remove simulator adapter; Core remains usable with test sources.

---

### Task 7: Implement the authoritative concurrent Monitoring Runtime

**Files:**
- Create: `core/src/main/kotlin/com/filamentvision/core/runtime/MonitoringState.kt`
- Create: `core/src/main/kotlin/com/filamentvision/core/runtime/MonitoringCommand.kt`
- Create: `core/src/main/kotlin/com/filamentvision/core/runtime/MonitoringRuntime.kt`
- Create: `core/src/main/kotlin/com/filamentvision/core/runtime/RuntimeOwnership.kt`
- Create: `core/src/main/kotlin/com/filamentvision/core/runtime/MeasurementWriteBuffer.kt`
- Create: `core/src/main/kotlin/com/filamentvision/core/runtime/RealtimeMeasurementBuffer.kt`
- Create: `core/src/test/kotlin/com/filamentvision/core/runtime/MonitoringRuntimeConcurrencyTest.kt`
- Create: `core/src/test/kotlin/com/filamentvision/core/runtime/MonitoringRuntimeFailureTest.kt`

**Interfaces:**
- `MonitoringRuntime.start(StartMonitoringCommand): StartResult`.
- `MonitoringRuntime.stop(StopMonitoringCommand): StopResult`.
- `MonitoringRuntime.close()` interrupts and finalizes safely.
- `RuntimeOwnership` serializes commands and owns the only source Job/session acceptance gate.
- Uses injected source, pairing strategy, processor, fusion, repositories, clock and domain event sink.

- [ ] **Step 1: Write RED concurrency tests**

Required interleavings:

```kotlin
@Test fun concurrentDuplicateStartCreatesOneSessionAndProducer() = runTest { /* 50 starts */ }
@Test fun startRacingStopEndsWithZeroOrOneProducerMatchingState() = runTest { /* barriers */ }
@Test fun stopDuringStartingCancelsAndFinalizesOnce() = runTest { /* delayed source start */ }
@Test fun producerFailureUsesInterruptedFinalizePipeline() = runTest { /* throwing source */ }
@Test fun restartAfterCompletedSessionCreatesOneNewSession() = runTest { /* start-stop-start */ }
@Test fun closeDuringRunningFlushesBeforeReturning() = runTest { /* pending batch */ }
```

Each test records the maximum simultaneous source producers, session create/finalize counts and accepted-versus-persisted measurement counts.

- [ ] **Step 2: Run RED**

```powershell
.\gradlew.bat :core:test --tests "com.filamentvision.core.runtime.*"
```

- [ ] **Step 3: Implement ownership as a serialized state machine**

All state-changing commands execute through one `Mutex`-guarded transition function or command actor. `STARTING` reserves ownership before calling the source. A generation/session token rejects late frames after stop. The runtime accepts measurements only in `RUNNING` with the active token.

Finalization order is fixed:

```text
RUNNING/STARTING -> STOPPING -> close acceptance -> stop source -> await producer
-> flush measurement -> flush alarm -> finalize statistics/session -> publish final event
-> IDLE or ERROR
```

- [ ] **Step 4: Make command IDs idempotent with a bounded result cache**

Duplicate `commandId` returns the original result. The cache has a configured maximum and cannot grow for the process lifetime.

- [ ] **Step 5: Run concurrency tests repeatedly**

```powershell
1..20 | ForEach-Object { .\gradlew.bat :core:test --tests "com.filamentvision.core.runtime.*"; if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE } }
```

Expected: every run passes; observed maximum producer count is 1; every finalized session has all accepted measurements.

- [ ] **Step 6: Commit**

```powershell
git add core/src/main/kotlin/com/filamentvision/core/runtime core/src/test/kotlin/com/filamentvision/core/runtime
git commit -m "feat: add single-owner monitoring runtime"
```

**Rollback boundary:** Runtime is behind Core interfaces and not yet installed in Android or server composition; revert this task without touching algorithms/simulator.

---

### Task 8: Add repository/configuration contracts and SQLite adapters

**Files:**
- Create: `core/src/main/kotlin/com/filamentvision/core/repository/SessionRepository.kt`
- Create: `core/src/main/kotlin/com/filamentvision/core/repository/MeasurementRepository.kt`
- Create: `core/src/main/kotlin/com/filamentvision/core/repository/AlarmEventRepository.kt`
- Create: `core/src/main/kotlin/com/filamentvision/core/repository/ConfigurationRepository.kt`
- Create: `core/src/main/kotlin/com/filamentvision/core/repository/SnapshotRepository.kt`
- Create: `core/src/main/kotlin/com/filamentvision/core/repository/ErrorRepository.kt`
- Create: `core/src/main/kotlin/com/filamentvision/core/config/MonitoringConfiguration.kt`
- Create: `core/src/main/kotlin/com/filamentvision/core/config/ConfigurationService.kt`
- Create: `core/src/test/kotlin/com/filamentvision/core/config/ConfigurationServiceTest.kt`
- Create: `backend/src/main/resources/db/migration/V001__phase2_foundation.sql`
- Create: `backend/src/main/kotlin/com/filamentvision/backend/persistence/sqlite/SqliteDatabase.kt`
- Create: `backend/src/main/kotlin/com/filamentvision/backend/persistence/sqlite/SqliteRepositories.kt`
- Create: `backend/src/test/kotlin/com/filamentvision/backend/persistence/sqlite/SqliteRepositoryContractTest.kt`

**Interfaces:**
- Repositories consume/return Core records only.
- Batch append is atomic; history is keyset-paged by `(timestamp, id)`.
- `ConfigurationService.save`, `apply`, `saveAndApply` preserve saved/applied revisions.

- [ ] **Step 1: Write failing configuration semantics tests**

Assert Save does not alter applied revision, Apply applies exactly the saved revision, stale expected revision conflicts, Save-and-Apply never applies a different revision, and restart-sensitive Apply while running returns `REQUIRES_STOP` without changing runtime state.

- [ ] **Step 2: Write failing repository conformance tests**

Run the same contract against in-memory test adapters and SQLite temporary-file adapters. Assert transaction rollback on a deliberately invalid item, ordered keyset history, retained Camera A/B/fused values, alarm/session foreign keys, finalized statistics and restart persistence.

- [ ] **Step 3: Run RED**

```powershell
.\gradlew.bat :core:test --tests "com.filamentvision.core.config.*" :backend:test --tests "com.filamentvision.backend.persistence.sqlite.*"
```

- [ ] **Step 4: Implement Core contracts without JDBC types**

Core repository method signatures use domain records, `Long`, `String`, lists and result types. No `Connection`, `ResultSet`, SQL string, row annotation or database exception leaves the adapter package.

- [ ] **Step 5: Implement SQLite migration and adapters**

Use explicit transactions and prepared statements. Store nullable missing camera values as SQL `NULL`. Add indexes for session/timestamp history and unique configuration revision. Translate `SQLException` into typed repository failures at the adapter boundary.

- [ ] **Step 6: Verify Core firewall and PostgreSQL replaceability**

```powershell
.\gradlew.bat :core:verifyCorePlatformBoundary :core:test :backend:test --tests "*SqliteRepositoryContractTest"
```

Code review check: removing `backend/persistence/sqlite` and substituting the in-memory contract adapter must require no change under `core/runtime`, `core/config` or `backend/api`.

- [ ] **Step 7: Commit**

```powershell
git add core backend/src/main/resources/db backend/src/main/kotlin/com/filamentvision/backend/persistence backend/src/test/kotlin/com/filamentvision/backend/persistence
git commit -m "feat: add repository contracts and sqlite adapters"
```

**Rollback boundary:** Revert SQLite and contracts as one commit; runtime tests continue with their local test doubles.

---

### Task 9: Separate reliable events from bounded preview backpressure

**Files:**
- Create: `core/src/main/kotlin/com/filamentvision/core/event/DomainEvent.kt`
- Create: `core/src/main/kotlin/com/filamentvision/core/event/SequencedEventHub.kt`
- Create: `core/src/main/kotlin/com/filamentvision/core/preview/PreviewFrame.kt`
- Create: `core/src/main/kotlin/com/filamentvision/core/preview/LatestPreviewHub.kt`
- Create: `core/src/test/kotlin/com/filamentvision/core/event/SequencedEventHubTest.kt`
- Create: `core/src/test/kotlin/com/filamentvision/core/preview/LatestPreviewHubBackpressureTest.kt`

**Interfaces:**
- `SequencedEventHub.publish(DomainEvent)` assigns monotonic per-device sequence and stores a bounded replay window.
- `LatestPreviewHub.publish(PreviewFrame)` retains at most one pending frame per camera/stage/subscriber policy and increments dropped-preview metrics.
- Both preserve camera ID, timestamp and source sequence where applicable.

- [ ] **Step 1: Write a failing slow-subscriber isolation test**

```kotlin
@Test fun slowPreviewSubscriberDropsOldPreviewOnly() = runTest {
    repeat(1_000) { preview.publish(frame(sequence = it.toLong())) }
    reliable.publish(MeasurementEvent(/* sequence metadata */))
    reliable.publish(AlarmTransitionEvent(/* ... */))
    assertTrue(preview.metrics.value.droppedFrames > 0)
    assertEquals(999L, preview.latest("A", RAW)!!.sourceSequence)
    assertEquals(listOf("measurement.sample", "alarm.transition"), reliable.events.map { it.type })
}
```

- [ ] **Step 2: Write replay/sequence tests**

Assert monotonic sequences, resume after sequence, expired replay producing a snapshot-required result, and fixed replay capacity.

- [ ] **Step 3: Run RED**

```powershell
.\gradlew.bat :core:test --tests "com.filamentvision.core.event.*" --tests "com.filamentvision.core.preview.*"
```

- [ ] **Step 4: Implement physically separate hubs/queues**

Do not use one `SharedFlow` for both categories. Reliable events use ordered publication plus bounded replay backed by persisted source-of-truth/state snapshot. Preview uses conflated latest-value channels and is never awaited by measurement processing.

- [ ] **Step 5: Run GREEN with 10,000 preview publications**

```powershell
.\gradlew.bat :core:test --tests "*LatestPreviewHubBackpressureTest" --tests "*SequencedEventHubTest"
```

Expected: bounded retained counts, latest source sequence delivered, reliable event count unchanged.

- [ ] **Step 6: Commit**

```powershell
git add core/src/main/kotlin/com/filamentvision/core/event core/src/main/kotlin/com/filamentvision/core/preview core/src/test/kotlin/com/filamentvision/core/event core/src/test/kotlin/com/filamentvision/core/preview
git commit -m "feat: isolate reliable events from preview backpressure"
```

**Rollback boundary:** Hubs are standalone interfaces; runtime can temporarily keep test sinks if reverted.

---

### Task 10: Add bounded snapshot cache and runtime metrics

**Files:**
- Create: `core/src/main/kotlin/com/filamentvision/core/snapshot/LatestFrameSnapshotCache.kt`
- Create: `core/src/main/kotlin/com/filamentvision/core/snapshot/SnapshotPolicy.kt`
- Create: `core/src/main/kotlin/com/filamentvision/core/metrics/MonitoringMetrics.kt`
- Create: `core/src/test/kotlin/com/filamentvision/core/snapshot/LatestFrameSnapshotCacheTest.kt`
- Create: `core/src/test/kotlin/com/filamentvision/core/metrics/MonitoringMetricsTest.kt`
- Create: `backend/src/main/kotlin/com/filamentvision/backend/snapshot/FileSnapshotObjectStore.kt`
- Create: `backend/src/test/kotlin/com/filamentvision/backend/snapshot/FileSnapshotObjectStoreTest.kt`

**Interfaces:**
- Cache is bounded by entries and total encoded bytes, keyed by camera/stage.
- Snapshot policy retains the existing five-minute cooldown.
- Metrics expose per-camera received/processed FPS, fusion rate, dropped counts, latencies, WS publication rate and database batch/row rates.

- [ ] **Step 1: Write failing capacity/cooldown tests**

Assert byte and entry eviction, replacement of the same camera/stage, five-minute capture cooldown and repository metadata containing camera ID/timestamp/source sequence.

- [ ] **Step 2: Write failing metrics tests with an injected clock**

Use virtual time to prove frame rate, processing latency, fusion, drops, WS and DB rates are independent counters. There must be no test that equates these values to 8 Hz.

- [ ] **Step 3: Run RED, implement minimal Core cache/metrics, then run GREEN**

```powershell
.\gradlew.bat :core:test --tests "com.filamentvision.core.snapshot.*" --tests "com.filamentvision.core.metrics.*"
```

- [ ] **Step 4: Implement backend encoded object store**

The store receives already encoded backend bytes, creates bounded filenames/metadata and returns a Core snapshot reference. Core never imports file/image encoder types.

- [ ] **Step 5: Commit**

```powershell
git add core backend/src/main/kotlin/com/filamentvision/backend/snapshot backend/src/test/kotlin/com/filamentvision/backend/snapshot
git commit -m "feat: add bounded snapshots and monitoring metrics"
```

**Rollback boundary:** Remove observability/storage adapter without affecting measurement correctness.

---

### Task 11: Expose versioned REST contracts

**Files:**
- Create: `backend/src/main/kotlin/com/filamentvision/backend/api/dto/StateDtos.kt`
- Create: `backend/src/main/kotlin/com/filamentvision/backend/api/dto/MeasurementDtos.kt`
- Create: `backend/src/main/kotlin/com/filamentvision/backend/api/dto/ConfigurationDtos.kt`
- Create: `backend/src/main/kotlin/com/filamentvision/backend/api/dto/ErrorDtos.kt`
- Create: `backend/src/main/kotlin/com/filamentvision/backend/api/dto/DtoMappers.kt`
- Create: `backend/src/main/kotlin/com/filamentvision/backend/api/Routes.kt`
- Create: `backend/src/test/kotlin/com/filamentvision/backend/api/RestContractTest.kt`
- Create: `backend/src/test/kotlin/com/filamentvision/backend/api/HistoryApiTest.kt`

**Interfaces:**
- Implements the `/api/v1` table in the design spec.
- DTO mapping converts absent camera measurements to JSON `null`, never NaN.
- Route handlers call application/runtime/repository services only.

- [ ] **Step 1: Write failing Ktor route tests**

Use `testApplication` to assert state, idempotent start/stop, latest measurement, paged sessions/history/alarms, configuration Save/Apply/Save-and-Apply, cameras, diagnostics, errors and snapshot metadata. Assert stable error body fields and `correlationId`.

- [ ] **Step 2: Add JSON safety tests**

Serialize a single-camera measurement and assert `diameterA` or `diameterB` is `null`; assert the response contains no `NaN`, base64 pixel field or SQL identifier.

- [ ] **Step 3: Run RED**

```powershell
.\gradlew.bat :backend:test --tests "com.filamentvision.backend.api.RestContractTest" --tests "com.filamentvision.backend.api.HistoryApiTest"
```

- [ ] **Step 4: Implement DTOs/mappers first, then thin routes**

All domain-to-JSON conversions stay under `backend/api/dto`. `Routes.kt` validates request shape and delegates; it contains no processing, pairing or JDBC code.

- [ ] **Step 5: Run GREEN and Core firewall**

```powershell
.\gradlew.bat :backend:test --tests "com.filamentvision.backend.api.*" :core:verifyCorePlatformBoundary
```

- [ ] **Step 6: Commit**

```powershell
git add backend/src/main/kotlin/com/filamentvision/backend/api backend/src/test/kotlin/com/filamentvision/backend/api
git commit -m "feat: expose phase two REST contracts"
```

**Rollback boundary:** Remove API adapters; runtime and persistence remain headless/testable.

---

### Task 12: Expose reliable event and bounded preview WebSockets

**Files:**
- Create: `backend/src/main/kotlin/com/filamentvision/backend/api/EventWebSocket.kt`
- Create: `backend/src/main/kotlin/com/filamentvision/backend/api/PreviewWebSocket.kt`
- Create: `backend/src/main/kotlin/com/filamentvision/backend/api/dto/EventDtos.kt`
- Create: `backend/src/main/kotlin/com/filamentvision/backend/preview/PreviewEncoder.kt`
- Create: `backend/src/test/kotlin/com/filamentvision/backend/api/EventWebSocketTest.kt`
- Create: `backend/src/test/kotlin/com/filamentvision/backend/api/PreviewWebSocketBackpressureTest.kt`

**Interfaces:**
- `/api/v1/events` sends typed JSON with discriminator, occurredAt and monotonic sequence.
- `/api/v1/previews` sends typed metadata plus rate-limited binary JPEG/PNG adapter output.
- Preview encoder accepts Core diagnostic pixels and returns backend bytes; it is absent from Core.

- [ ] **Step 1: Write failing event WebSocket tests**

Assert initial `state.snapshot`, ordered session/measurement/alarm events, resume after sequence, expired-replay snapshot fallback and multiple clients not changing producer count.

- [ ] **Step 2: Write failing preview pressure tests**

Connect a deliberately slow preview client, publish 1,000 frames, and assert bounded pending count, newest camera ID/timestamp/source sequence delivered, old previews dropped and all reliable events still received by `/events`.

- [ ] **Step 3: Run RED**

```powershell
.\gradlew.bat :backend:test --tests "*EventWebSocketTest" --tests "*PreviewWebSocketBackpressureTest"
```

- [ ] **Step 4: Implement separate WebSocket routes and encoder adapter**

Use independent coroutine jobs/channels. Configure preview channel capacity to one pending item per subscribed camera/stage, with drop-oldest/conflation. Reliable events are not sent through that channel and are resumable by sequence/state snapshot.

- [ ] **Step 5: Run GREEN under multiple clients**

```powershell
.\gradlew.bat :backend:test --tests "*WebSocket*Test"
```

Expected: producer count remains 1 while client count changes; camera metadata survives both streams.

- [ ] **Step 6: Commit**

```powershell
git add backend/src/main/kotlin/com/filamentvision/backend/api backend/src/main/kotlin/com/filamentvision/backend/preview backend/src/test/kotlin/com/filamentvision/backend/api
git commit -m "feat: publish reliable events and bounded previews"
```

**Rollback boundary:** REST/backend runtime remain functional if WebSocket adapters are reverted.

---

### Task 13: Compose the backend and prove the complete simulated pipeline

**Files:**
- Create: `backend/src/main/kotlin/com/filamentvision/backend/BackendApplication.kt`
- Create: `backend/src/main/kotlin/com/filamentvision/backend/BackendComposition.kt`
- Create: `backend/src/main/kotlin/com/filamentvision/backend/DiagnosticClient.kt`
- Create: `backend/src/test/kotlin/com/filamentvision/backend/e2e/SimulatedMonitoringE2eTest.kt`
- Create: `backend/src/test/kotlin/com/filamentvision/backend/e2e/BackendRestartHistoryTest.kt`
- Create: `backend/src/test/kotlin/com/filamentvision/backend/e2e/LongRunBoundednessTest.kt`

**Interfaces:**
- Composition creates one device runtime for `simulator-1`, one SQLite adapter set and separate event/preview hubs.
- Diagnostic client submits start/stop/scenario commands and prints REST/event results; it does not own a producer.

- [ ] **Step 1: Write the failing image-to-observable-output E2E test**

The test must exercise:

```text
Simulator A/B CameraFrame
-> Core Vision stages/detection
-> injected pairing strategy
-> Fusion
-> Alarm + Statistics
-> runtime buffers
-> SQLite transaction
-> GET latest/history
-> WebSocket measurement/alarm/session events
```

Assert A/B/fused diameters, source timestamps/sequences, alarm transition, sample count, persisted row count and monotonic event sequence from one monitoring session.

- [ ] **Step 2: Write restart/history and single-owner tests**

Close the first server/runtime, reopen using the same temporary SQLite file, and assert finalized history is queryable with no active producer. Start a new runtime/session and assert exactly one producer and a new session ID.

- [ ] **Step 3: Write bounded long-run test**

Run virtual-time dual-camera simulation for at least eight virtual hours. Assert realtime buffer ≤300, preview pending ≤configured capacity, event replay ≤configured capacity, snapshot bounds, command-id cache bounds, no list proportional to total frames, and accepted=persisted after stop.

- [ ] **Step 4: Run RED, compose adapters, then run GREEN**

```powershell
.\gradlew.bat :backend:test --tests "com.filamentvision.backend.e2e.*"
```

- [ ] **Step 5: Run the diagnostic client against a local server**

```powershell
.\gradlew.bat :backend:run --args="--database build/phase2-smoke.db --scenario NORMAL"
```

In a second terminal:

```powershell
.\gradlew.bat :backend:runDiagnosticClient --args="--base-url http://127.0.0.1:8080 --device simulator-1 --start --observe-seconds 30 --stop --history"
```

Expected: one session, producer 1 only while running, A/B/fused output, sequenced events, stop flush and persisted history.

- [ ] **Step 6: Commit**

```powershell
git add backend/src/main/kotlin/com/filamentvision/backend backend/src/test/kotlin/com/filamentvision/backend/e2e
git commit -m "feat: complete simulated backend monitoring pipeline"
```

**Rollback boundary:** Composition and executable can be reverted while lower-level Core/backend adapters retain their independently tested value.

---

### Task 14: Switch Android algorithm consumers only after equivalence is proven

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `app/src/main/java/com/filamentvision/vision/processing/VisionProcessor.kt`
- Modify: `app/src/main/java/com/filamentvision/vision/fusion/FusionEngine.kt`
- Modify: `app/src/main/java/com/filamentvision/domain/alarm/AlarmEvaluator.kt`
- Modify: `app/src/main/java/com/filamentvision/domain/session/SessionStatisticsAccumulator.kt`
- Create: `app/src/main/java/com/filamentvision/coreadapter/AndroidCoreMappers.kt`
- Modify: affected Android imports/call sites identified by `rg "VisionProcessor|FusionEngine|AlarmEvaluator|SessionStatisticsAccumulator" app/src/main`.
- Keep: characterization/equivalence tests until Phase 2 report is accepted.

**Interfaces:**
- Android adapter converts current Android models to/from Core domain types.
- No Android type enters Core.

- [ ] **Step 1: Record pre-switch test results and source hashes**

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
Get-FileHash app\src\main\java\com\filamentvision\vision\processing\VisionProcessor.kt,app\src\main\java\com\filamentvision\vision\fusion\FusionEngine.kt,app\src\main\java\com\filamentvision\domain\alarm\AlarmEvaluator.kt,app\src\main\java\com\filamentvision\domain\session\SessionStatisticsAccumulator.kt
```

- [ ] **Step 2: Add adapter tests before switching production call sites**

Assert round-trip camera ID/timestamp/sequence, null/NaN compatibility at the legacy boundary and exact calibration/profile mapping.

- [ ] **Step 3: Replace implementations with thin Core delegates one class at a time**

After each of Vision, Fusion, Alarm and Statistics, run its existing tests plus all characterization tests. Do not switch Android `MonitoringRuntime`, Room, Service or Compose ownership in this task.

- [ ] **Step 4: Remove duplicate algorithm bodies only after every comparison passes**

Keep compatibility class names if they minimize Android churn; their bodies must delegate to Core rather than retain a second algorithm implementation.

- [ ] **Step 5: Run full Android/Core/backend verification**

```powershell
.\gradlew.bat :core:test :backend:test :app:testDebugUnitTest :app:assembleDebug :core:verifyCorePlatformBoundary
```

- [ ] **Step 6: Commit the switch as its own revertible checkpoint**

```powershell
git add app
git commit -m "refactor: route Android algorithms through shared core"
```

**Rollback boundary:** Revert only this commit to restore Android’s legacy algorithm bodies; the backend continues using Core. This is deliberately separate from extraction.

---

### Task 15: Final verification, report and Phase 3 gate

**Files:**
- Create: `docs/web-migration/17-phase2-implementation-report.md`
- Modify: `docs/web-migration/README.md`

**Interfaces:**
- Consumes: every Task 1–14 test/build artifact and recorded metric.
- Produces: auditable Phase 2 outcome and explicit Phase 3 readiness decision.

- [ ] **Step 1: Run all automated gates from a clean Gradle invocation**

```powershell
.\gradlew.bat --stop
.\gradlew.bat clean :core:test :core:verifyCorePlatformBoundary :backend:test :app:testDebugUnitTest :app:assembleDebug
```

Expected: all pass with exact counts recorded. A network/dependency-resolution failure is an environment failure and must not be reported as a code pass.

- [ ] **Step 2: Repeat concurrency and E2E suites**

```powershell
1..20 | ForEach-Object { .\gradlew.bat :core:test --tests "com.filamentvision.core.runtime.*" :backend:test --tests "com.filamentvision.backend.e2e.*"; if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE } }
```

- [ ] **Step 3: Run sustained wall-clock simulator verification**

Run the backend for at least 30 minutes with dual-camera Normal, mismatch, dropout and recovery scenario transitions. Sample process heap, producer count, queue sizes, replay count, snapshot bytes, accepted/persisted rows and event sequence at fixed intervals. Success requires no continuously growing bounded structure, no second producer and no lost accepted measurement after stop.

- [ ] **Step 4: Write the implementation report with evidence**

Report:

- modules/files and responsibility boundaries;
- reuse/extraction decisions and Android-only code intentionally retained;
- characterization before/after evidence;
- exact build/test commands, counts and failures;
- concurrency repetitions and maximum producer count;
- sustained-run duration, memory/queue/buffer/latency/rate observations;
- SQLite restart/history evidence;
- REST/WebSocket contract results;
- known uncertainties and every Raspberry Pi assumption;
- all remaining `DESIGN DECISION REQUIRED` items.

- [ ] **Step 5: Apply the Phase 3 gate**

Phase 3 is READY only if all are true:

1. headless image-level simulator reaches Vision → Fusion → Alarm/Statistics → SQLite → REST/WebSocket without Android UI;
2. Core firewall passes and Core contains no platform/image/network/database framework dependency;
3. characterization and side-by-side equivalence tests pass;
4. concurrent/repeated start, stop-during-start, failure, close and restart never exceed one producer and always finalize once;
5. preview pressure drops only previews and retains camera ID/timestamp/sequence;
6. reliable measurement/alarm/session events remain complete and ordered;
7. SQLite can be replaced through repository contracts without runtime/API changes;
8. persisted history survives backend restart;
9. sustained run shows bounded memory structures and complete final flush;
10. Android unit tests and `assembleDebug` pass.

If any item fails, report Phase 3 as NOT READY with the exact failing assertion and retain the last green task boundary.

- [ ] **Step 6: Commit documentation**

```powershell
git add docs/web-migration/17-phase2-implementation-report.md docs/web-migration/README.md
git commit -m "docs: report phase two backend foundation"
```

**Rollback boundary:** Documentation-only commit; never change evidence to turn a failed gate into READY.

## Review checkpoints

Implementation pauses for review after these checkpoints:

1. Task 1 behavior freeze;
2. Task 5 portable algorithms and equivalence evidence;
3. Task 7 runtime concurrency ownership;
4. Task 9 reliable-versus-preview backpressure split;
5. Task 13 complete headless E2E;
6. Task 14 Android consumer switch;
7. Task 15 Phase 3 gate.

No checkpoint authorizes Raspberry Pi protocol work, React UI work, Android deletion or a production deployment.
