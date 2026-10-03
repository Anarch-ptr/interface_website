# FilamentVision Single-Camera UDP Delta Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Migrate the already verified Task 1–27 implementation to one Raspberry Pi, one structured-chunked UDP image stream, one live preview, and Vision-derived diameter without weakening its safety, persistence, reliable-event, or producer-ownership guarantees.

**Architecture:** Preserve the proven receiver, unchanged 34-byte transport codec, structured reassembly, four image decoders, revision/CAS machinery, Inspector isolation, and reliable runtime paths. Replace only the dual-camera production boundaries with one `RaspberryPiUdpInputProfile`, one exact two-byte Datagram envelope, one receiver/source, a neutral measurement assembler, and single-stream Backend/frontend contracts. Keep generic dual-fusion, single-datagram, simulator, and synthetic-image capabilities only where they remain useful as tests or independent algorithms.

**Tech Stack:** Kotlin 2.2.10/JVM 17, kotlinx-coroutines 1.9.0, JUnit 4.13.2, Ktor 3.3.1, Jackson, SQLite JDBC 3.51.1, React 19, TypeScript 6, Vite 8, Vitest 4, Testing Library.

**Spec:** `docs/superpowers/specs/2026-10-03-single-camera-udp-design.md` at design commit `00b2da1adb31cbe5c6b7a5d9517bc69de792597c`.

## Global Constraints

- Start from baseline `a8c039ad51d426569e2d41e3cd3ba42999c94a6a`; this plan is a delta and must not replay or revert the original 27 tasks.
- Do not change the 34-byte Structured Transport Header length, offsets, integer widths, unsigned semantics, or Big Endian encoding. Offset 6 remains present and production accepts only compatibility value `1`.
- Production Datagram layout is exactly `[2-byte HEX Header][34-byte Structured Header][Image Chunk][2-byte HEX Footer]`; envelope validation and stripping precede structured parsing.
- Production supports only `JPEG`, `GRAY8`, `BINARY`, and `RGB888`, always through `STRUCTURED_CHUNKED`. `DIAMETER`, `SINGLE_DATAGRAM`, arbitrary/ASCII framing, scope, and strip controls are absent from production contracts.
- `:core` remains free of Android, Ktor, SQLite/JDBC, Room, Compose, Jackson, AWT, `ImageIO`, and desktop-only image APIs.
- `MonitoringRuntime` remains the sole producer owner; `activeProducerCount <= 1` and UDP receiver jobs `<= 1` are runtime invariants, not only test assertions.
- Preview and Inspector are bounded, latest-only observers. Their congestion must never drop an admitted measurement, alarm, session, error, or reliable event.
- Preserve existing Vision/Fusion/Alarm/Statistics golden expected values. A characterization mismatch is a stop condition, never a reason to rewrite expected values.
- Preserve Datagram byte ownership: borrowed receive-buffer views cannot cross an asynchronous or retained-state boundary. Reassembly owns bounded chunk bytes; Inspector owns only its bounded preview copy.
- Every task uses: RED test and observed expected failure; minimal GREEN; focused tests; named regression tests; `git diff --check`; `git diff --stat`; diff review; one independent commit. Do not start the next task until all checks pass.
- V001–V003 are immutable. Task 15 adds V004; any correction after its commit requires V005.
- If implementation evidence requires a wire change, a second physical stream, a direct-diameter stream, or weaker memory bounds, stop for a new design review.

## File and Boundary Map

- `backend/input/profile`: authoritative single-stream settings and validation.
- `backend/input/framing`: exact two-byte outer Datagram envelope only; generic old framing remains non-production.
- `backend/input/transport`: unchanged structured wire codec, one-source receive capacity, IPv4 binding/filtering.
- `backend/input/reassembly`: reuse structured bounded algorithm; production no longer instantiates single-Datagram reassembly.
- `backend/input/runtime`: one compiled processing chain, one receiver/source, activity-derived status, atomic Apply.
- `backend/input/decoder`: retain four image decoders and shared allocation policy; remove direct-diameter decoder from production wiring only.
- `core/measurement` and `core/runtime`: neutral measurement assembly and single Vision configuration; generic fusion stays independently tested.
- `backend/persistence/sqlite`: versioned profile codec, V004 tables, neutral `measurements_v2`, legacy union reads.
- `backend/api`: single-stream REST/WS DTOs and routes.
- `frontend`: Backend-backed single-stream settings, Inspector, preview and monitoring; Mock stays test-only.

Canonical cross-task interfaces (field-specific payload classes use the exact fields in the Design Amendment):

```kotlin
data class RaspberryPiUdpInputProfile(
    val enabled: Boolean,
    val networkInterface: NetworkInterfaceSelection,
    val listenPort: Int,
    val raspberryPiIpv4: String?,
    val payload: ImagePayloadProfile,
    val envelope: DatagramEnvelopeProfile,
    val transportLimits: TransportLimits,
)

data class DatagramEnvelopeProfile(val headerHex: String, val footerHex: String)

interface MeasurementAssembler {
    fun reset()
    fun accept(evidence: CameraMeasurement): List<OfficialMeasurement>
}

interface Ipv4NetworkInterfaceProvider {
    fun listEligible(): List<Ipv4NetworkInterfaceEntry>
}

interface RaspberryPiUdpInputProfileRepository {
    suspend fun save(expectedSavedRevision: Long, profile: RaspberryPiUdpInputProfile): ProfileRevision
    suspend fun markApplied(expectedAppliedRevision: Long, revision: Long): Boolean
    suspend fun state(): RaspberryPiUdpProfileState
}
```

---

### Task 1: Freeze the current migration behavior as characterization tests

**Files:**
- Create: `backend/src/test/kotlin/com/filamentvision/backend/migration/SingleCameraMigrationCharacterizationTest.kt`
- Create: `core/src/test/kotlin/com/filamentvision/core/measurement/SingleCameraBaselineCharacterizationTest.kt`
- Modify: `frontend/src/App.test.tsx`

**Interfaces:**
- Consumes: current dual profile, transport codec, Vision result, preview, persistence, and production composition at baseline.
- Produces: named assertions that later tasks must preserve or intentionally replace according to the Amendment.

- [ ] Add tests that record the current 34-byte fixture bytes and offsets, unsigned `framePayloadBytes`, decoder outputs for all four image formats, Vision diameter/confidence for a fixed image, Runtime producer maximum, Preview latest-only behavior, profile saved/applied separation, and legacy measurement-history mapping. Add frontend assertions locating every dual/fake production label that later tasks must remove.
- [ ] Run `./gradlew.bat :core:test --tests "com.filamentvision.core.measurement.SingleCameraBaselineCharacterizationTest"` and `./gradlew.bat :backend:test --tests "com.filamentvision.backend.migration.SingleCameraMigrationCharacterizationTest"`. RED must be only missing new test fixtures/assertion helpers, not a changed golden value.
- [ ] Add only deterministic fixture builders/test helpers. Do not modify production code or golden outputs.
- [ ] Re-run both focused commands; then run `./gradlew.bat :core:test --tests "com.filamentvision.core.vision.VisionGoldenTest" --tests "com.filamentvision.core.fusion.FusionGoldenTest"` and `./gradlew.bat :backend:test --tests "com.filamentvision.backend.e2e.UdpInputEndToEndTest" --tests "com.filamentvision.backend.persistence.sqlite.SqliteRepositoryContractTest"`; run `npm --prefix frontend test -- src/App.test.tsx`.
- [ ] Run `git diff --check`, inspect `git diff --stat` and the full diff, then commit `test(migration): characterize single-camera baseline`.

**Done / rollback:** All retained behavior has a stable baseline and obsolete UI assertions are explicitly labelled migration targets. This test-only commit can be reverted without runtime effect.

### Task 2: Define the authoritative single UDP profile and validator

**Files:**
- Create: `backend/src/main/kotlin/com/filamentvision/backend/input/profile/RaspberryPiUdpInputProfile.kt`
- Create: `backend/src/main/kotlin/com/filamentvision/backend/input/profile/RaspberryPiUdpInputProfileValidator.kt`
- Create: `backend/src/test/kotlin/com/filamentvision/backend/input/profile/RaspberryPiUdpInputProfileValidatorTest.kt`

**Interfaces:**
- Produces: `RaspberryPiUdpInputProfile`, `NetworkInterfaceSelection`, `ImagePayloadProfile`, `DatagramEnvelopeProfile`, `TransportLimits`, `normalizeTwoByteHex(String): String`, and field-path validation errors.

- [ ] Test disabled JPEG defaults, `AUTO`/specific interface selection, optional IPv4 sender, port bounds, four image formats and conditional fields, JPEG 16 MiB default, transport 32 MiB hard max, timeout/in-flight/chunk limits, `maxChunkCount=32768`, allowed maximum 65535, and rejection of DIAMETER/single-Datagram concepts by absence from the new model.
- [ ] Test exact HEX normalization: `AA 01`, `AA01`, `aa01` become `AA 01`; empty, one/three bytes, odd digits, `GG 01`, and non-HEX fail on precise `envelope.headerHex`/`footerHex` paths.
- [ ] Run `./gradlew.bat :backend:test --tests "com.filamentvision.backend.input.profile.RaspberryPiUdpInputProfileValidatorTest"`; expect compile failure because the new types do not exist.
- [ ] Implement immutable types/default factory and checked validation by delegating existing image/allocation bounds where possible. Do not delete `UdpInputProfile` yet.
- [ ] Run the focused command; then `./gradlew.bat :backend:test --tests "com.filamentvision.backend.input.profile.UdpInputProfileValidatorTest" --tests "com.filamentvision.backend.input.decoder.DecodedAllocationPolicyTest"`.
- [ ] Run diff checks/review and commit `feat(udp): define single Raspberry Pi input profile`.

**Done / rollback:** A complete single-stream model exists beside the old model with no consumers changed. Reverting removes only the new model/tests.

### Task 3: Parse the exact two-byte Datagram envelope

**Files:**
- Create: `backend/src/main/kotlin/com/filamentvision/backend/input/framing/DatagramEnvelopeParser.kt`
- Create: `backend/src/test/kotlin/com/filamentvision/backend/input/framing/DatagramEnvelopeParserTest.kt`

**Interfaces:**
- Consumes: compiled owned two-byte header/footer from Task 2 and `BorrowedDatagramBytes`.
- Produces: `EnvelopeParseResult.Accepted(structuredBytes, matchedHeader, matchedFooter)` or typed header/footer/size rejection; returned borrowed slice is synchronous-only.

- [ ] Test exact valid layout, minimum size 38, header mismatch precedence, footer mismatch, no payload allocation, and two sequential receives reusing the same backing array while any retained metadata remains unchanged.
- [ ] Run `./gradlew.bat :backend:test --tests "com.filamentvision.backend.input.framing.DatagramEnvelopeParserTest"`; expect missing parser/types.
- [ ] Implement constant-time boundary comparison and stripped borrowed view. Compile HEX once outside `parse`; never build Hex strings in the parser.
- [ ] Run focused tests; then `./gradlew.bat :backend:test --tests "com.filamentvision.backend.input.framing.ApplicationFramingParserTest" --tests "com.filamentvision.backend.input.transport.UdpReceiveBufferOwnershipTest"`.
- [ ] Run diff checks/review and commit `feat(udp): parse fixed Datagram envelope`.

**Done / rollback:** Envelope errors are distinguishable and cannot reach structured parsing. Revert leaves the existing generic framing path untouched.

### Task 4: Account for envelope bytes in receive capacity and sentinel detection

**Files:**
- Modify: `backend/src/main/kotlin/com/filamentvision/backend/input/transport/UdpReceiveCapacity.kt`
- Modify: `backend/src/main/kotlin/com/filamentvision/backend/input/transport/UdpReceiver.kt`
- Modify: `backend/src/test/kotlin/com/filamentvision/backend/input/transport/UdpReceiveCapacityTest.kt`
- Modify: `backend/src/test/kotlin/com/filamentvision/backend/input/transport/UdpReceiverTest.kt`

**Interfaces:**
- Produces: production accepted length `2 + 34 + maxChunkPayloadBytes + 2` and a sentinel capacity one byte larger where IPv4 permits.

- [ ] Add tests for the maximum legal envelope+structured+chunk Datagram, exact oversize sentinel rejection, UDP IPv4 payload ceiling, explicit `DATAGRAM_TOO_LARGE_OR_TRUNCATED`, and no parser call after truncation.
- [ ] Run `./gradlew.bat :backend:test --tests "com.filamentvision.backend.input.transport.UdpReceiveCapacityTest" --tests "com.filamentvision.backend.input.transport.UdpReceiverTest"` and observe failures because capacity excludes four envelope bytes.
- [ ] Make capacity accept explicit envelope bytes and preserve old overload behavior for characterization. Do not change socket lifecycle or reassembly.
- [ ] Run focused tests; then `./gradlew.bat :backend:test --tests "com.filamentvision.backend.input.transport.UdpReceiveBufferOwnershipTest" --tests "com.filamentvision.backend.e2e.UdpInputEndToEndTest"`.
- [ ] Run diff checks/review and commit `fix(udp): include envelope in receive bounds`.

**Done / rollback:** A maximum legal Datagram is not truncated and larger input has one explicit diagnostic. Revert restores prior capacity independently.

### Task 5: Preserve the 34-byte codec with one compatibility stream identity

**Files:**
- Modify: `backend/src/main/kotlin/com/filamentvision/backend/input/transport/StructuredTransportHeader.kt`
- Modify: `backend/src/main/kotlin/com/filamentvision/backend/input/transport/TransportDatagramParser.kt`
- Modify: `backend/src/test/kotlin/com/filamentvision/backend/input/transport/TransportDatagramParserTest.kt`
- Modify: `backend/src/test/kotlin/com/filamentvision/backend/migration/SingleCameraMigrationCharacterizationTest.kt`

**Interfaces:**
- Produces: unchanged 34-byte parsing with `compatibilityStreamId: UByte`; production policy accepts only value `1` without changing encoded bytes.

- [ ] Add byte-for-byte old/new fixture equality, total length 34, all exact offsets, value 1 acceptance, 2/255 rejection, unsigned `0x80000000`/`0xFFFFFFFF` length rejection by limits, and unchanged timestamp/payload fields.
- [ ] Run `./gradlew.bat :backend:test --tests "com.filamentvision.backend.input.transport.TransportDatagramParserTest" --tests "com.filamentvision.backend.migration.SingleCameraMigrationCharacterizationTest"`; RED must show only missing neutral compatibility naming/policy.
- [ ] Add a naming adapter/policy around the existing byte. Do not move a field or change serializer output.
- [ ] Run focused tests; then `./gradlew.bat :backend:test --tests "com.filamentvision.backend.input.reassembly.StructuredChunkFrameReassemblerOrderingTest" --tests "com.filamentvision.backend.input.reassembly.StructuredChunkFrameReassemblerLimitsTest"`.
- [ ] Run diff checks/review and commit `refactor(udp): reserve structured stream byte`.

**Done / rollback:** Every legacy stream-1 wire fixture remains byte-identical. Any fixture difference blocks the task.

### Task 6: Enumerate and resolve local IPv4 interfaces

**Files:**
- Create: `backend/src/main/kotlin/com/filamentvision/backend/input/network/Ipv4NetworkInterfaceProvider.kt`
- Create: `backend/src/main/kotlin/com/filamentvision/backend/input/network/BackendIpv4Resolver.kt`
- Create: `backend/src/test/kotlin/com/filamentvision/backend/input/network/Ipv4NetworkInterfaceProviderTest.kt`
- Create: `backend/src/test/kotlin/com/filamentvision/backend/input/network/BackendIpv4ResolverTest.kt`

**Interfaces:**
- Produces: immutable `Ipv4NetworkInterfaceEntry`; `listEligible()`; `resolve(selection, raspberryPiIpv4): BackendIpv4Resolution`.

- [ ] Test up/non-loopback IPv4 filtering, visible VPN/Wi-Fi/Ethernet labels, deterministic AUTO order, SPECIFIC ownership validation, route-probe selection without sending a packet, multiple-candidate flag, no-interface result, and exclusion of IPv6.
- [ ] Run `./gradlew.bat :backend:test --tests "com.filamentvision.backend.input.network.Ipv4NetworkInterfaceProviderTest" --tests "com.filamentvision.backend.input.network.BackendIpv4ResolverTest"`; expect missing adapters.
- [ ] Implement a platform interface plus `java.net.NetworkInterface` adapter and injectable route probe so tests never depend on host adapters.
- [ ] Run focused tests; then `./gradlew.bat :backend:test --tests "com.filamentvision.backend.input.profile.RaspberryPiUdpInputProfileValidatorTest" --tests "com.filamentvision.backend.input.transport.UdpReceiverTest"`.
- [ ] Run diff checks/review and commit `feat(network): resolve UDP IPv4 interface`.

**Done / rollback:** `0.0.0.0` remains internal and is never returned as the advertised target. Revert is isolated from sockets.

### Task 7: Compile the one-stream envelope-to-decoder processor

**Files:**
- Create: `backend/src/main/kotlin/com/filamentvision/backend/input/runtime/CompiledRaspberryPiInputProfile.kt`
- Create: `backend/src/main/kotlin/com/filamentvision/backend/input/runtime/RaspberryPiUdpProcessor.kt`
- Create: `backend/src/test/kotlin/com/filamentvision/backend/input/runtime/RaspberryPiUdpProcessorTest.kt`

**Interfaces:**
- Consumes: Tasks 2–5, existing `StructuredChunkFrameReassembler`, and four existing image decoders.
- Produces: synchronous `process(BorrowedDatagramBytes, ReceivedDatagramMetadata): ProcessorResult`; completed output is `CameraInput.Image` only.

- [ ] Test strict order envelope→structured parser→reassembly→decoder, multi-chunk JPEG/GRAY8/BINARY/RGB888, out-of-order/duplicate/conflicting/missing/timeout handling, header/footer short-circuit, fixed stream value, owned chunk bytes across receive-buffer reuse, and absence of direct diameter output.
- [ ] Run `./gradlew.bat :backend:test --tests "com.filamentvision.backend.input.runtime.RaspberryPiUdpProcessorTest"`; expect missing processor/compiled profile.
- [ ] Compose existing algorithms with one reassembler and selected image decoder. Copy only the bounded chunk bytes at the reassembly ownership boundary; do not copy a full Datagram twice.
- [ ] Run focused tests; then run `./gradlew.bat :backend:test --tests "com.filamentvision.backend.input.reassembly.StructuredChunkFrameReassemblerOrderingTest" --tests "com.filamentvision.backend.input.reassembly.StructuredChunkFrameReassemblerLimitsTest" --tests "com.filamentvision.backend.input.decoder.JpegDecoderTest" --tests "com.filamentvision.backend.input.decoder.Gray8DecoderTest" --tests "com.filamentvision.backend.input.decoder.BinaryDecoderTest" --tests "com.filamentvision.backend.input.decoder.Rgb888DecoderTest"`.
- [ ] Run diff checks/review and commit `feat(udp): process one structured image stream`.

**Done / rollback:** All production formats reach `CameraInput.Image`; generic old processor remains until consumers migrate.

### Task 8: Implement one receiver source and activity-derived UDP status

**Files:**
- Create: `backend/src/main/kotlin/com/filamentvision/backend/input/runtime/RaspberryPiUdpInputSource.kt`
- Create: `backend/src/main/kotlin/com/filamentvision/backend/input/runtime/UdpOperationalStatus.kt`
- Create: `backend/src/test/kotlin/com/filamentvision/backend/input/runtime/RaspberryPiUdpInputSourceTest.kt`
- Modify: `backend/src/main/kotlin/com/filamentvision/backend/input/diagnostics/UdpDiagnostics.kt`
- Modify: `backend/src/test/kotlin/com/filamentvision/backend/input/diagnostics/UdpDiagnosticsTest.kt`

**Interfaces:**
- Produces: one `CameraInputSource`, one receiver job, capacity-one latest mailbox, diagnostics snapshot, and statuses `DISABLED`, `UDP_READY`, `WAITING_FOR_RASPBERRY_PI`, `RECEIVING`, `NO_PACKETS_RECENTLY`, `SOURCE_IP_MISMATCH`, `BIND_FAILED`.

- [ ] With fake monotonic clock/socket, test 2-second initial grace, 3-second recent threshold, accepted/mismatch precedence, safe bind error codes, source filtering before parsing, Inspector-independent normal rejection, one active job under repeated/concurrent start, stop cleanup, and replaced-frame close/count before admission.
- [ ] Run `./gradlew.bat :backend:test --tests "com.filamentvision.backend.input.runtime.RaspberryPiUdpInputSourceTest" --tests "com.filamentvision.backend.input.diagnostics.UdpDiagnosticsTest"`; expect missing source/status behavior.
- [ ] Implement atomic timestamps/counters and derived snapshot without history. One lifecycle reservation owns the socket job; restart joins the old job before replacement.
- [ ] Run focused tests; then `./gradlew.bat :backend:test --tests "com.filamentvision.backend.input.transport.UdpReceiveBufferOwnershipTest" --tests "com.filamentvision.backend.input.transport.UdpReceiverTest"`; separately run `./gradlew.bat :core:test --tests "com.filamentvision.core.runtime.MonitoringRuntimeConcurrencyTest" --tests "com.filamentvision.core.runtime.MonitoringRuntimeFailureTest"`.
- [ ] Run diff checks/review and commit `feat(udp): own one Raspberry Pi receiver`.

**Done / rollback:** Producer and receiver counts never exceed one, and bind is never reported as Connected.

### Task 9: Gate full-frame decoding with one owned permit

**Files:**
- Create: `backend/src/main/kotlin/com/filamentvision/backend/input/runtime/DecodedFramePermit.kt`
- Create: `backend/src/test/kotlin/com/filamentvision/backend/input/runtime/DecodedFramePermitTest.kt`
- Modify: `backend/src/main/kotlin/com/filamentvision/backend/input/runtime/RaspberryPiUdpProcessor.kt`
- Modify: `backend/src/test/kotlin/com/filamentvision/backend/input/runtime/RaspberryPiUdpProcessorTest.kt`

**Interfaces:**
- Produces: `tryAcquire(): DecodedFrameLease?`; the lease is transferred into `CameraFrame.close()` and releases exactly once.

- [ ] Test one concurrent permit, drop-before-decode while occupied, counter increment, success/drop/cancellation/stop release, and no double-release. Include 3840x2160 declarations and the existing 8192/16,777,216-pixel/64-MiB hard edges.
- [ ] Run `./gradlew.bat :backend:test --tests "com.filamentvision.backend.input.runtime.DecodedFramePermitTest" --tests "com.filamentvision.backend.input.runtime.RaspberryPiUdpProcessorTest"`; expect missing permit and pre-decode admission.
- [ ] Add an atomic single permit immediately before decoder allocation and attach release to the frame lease. Do not raise allocation limits.
- [ ] Run focused tests; then `./gradlew.bat :backend:test --tests "com.filamentvision.backend.input.decoder.DecodedAllocationPolicyTest" --tests "com.filamentvision.backend.input.decoder.JpegDecoderTest" --tests "com.filamentvision.backend.input.decoder.Gray8DecoderTest" --tests "com.filamentvision.backend.input.decoder.BinaryDecoderTest" --tests "com.filamentvision.backend.input.decoder.Rgb888DecoderTest"`.
- [ ] Run diff checks/review and commit `feat(udp): bound decoded frame concurrency`.

**Done / rollback:** A slow Vision consumer cannot accumulate decoded 8MP frames. Revert restores prior processor only.

### Task 10: Downsample one preview without a full-frame copy

**Files:**
- Create: `backend/src/main/kotlin/com/filamentvision/backend/preview/PreviewFrameDownsampler.kt`
- Create: `backend/src/test/kotlin/com/filamentvision/backend/preview/PreviewFrameDownsamplerTest.kt`
- Modify: `backend/src/main/kotlin/com/filamentvision/backend/preview/PreviewEncoder.kt`
- Modify: `backend/src/test/kotlin/com/filamentvision/backend/api/PreviewWebSocketBackpressureTest.kt`

**Interfaces:**
- Produces: nearest-neighbour ARGB preview no larger than 1280×720 and 3,686,400 raw bytes before encoding; one `PreviewKey(deviceId, "camera", RAW)` under a 4 MiB Hub cap.

- [ ] Test aspect-ratio sizing, checked arithmetic, pixel correspondence, a deterministic 3840×2160 source, allocation never equal to full 8MP ARGB, FPS admission before encode, one retained key/frame, replacement release, and reliable-event independence during preview congestion.
- [ ] Run `./gradlew.bat :backend:test --tests "com.filamentvision.backend.preview.PreviewFrameDownsamplerTest" --tests "com.filamentvision.backend.api.PreviewWebSocketBackpressureTest"`; expect missing downsampler/new cap behavior.
- [ ] Sample directly from `PixelBuffer` into the bounded preview output and configure Hub cap to 4 MiB. Do not allocate an intermediate full-resolution ARGB array.
- [ ] Run focused tests; then `./gradlew.bat :core:test --tests "com.filamentvision.core.preview.LatestPreviewHubBackpressureTest"`; separately run `./gradlew.bat :backend:test --tests "com.filamentvision.backend.api.EventWebSocketTest"`.
- [ ] Run diff checks/review and commit `perf(preview): bound single-camera preview memory`.

**Done / rollback:** Preview retention is one bounded frame and cannot block reliable delivery.

### Task 11: Bound production Vision processing to the configured ROI

**Files:**
- Create: `core/src/main/kotlin/com/filamentvision/core/vision/VisionAnalyzer.kt`
- Create: `core/src/main/kotlin/com/filamentvision/core/vision/RoiVisionProcessor.kt`
- Create: `core/src/test/kotlin/com/filamentvision/core/vision/RoiVisionProcessorTest.kt`
- Modify: `core/src/main/kotlin/com/filamentvision/core/vision/VisionProcessor.kt`
- Modify: `core/src/test/kotlin/com/filamentvision/core/vision/VisionGoldenTest.kt`

**Interfaces:**
- Produces: `VisionAnalyzer.process(frame, configuration, decodeNanos): VisionResult`; production `RoiVisionProcessor` allocates geometry/grayscale/threshold/edge working arrays only for the configured ROI and translates detected coordinates back to source space.

- [ ] Add a deterministic 3840×2160 frame test with a 640×480 ROI and an allocation observer asserting each retained working buffer has at most `roiWidth * roiHeight` elements, checked arithmetic rejects invalid transformed ROIs before allocation, and the diameter/confidence/error/edge coordinates match the existing `VisionProcessor` on the same pixels. Add rotation/mirror and full-frame-small-fixture equivalence cases.
- [ ] Run `./gradlew.bat :core:test --tests "com.filamentvision.core.vision.RoiVisionProcessorTest" --tests "com.filamentvision.core.vision.VisionGoldenTest"`; expect missing analyzer/ROI processor while existing golden remains green.
- [ ] Introduce the analyzer interface, extract only geometry-mapped ROI pixels into a compact stage, localize calibration for existing grayscale/threshold/edge/detection modules, then translate result metadata back. Keep `VisionProcessor` as the characterization implementation; do not change golden expected values.
- [ ] Re-run the focused command; then run `./gradlew.bat :core:test --tests "com.filamentvision.core.vision.GrayscaleProcessorTest" --tests "com.filamentvision.core.vision.ImageGeometryTransformerTest" --tests "com.filamentvision.core.alarm.AlarmGoldenTest" --tests "com.filamentvision.core.session.StatisticsGoldenTest"`.
- [ ] Run diff checks/review and commit `perf(vision): bound processing to calibrated ROI`.

**Done / rollback:** Production can process an 8MP source without retaining four 8MP intermediate arrays, while fixed-input measurement output remains equivalent. Any golden mismatch blocks the task.

### Task 12: Add a neutral single-camera measurement assembler

**Files:**
- Create: `core/src/main/kotlin/com/filamentvision/core/measurement/MeasurementAssembler.kt`
- Create: `core/src/main/kotlin/com/filamentvision/core/measurement/SingleCameraMeasurementAssembler.kt`
- Create: `core/src/test/kotlin/com/filamentvision/core/measurement/SingleCameraMeasurementAssemblerTest.kt`
- Modify: `core/src/main/kotlin/com/filamentvision/core/model/MeasurementModels.kt`

**Interfaces:**
- Produces: `MeasurementAssembler.reset()` and `accept(CameraMeasurement): List<OfficialMeasurement>`; one valid `camera` evidence maps diameter/confidence/timestamp unchanged, invalid evidence emits none.

- [ ] Test exact single evidence mapping, wrong source rejection, invalid/no-edge suppression, reset, neutral public names, and no fabricated B/pixel/fusion metadata.
- [ ] Run `./gradlew.bat :core:test --tests "com.filamentvision.core.measurement.SingleCameraMeasurementAssemblerTest"`; expect missing interface/neutral model.
- [ ] Implement assembler and add neutral fields while retaining compatibility readers needed by old fusion tests.
- [ ] Run focused test; then `./gradlew.bat :core:test --tests "com.filamentvision.core.fusion.FusionCoordinatorTest" --tests "com.filamentvision.core.fusion.FusionGoldenTest" --tests "com.filamentvision.core.fusion.LatestCompatiblePairingStrategyTest" --tests "com.filamentvision.core.fusion.DirectDiameterMeasurementAdapterTest" --tests "com.filamentvision.core.alarm.AlarmGoldenTest" --tests "com.filamentvision.core.session.StatisticsGoldenTest"` without changing expected values.
- [ ] Run diff checks/review and commit `feat(core): assemble single-camera measurements`.

**Done / rollback:** Production can avoid fusion while generic fusion remains green and independent.

### Task 13: Make MonitoringRuntime consume one Vision stream

**Files:**
- Modify: `core/src/main/kotlin/com/filamentvision/core/config/MonitoringConfiguration.kt`
- Modify: `core/src/main/kotlin/com/filamentvision/core/runtime/MonitoringRuntime.kt`
- Modify: `core/src/main/kotlin/com/filamentvision/core/runtime/MonitoringState.kt`
- Modify: `core/src/test/kotlin/com/filamentvision/core/runtime/MonitoringRuntimeCameraInputTest.kt`
- Modify: `core/src/test/kotlin/com/filamentvision/core/runtime/MonitoringRuntimeConcurrencyTest.kt`
- Modify: `core/src/test/kotlin/com/filamentvision/core/runtime/MonitoringRuntimeFailureTest.kt`

**Interfaces:**
- Consumes: Task 11 `VisionAnalyzer`, Task 12 `MeasurementAssembler`, and one `VisionConfiguration`.
- Produces: neutral latest measurement/state while preserving start/stop/finalize/write/alarm/session behavior.

- [ ] Test one image→Vision→official measurement, invalid Vision suppression, start×2, concurrent start/stop, source exception/finalization/restart, `activeProducerCount <= 1`, flush on stop, and reliable alarm/session behavior. Assert no pairing timeout or Camera B state participates.
- [ ] Run `./gradlew.bat :core:test --tests "com.filamentvision.core.runtime.MonitoringRuntimeCameraInputTest" --tests "com.filamentvision.core.runtime.MonitoringRuntimeConcurrencyTest" --tests "com.filamentvision.core.runtime.MonitoringRuntimeFailureTest"`; RED must identify old fusion/dual configuration assumptions.
- [ ] Inject assembler, map one vision configuration, and retain the existing authoritative reservation/finalize pipelines. Do not remove generic fusion classes.
- [ ] Run focused classes; then `./gradlew.bat :core:test`.
- [ ] Run diff checks/review and commit `refactor(runtime): monitor one vision stream`.

**Done / rollback:** Runtime owns exactly one producer and produces no synthetic sample while the source is silent.

### Task 14: Version the configuration and UDP profile codecs

**Files:**
- Modify: `backend/src/main/kotlin/com/filamentvision/backend/persistence/sqlite/ConfigurationCodec.kt`
- Create: `backend/src/main/kotlin/com/filamentvision/backend/persistence/sqlite/RaspberryPiUdpInputProfileCodec.kt`
- Modify: `backend/src/test/kotlin/com/filamentvision/backend/persistence/sqlite/UdpInputProfileCodecTest.kt`
- Create: `backend/src/test/kotlin/com/filamentvision/backend/persistence/sqlite/RaspberryPiUdpInputProfileCodecTest.kt`

**Interfaces:**
- Produces: monitoring configuration codec v2 and Raspberry Pi UDP profile codec v1; old blobs decode unchanged and choose Camera A vision settings deterministically.

- [ ] Test all new fields/enums round-trip, canonical two-byte HEX, v1 monitoring blob→v2 domain mapping, corrupt/unknown version rejection, old bytes immutability, and no old DIAMETER/framing concept leaking into new encoded output.
- [ ] Run `./gradlew.bat :backend:test --tests "com.filamentvision.backend.persistence.sqlite.UdpInputProfileCodecTest" --tests "com.filamentvision.backend.persistence.sqlite.RaspberryPiUdpInputProfileCodecTest"`; expect missing new codec/version branch.
- [ ] Add explicit version tags and pure mapping. Keep the old codec for old table reads; never mutate stored blobs.
- [ ] Run focused tests; then `./gradlew.bat :core:test --tests "com.filamentvision.core.config.ConfigurationServiceTest"`; separately run `./gradlew.bat :backend:test --tests "com.filamentvision.backend.persistence.sqlite.InMemoryRepositoryContractTest" --tests "com.filamentvision.backend.persistence.sqlite.SqliteRepositoryContractTest"`.
- [ ] Run diff checks/review and commit `feat(sqlite): version single-camera codecs`.

**Done / rollback:** Old data can be read and new data has a stable independent codec. Revert precedes V004.

### Task 15: Add SQLite V004 and neutral measurement persistence

**Files:**
- Create: `backend/src/main/resources/db/migration/V004__single_camera_udp_and_measurements.sql`
- Create: `backend/src/main/kotlin/com/filamentvision/backend/persistence/sqlite/SqliteRaspberryPiUdpInputProfileRepository.kt`
- Modify: `backend/src/main/kotlin/com/filamentvision/backend/persistence/sqlite/SqliteDatabase.kt`
- Modify: `backend/src/main/kotlin/com/filamentvision/backend/persistence/sqlite/SqliteRepositories.kt`
- Modify: `backend/src/test/kotlin/com/filamentvision/backend/persistence/sqlite/SqliteRepositoryContractTest.kt`
- Modify: `backend/src/test/kotlin/com/filamentvision/backend/persistence/sqlite/RepositoryConformance.kt`

**Interfaces:**
- Produces: revision/state tables and `measurements_v2`; new writes target v2, reads union v2 with immutable legacy `measurements`.

- [ ] Test fresh V004, V001/V002/V003 upgrade, disabled seeded JPEG profile, safe carry-forward of compatible Camera A fields, default envelope for unsafe old framing, atomic saved/applied revisions, CAS, neutral write/read, legacy history union/order/cursor, and no fabricated B/fusion fields in v2.
- [ ] Run `./gradlew.bat :backend:test --tests "com.filamentvision.backend.persistence.sqlite.SqliteRepositoryContractTest"`; expect schema/table/repository failures.
- [ ] Add V004 transaction, repository adapter and union mapper. Never edit V001–V003 and never write new rows to legacy measurements.
- [ ] Run focused tests; then `./gradlew.bat :backend:test --tests "com.filamentvision.backend.persistence.sqlite.UdpInputProfileCodecTest" --tests "com.filamentvision.backend.persistence.sqlite.RaspberryPiUdpInputProfileCodecTest" --tests "com.filamentvision.backend.persistence.sqlite.CompositionLookupTest" --tests "com.filamentvision.backend.persistence.sqlite.InMemoryRepositoryContractTest" --tests "com.filamentvision.backend.persistence.sqlite.SqliteRepositoryContractTest"`; separately run `./gradlew.bat :core:test --tests "com.filamentvision.core.runtime.MonitoringRuntimeCameraInputTest" --tests "com.filamentvision.core.runtime.MonitoringRuntimeFailureTest"`.
- [ ] Run diff checks/review and commit `feat(sqlite): migrate single-camera state`.

**Done / rollback:** Fresh/upgraded DBs satisfy the same domain contract. After commit, V004 is immutable; rollback is allowed only before production migration.

### Task 16: Apply one profile atomically with rollback compensation

**Files:**
- Create: `backend/src/main/kotlin/com/filamentvision/backend/input/runtime/RaspberryPiUdpInputConfigurationService.kt`
- Create: `backend/src/main/kotlin/com/filamentvision/backend/input/runtime/RaspberryPiUdpInputRouter.kt`
- Create: `backend/src/test/kotlin/com/filamentvision/backend/input/runtime/RaspberryPiUdpInputConfigurationServiceTest.kt`
- Create: `backend/src/test/kotlin/com/filamentvision/backend/input/runtime/RaspberryPiUdpInputRouterTest.kt`

**Interfaces:**
- Produces: serialized `save`, `apply(revision)`, parser-only atomic swap, receiver restart, idempotence, and postcondition runtime profile = effective receiver profile = persisted applied revision.

- [ ] Test format/envelope/limit swap with old in-flight discard; enabled/interface/port/source restart; bind failure rollback; concurrent Save N+1 against Apply N; runtime success plus CAS false; persistence exception; successful compensation; compensation failure surfaced as fatal consistency error; repeated Apply no-op; no second receiver job.
- [ ] Run `./gradlew.bat :backend:test --tests "com.filamentvision.backend.input.runtime.RaspberryPiUdpInputConfigurationServiceTest" --tests "com.filamentvision.backend.input.runtime.RaspberryPiUdpInputRouterTest"`; expect missing single router/service.
- [ ] Implement a per-device serialization mutex, validate/prepare before swap, swap before `markApplied`, and compensate runtime/socket/profile on any final persistence failure.
- [ ] Run focused tests; then `./gradlew.bat :backend:test --tests "com.filamentvision.backend.input.runtime.UdpInputConfigurationServiceTest" --tests "com.filamentvision.backend.input.runtime.UdpInputRouterTest" --tests "com.filamentvision.backend.input.runtime.RaspberryPiUdpInputSourceTest" --tests "com.filamentvision.backend.persistence.sqlite.SqliteRepositoryContractTest"`.
- [ ] Run diff checks/review and commit `feat(udp): apply one profile atomically`.

**Done / rollback:** The three-way consistency invariant holds on every success/failure path and no second producer is created.

### Task 17: Compose production with one real UDP source and no fake fallback

**Files:**
- Modify: `backend/src/main/kotlin/com/filamentvision/backend/BackendComposition.kt`
- Modify: `backend/src/main/kotlin/com/filamentvision/backend/BackendApplication.kt`
- Modify: `backend/build.gradle.kts`
- Modify: `backend/src/main/kotlin/com/filamentvision/backend/input/runtime/BackendInputSourceFactory.kt`
- Modify: `backend/src/test/kotlin/com/filamentvision/backend/input/runtime/ProductionUdpCompositionTest.kt`
- Modify: `backend/src/test/kotlin/com/filamentvision/backend/e2e/SimulatedMonitoringE2eTest.kt`
- Modify: `backend/src/test/kotlin/com/filamentvision/backend/simulator/SimulatorImageInputSourceTest.kt`
- Modify: `backend/src/test/kotlin/com/filamentvision/backend/simulator/SimulatorScenarioVisionTest.kt`
- Move: `backend/src/main/kotlin/com/filamentvision/backend/simulator/SimulationScenario.kt` → `backend/src/test/kotlin/com/filamentvision/backend/simulator/fixtures/SimulationScenario.kt`
- Move: `backend/src/main/kotlin/com/filamentvision/backend/simulator/SimulatorFrameFactory.kt` → `backend/src/test/kotlin/com/filamentvision/backend/simulator/fixtures/SimulatorFrameFactory.kt`
- Move: `backend/src/main/kotlin/com/filamentvision/backend/simulator/SimulatorImageInputSource.kt` → `backend/src/test/kotlin/com/filamentvision/backend/simulator/fixtures/SimulatorImageInputSource.kt`
- Move: `backend/src/main/kotlin/com/filamentvision/backend/SustainedVerifier.kt` → `backend/src/test/kotlin/com/filamentvision/backend/e2e/SustainedVerifierFixture.kt`
- Delete after replacement: `backend/src/main/kotlin/com/filamentvision/backend/input/runtime/UdpCameraInputSource.kt`
- Delete after replacement: `backend/src/main/kotlin/com/filamentvision/backend/input/runtime/UdpCameraProcessor.kt`
- Delete after replacement: `backend/src/main/kotlin/com/filamentvision/backend/input/runtime/CompiledUdpInputProfile.kt`
- Delete after replacement: `backend/src/main/kotlin/com/filamentvision/backend/input/runtime/UdpInputRouter.kt`
- Delete after replacement: `backend/src/main/kotlin/com/filamentvision/backend/input/runtime/UdpInputConfigurationService.kt`
- Delete: `backend/src/main/kotlin/com/filamentvision/backend/input/decoder/DiameterDecoder.kt`
- Delete: `backend/src/test/kotlin/com/filamentvision/backend/input/decoder/DiameterDecoderTest.kt`
- Delete after replacement: `backend/src/test/kotlin/com/filamentvision/backend/input/runtime/UdpCameraInputSourceTest.kt`
- Delete after replacement: `backend/src/test/kotlin/com/filamentvision/backend/input/runtime/UdpCameraProcessorTest.kt`
- Delete after replacement: `backend/src/test/kotlin/com/filamentvision/backend/input/runtime/UdpInputRouterTest.kt`
- Delete after replacement: `backend/src/test/kotlin/com/filamentvision/backend/input/runtime/UdpInputConfigurationServiceTest.kt`

**Interfaces:**
- Produces: default `BackendComposition.open(...)` with `deviceId="filamentvision-1"`, source id `camera`, one preview key/source/router; explicit test factory is the only simulator entry.

- [ ] Test default composition type, silent source→no preview/measurement and waiting status, no `--scenario`, explicit test simulator still exercising existing deterministic E2E, close order, and one producer.
- [ ] Run `./gradlew.bat :backend:test --tests "com.filamentvision.backend.input.runtime.ProductionUdpCompositionTest" --tests "com.filamentvision.backend.e2e.SimulatedMonitoringE2eTest"`; RED must expose current simulator overload/dual source composition.
- [ ] Wire Tasks 8–16, remove production simulator imports/CLI flag/overload and the production `runSustainedVerifier` task, then move simulator/verifier files with package updates only. Delete the fully replaced dual runtime classes/tests and unused Diameter decoder; keep the protocol-neutral Core direct-diameter/fusion algorithms and their golden tests unchanged.
- [ ] Run focused tests; then `./gradlew.bat :backend:test --tests "com.filamentvision.backend.BackendSmokeTest" --tests "com.filamentvision.backend.api.CompositionEventContractTest" --tests "com.filamentvision.backend.api.PreviewWebSocketBackpressureTest" --tests "com.filamentvision.backend.api.EventWebSocketTest"`; separately run `./gradlew.bat :core:test --tests "com.filamentvision.core.runtime.MonitoringRuntimeConcurrencyTest"`.
- [ ] Run diff checks/review and commit `refactor(backend): compose one real UDP stream`.

**Done / rollback:** Starting production without Pi yields waiting state and zero fake measurements. Simulator remains explicitly test-only.

### Task 18: Expose single profile, interface, status and diagnostics REST contracts

**Files:**
- Create: `backend/src/main/kotlin/com/filamentvision/backend/api/dto/RaspberryPiUdpInputDtos.kt`
- Create: `backend/src/main/kotlin/com/filamentvision/backend/api/dto/RaspberryPiUdpInputDtoMappers.kt`
- Modify: `backend/src/main/kotlin/com/filamentvision/backend/api/dto/ConfigurationDtos.kt`
- Modify: `backend/src/main/kotlin/com/filamentvision/backend/api/dto/MeasurementDtos.kt`
- Modify: `backend/src/main/kotlin/com/filamentvision/backend/api/dto/EventDtos.kt`
- Modify: `backend/src/main/kotlin/com/filamentvision/backend/api/dto/StateDtos.kt`
- Modify: `backend/src/main/kotlin/com/filamentvision/backend/api/dto/DtoMappers.kt`
- Modify: `backend/src/main/kotlin/com/filamentvision/backend/api/PreviewWebSocket.kt`
- Modify: `backend/src/main/kotlin/com/filamentvision/backend/api/Routes.kt`
- Modify: `backend/src/main/kotlin/com/filamentvision/backend/application/BackendApplication.kt`
- Modify: `backend/src/main/kotlin/com/filamentvision/backend/ComposedBackendApplication.kt`
- Modify: `backend/src/test/kotlin/com/filamentvision/backend/api/UdpInputConfigurationApiTest.kt`
- Modify: `backend/src/test/kotlin/com/filamentvision/backend/api/UdpDiagnosticsApiTest.kt`
- Create: `backend/src/test/kotlin/com/filamentvision/backend/api/NetworkInterfacesApiTest.kt`

**Interfaces:**
- Produces: retained device configuration GET/PUT/APPLY routes plus `GET /api/v1/network-interfaces` and one `GET /api/v1/devices/{deviceId}/udp-diagnostics`.

- [ ] Contract-test exact single DTO, four formats, envelope, Save/Apply revisions/CAS/errors, interface choices/advertised target/no-usable-interface, all operational statuses/counters, neutral configuration/measurement/reliable-event fields, one preview key, unknown device, and absence of A/B/camera route fields.
- [ ] Run `./gradlew.bat :backend:test --tests "com.filamentvision.backend.api.UdpInputConfigurationApiTest" --tests "com.filamentvision.backend.api.UdpDiagnosticsApiTest" --tests "com.filamentvision.backend.api.NetworkInterfacesApiTest"`; expect DTO/route mismatch.
- [ ] Add authoritative mapping and routes, then delete old dual UDP DTO/mappers only after no production consumer remains.
- [ ] Run focused tests; then `./gradlew.bat :backend:test --tests "com.filamentvision.backend.api.RestContractTest" --tests "com.filamentvision.backend.api.RequestMediaContractTest" --tests "com.filamentvision.backend.api.CompositionEventContractTest"`.
- [ ] Run diff checks/review and commit `feat(api): expose single UDP configuration`.

**Done / rollback:** API never returns `0.0.0.0` as destination and old per-camera diagnostics routes are gone in the bundled release.

### Task 19: Collapse Packet Inspector to one bounded endpoint

**Files:**
- Modify: `backend/src/main/kotlin/com/filamentvision/backend/input/inspector/PacketInspector.kt`
- Modify: `backend/src/main/kotlin/com/filamentvision/backend/input/inspector/PacketInspectorModels.kt`
- Modify: `backend/src/main/kotlin/com/filamentvision/backend/api/dto/PacketInspectorDtos.kt`
- Modify: `backend/src/main/kotlin/com/filamentvision/backend/api/Routes.kt`
- Modify: `backend/src/test/kotlin/com/filamentvision/backend/input/inspector/PacketInspectorTest.kt`
- Modify: `backend/src/test/kotlin/com/filamentvision/backend/api/PacketInspectorApiTest.kt`

**Interfaces:**
- Produces: one lease namespace and routes without camera id; raw envelope/structured fields and parsed image frame result, sequence/ETag, previews 64/128/256 with hard max 512.

- [ ] Test lease create/release/expiry, latest-only replacement, source mismatch raw observation before pipeline, header/footer mismatch status before structured parser, all structured fields, four image decode results, no full frame/history, disabled near-zero overhead, receive-buffer reuse ownership, 304 unchanged, and retained bytes zero after release/expiry.
- [ ] Run `./gradlew.bat :backend:test --tests "com.filamentvision.backend.input.inspector.PacketInspectorTest" --tests "com.filamentvision.backend.api.PacketInspectorApiTest"`; RED must show dual namespaces/routes and old framing fields.
- [ ] Replace keyed maps with one atomic latest snapshot, tap envelope and parsed-frame stages, and format Hex only on REST read.
- [ ] Run focused tests; then `./gradlew.bat :backend:test --tests "com.filamentvision.backend.input.runtime.RaspberryPiUdpProcessorTest" --tests "com.filamentvision.backend.input.runtime.RaspberryPiUdpInputSourceTest" --tests "com.filamentvision.backend.api.PreviewWebSocketBackpressureTest" --tests "com.filamentvision.backend.api.EventWebSocketTest"`.
- [ ] Run diff checks/review and commit `refactor(inspector): observe one UDP endpoint`.

**Done / rollback:** Inspector is optional, bounded and never a processing dependency.

### Task 20: Define frontend single-stream contracts and Backend client

**Files:**
- Create: `frontend/src/domain/raspberryPiUdpInput.ts`
- Create: `frontend/src/config/raspberryPiUdpInputProfile.ts`
- Create: `frontend/src/config/raspberryPiUdpInputProfile.test.ts`
- Modify: `frontend/src/services/BackendApiClient.ts`
- Modify: `frontend/src/services/BackendApiClient.test.ts`
- Delete after migration: `frontend/src/domain/udpInput.ts`, `frontend/src/config/udpInputProfile.ts`, `frontend/src/config/udpInputProfile.test.ts`

**Interfaces:**
- Produces: typed profile/revision/interface/status/diagnostics/Inspector DTOs and client methods matching Tasks 18–19.

- [ ] Test four format-dependent drafts, exact two-byte HEX feedback, IPv4/port/bounds, no A/B/DIAMETER/transport/framing scope fields, GET/Save/Apply, interfaces, diagnostics, lease polling/304/abort and Backend field-error preservation.
- [ ] Run `npm --prefix frontend test -- src/config/raspberryPiUdpInputProfile.test.ts src/services/BackendApiClient.test.ts`; expect missing new contracts.
- [ ] Add types/client mapping and lightweight frontend validation; Backend remains authoritative. Remove old files only after imports are changed in this task.
- [ ] Run focused tests; then `npm --prefix frontend test -- src/state/MonitoringStore.test.ts src/App.test.tsx` and `npm --prefix frontend run build`.
- [ ] Run diff checks/review and commit `feat(frontend): add single UDP contracts`.

**Done / rollback:** Frontend can use the stable single-stream Backend API without rendering UI changes yet.

### Task 21: Replace dual Settings with one Raspberry Pi UDP panel

**Files:**
- Create: `frontend/src/components/RaspberryPiUdpInputSettings.tsx`
- Create: `frontend/src/components/RaspberryPiUdpInputSettings.test.tsx`
- Modify: `frontend/src/components/SettingsPanel.tsx`
- Modify: `frontend/src/components/UdpDiagnosticsPanel.tsx`
- Modify: `frontend/src/components/PacketInspector.tsx`
- Modify: `frontend/src/components/PacketInspector.test.tsx`
- Modify: `frontend/src/styles.css`
- Delete: `frontend/src/components/UdpCameraSettingsCard.tsx`, `frontend/src/components/UdpInputSettings.tsx`, `frontend/src/components/UdpInputSettings.test.tsx`

**Interfaces:**
- Consumes: Task 20 client.
- Produces: one Settings form with Network, Camera Stream, Advanced Transport, Diagnostics and Packet Inspector sections.

- [ ] Test AUTO/specific interface selection, advertised `IPv4:port`, never 0.0.0.0, optional Pi IPv4, status/last packet, JPEG default, all four conditional formats, two-byte header/footer, read-only protocol v1, Save distinct from Apply, Backend errors, one diagnostics panel, and one Inspector lifecycle.
- [ ] Run focused new Settings and Inspector tests; expect missing component and current dual cards.
- [ ] Build accessible controlled form, poll only while mounted, and preserve current monitoring threshold settings separately.
- [ ] Run focused tests; then run `npm --prefix frontend test`, `npm --prefix frontend run lint`, and `npm --prefix frontend run build` as three separate commands.
- [ ] Run diff checks/review and commit `feat(frontend): configure Raspberry Pi UDP input`.

**Done / rollback:** Settings contains no A/B, arbitrary framing, direct-diameter or fake input controls.

### Task 22: Make the production frontend consume Backend data only

**Files:**
- Create: `frontend/src/services/BackendFilamentDataSource.ts`
- Create: `frontend/src/services/BackendFilamentDataSource.test.ts`
- Modify: `frontend/src/services/FilamentDataSource.ts`
- Modify: `frontend/src/state/MonitoringRuntime.ts`
- Modify: `frontend/src/state/MonitoringStore.ts`
- Modify: `frontend/src/state/useMonitoringStore.ts`
- Modify: `frontend/src/domain/monitoring.ts`
- Modify: `frontend/src/main.tsx`
- Move: `frontend/src/services/MockFilamentDataSource.ts` → `frontend/src/test/fixtures/MockFilamentDataSource.ts`
- Move: `frontend/src/services/MockFilamentDataSource.test.ts` → `frontend/src/test/fixtures/MockFilamentDataSource.test.ts`

**Interfaces:**
- Produces: REST + reliable Event WS + one Preview WS source; `FilamentDataSource.subscribePreview(listener)` emits one owned/revocable object URL or byte-backed preview value; start/stop commands are idempotent Backend commands and React owns subscriptions only.

- [ ] Test reliable sequence handling/reconnect, one preview subscription, replacement/unmount object-URL revocation, cleanup/abort, explicit idempotent start/stop, browser reopen not creating a Backend producer, and silent Backend producing no local samples. Add an import-boundary test proving production `main.tsx` cannot import Mock.
- [ ] Run focused Backend data source, monitoring runtime and moved Mock tests; expect missing source and current Mock production import.
- [ ] Implement Backend source, inject it from `main.tsx`, and relocate Mock with package/import changes only.
- [ ] Run focused tests; then run `npm --prefix frontend test`, `npm --prefix frontend run lint`, and `npm --prefix frontend run build` as three separate commands.
- [ ] Run diff checks/review and commit `refactor(frontend): remove production fake data`.

**Done / rollback:** No Pi means no fake image or measurement; test fixtures remain deterministic.

### Task 23: Render one live preview and neutral diameter monitoring state

**Files:**
- Modify: `frontend/src/domain/measurement.ts`
- Modify: `frontend/src/domain/measurement.test.ts`
- Modify: `frontend/src/domain/monitoring.ts`
- Create: `frontend/src/components/LiveCameraPreview.tsx`
- Create: `frontend/src/components/LiveCameraPreview.test.tsx`
- Modify: `frontend/src/components/MonitoringDashboard.tsx`
- Modify: `frontend/src/components/DiameterReadingPanel.tsx`
- Modify: `frontend/src/components/TrendChart.tsx`
- Modify: `frontend/src/App.test.tsx`
- Modify: `frontend/src/styles.css`

**Interfaces:**
- Produces: status banner, one live preview, current `diameterMm`/confidence/timestamp, trend/history, and warning/alarm context.

- [ ] Test waiting state with empty preview/diameter, UDP status copy, one image tile fed by the Preview subscription, latest-frame replacement, neutral measurement fields, trend/history continuity, warning/alarm rendering, and absence of Camera A/B, dual confidence, view difference, fused copy and fake samples.
- [ ] Run focused measurement/App tests; expect old dual domain/dashboard assertions.
- [ ] Map the neutral DTO and simplify layout while retaining alarm/history behavior and accessibility.
- [ ] Run focused tests; then run `npm --prefix frontend test`, `npm --prefix frontend run lint`, and `npm --prefix frontend run build` as three separate commands.
- [ ] Run diff checks/review and commit `feat(frontend): monitor one camera stream`.

**Done / rollback:** Monitoring simultaneously shows one real preview, Vision diameter, trend/history and alarm/status.

### Task 24: Prove single-camera UDP E2E, 8MP boundedness and full regression

**Files:**
- Modify: `backend/src/test/kotlin/com/filamentvision/backend/e2e/UdpTestSender.kt`
- Replace dual assumptions in: `backend/src/test/kotlin/com/filamentvision/backend/e2e/UdpInputEndToEndTest.kt`
- Modify: `backend/src/test/kotlin/com/filamentvision/backend/e2e/LongRunBoundednessTest.kt`
- Modify: `backend/src/test/kotlin/com/filamentvision/backend/e2e/ReviewRegressionTest.kt`
- Create: `docs/implementation/single-camera-udp-verification.md`

**Interfaces:**
- Consumes: complete production composition and exact wire format.
- Produces: repeatable loopback sender/gate evidence; no new product feature.

- [ ] Before glue changes, add one real IPv4 socket/port cases for JPEG multi-chunk/out-of-order, GRAY8, both BINARY packings, RGB/BGR, exact valid/invalid envelope, source mismatch plus Inspector, sentinel oversize, missing/duplicate/conflicting/late/timeout chunks, Save/Apply parser swap/format/port restart/bind and CAS rollback, one Preview, Vision-derived diameter, alarm/session reliable events during Preview/Inspector congestion, waiting with no Pi, and one producer/job.
- [ ] Add bounded tests for a deterministic 3840×2160 frame, 8MP-safe declaration/allocation edges, max two in-flight reassemblies/64 MiB, one decoded permit, capacity-one mailbox, Preview ≤4 MiB, Inspector ≤512 bytes, and retained reassembly/permit/mailbox/Inspector/Preview resources returning to zero or their documented steady bound after stop/restart and lease release.
- [ ] Run `./gradlew.bat :backend:test --tests "com.filamentvision.backend.e2e.UdpInputEndToEndTest" --tests "com.filamentvision.backend.e2e.LongRunBoundednessTest"`; RED must identify only missing integration wiring, not a new protocol need.
- [ ] Add only sender/test-composition glue and fix integration defects within approved boundaries. Record measured peak retained categories and producer/job maxima in the verification report.
- [ ] Run the focused command, then the complete gate:

```powershell
./gradlew.bat clean :core:test :backend:test verifyStandaloneIndependence
Push-Location frontend
npm test
npm run lint
npm run build
Pop-Location
git diff --check
git status --short
```

- [ ] Confirm no skipped tests, inspect `git diff --stat` and full diff, then commit `test(udp): verify single-camera production path`.

**Done / rollback:** The verification report contains exact commands/counts, loopback cases, bounds, `activeProducerCount` maximum, receiver-job maximum, skipped-test count, retained generic test-only utilities, and confirmation that production has no simulator dependency. Reverting this final commit removes only gate fixtures/report, not feature work.

---

## Explicit Legacy-Test Disposition

- Replace dual-production assertions in `UdpCameraInputSourceTest`, `UdpCameraProcessorTest`, `UdpInputRouterTest`, `UdpInputConfigurationServiceTest`, production composition tests, UDP API tests, Inspector API tests, and frontend UDP/Monitoring tests with the new single-stream counterparts named above.
- Delete tests whose only purpose is distinct A/B listen-port validation, Camera A/B receiver isolation, two Settings cards, two Preview tiles, or production DIAMETER/SINGLE_DATAGRAM selection.
- Retain unchanged: Structured transport/reassembly ordering and limit tests, receive ownership/sentinel tests, all four image decoder tests, allocation tests, Vision/Fusion/Alarm/Statistics goldens, runtime concurrency/failure/finalization, repository conformance, reliable Event WS, Preview backpressure, REST/media/security, LongRunBoundedness and standalone independence.
- Retain as test-only: `SingleDatagramFrameReassemblerTest`, generic Application framing characterization, direct-diameter/fusion algorithm tests, simulator fixture tests, synthetic images, fake clocks/repositories and loopback sender.

## Dependency and Rollback Chain

```text
Characterization
  -> single profile -> exact envelope -> receive bounds -> unchanged transport identity
  -> IPv4 interfaces -> one processor -> one source/status
  -> decoded permit -> bounded preview
  -> neutral measurement -> Runtime
  -> codecs -> V004 -> atomic Apply
  -> production composition -> REST -> Inspector
  -> frontend contracts -> Settings -> Backend data source -> Monitoring UI
  -> E2E / boundedness / full gate
```

Every arrow is an independent commit/review boundary. A failed task rolls back to its immediate predecessor. V004 is not edited after Task 15. Tasks 2–16 keep old production types available until the consuming layer migrates; deletions occur only after the replacement compiles and its RED/GREEN tests pass.

## Final Phase Gate

The migration is complete only when all 24 delta commits are independently reviewable; the full Kotlin/frontend/standalone gate is green; no test is skipped; production has one source/socket/preview and no fake fallback; the 34-byte fixture is byte-identical; the outer envelope is exactly 2+2 bytes; all four image formats pass loopback E2E; waiting state emits no data; reliable events survive lossy observer congestion; `activeProducerCount <= 1`; receiver jobs `<= 1`; and every 8MP-related retained-memory category stays within the design bounds.
