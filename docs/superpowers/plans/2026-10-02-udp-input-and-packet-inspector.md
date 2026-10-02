# FilamentVision UDP Input and Packet Inspector Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a production UDP input path for Camera A and Camera B, with the two approved transport modes, five payload formats, revisioned Save/Apply, bounded diagnostics, and a lease-controlled Packet Inspector, without changing the existing Vision/Fusion/Alarm/Session behavior.

**Architecture:** Keep all UDP sockets, wire framing, JPEG handling, SQLite implementation, Ktor DTOs, and Inspector state in `:backend`. Add only the protocol-neutral `CameraInput`/direct-diameter evidence boundary to `:core`. A single `UdpCameraInputSource` remains the one producer owned by `MonitoringRuntime`; its Camera A/B receivers are child resources. The normal path is `UdpReceiver -> TransportDatagramParser -> DATAGRAM ApplicationFramingParser -> FrameReassembler -> FRAME ApplicationFramingParser -> PayloadDecoder -> CameraInput`. Inspector taps observe bounded metadata beside this path and never become a prerequisite for it.

**Tech Stack:** Kotlin 2.2.10/JVM 17, kotlinx-coroutines 1.9.0, JUnit 4.13.2, Ktor 3.3.1, Jackson, SQLite JDBC 3.51.1, React 19, TypeScript 6, Vite 8, Vitest 4, Testing Library.

**Spec:** `docs/superpowers/specs/2026-10-02-udp-input-and-packet-inspector-design.md` at design commit `97e35bd`.

## Global Constraints

- The approved design is the sole functional specification. Do not infer Raspberry Pi packet fields, add RTP/TCP/Bluetooth, or change the 34-byte structured wire format.
- `:core` remains free of Android, Ktor, SQLite/JDBC, Room, Compose, Jackson, AWT, `ImageIO`, and desktop-only image APIs.
- Every task follows this exact loop: add the named test and run it RED; record the expected failure; add only the named minimum implementation; run focused tests; run the listed regression tests; inspect `git diff --check` and `git diff --stat`; commit only that task.
- Never change existing characterization/golden expected values to accommodate this feature. A mismatch blocks the task and must be reported.
- `MonitoringRuntime` is the authoritative producer owner. Camera receiver jobs are children of its one source lifecycle and never increment `activeProducerCount` independently.
- Preview remains lossy/latest-first; measurement, alarm, session, configuration, error, and reliable event paths remain independent and must not be dropped by UDP or Inspector congestion.
- Pairing uses Backend `receivedAtMonotonicNanos` only. `sourceTimestampMicros` is diagnostics metadata and never drives pairing, timeout, or ordering.
- Production UDP composition must not depend on the simulator. Simulator classes remain available only through explicit test fixtures/factories.
- No task may combine a RED test, multiple architectural layers, and broad cleanup. If an actual dependency contradicts this plan, stop that task and report the evidence rather than silently redesigning it.
- The `UdpReceiver` receive array is borrowed storage valid only until the synchronous datagram-consumption callback returns. A parser may expose a view into it only inside that callback. Reassembly, Inspector, deferred processing, channels, flows, and every other asynchronous boundary must hold their own bounded bytes before the receive loop can reuse the array.
- Ownership transfer never means copying every full datagram multiple times: Structured reassembly copies only the validated post-DATAGRAM-framing chunk bytes it retains; SINGLE_DATAGRAM copies its bounded completed payload once before asynchronous handoff; Inspector copies only the configured 64/128/256-byte preview, hard-capped at 512.
- UDP Save and Apply share one configuration command mutex/serialization boundary. A repository mutation that throws has the contract that its transaction did not commit. Applied state is published only after runtime swap and persisted `markApplied` both succeed; any persistence/CAS failure after a runtime swap uses the swap rollback token before returning.

## Verified Repository Map and Planned Boundaries

The repository currently has only `:core` and `:backend` Gradle modules. `BackendComposition` owns one simulator-backed `MonitoringRuntime`; `ComposedBackendApplication` serializes application commands; `Routes.kt` exposes `/api/v1`; SQLite is isolated below `backend/persistence/sqlite`; reliable events and preview already use separate hubs. The frontend Settings page currently edits only local monitoring thresholds and has no Backend API client.

### Core boundary changes

- Create `core/src/main/kotlin/com/filamentvision/core/input/CameraInput.kt` for `ImageFrameInput`, `DirectDiameterInput`, and timestamp provenance.
- Create `core/src/main/kotlin/com/filamentvision/core/input/CameraInputSource.kt` for the single producer lifecycle.
- Modify `core/src/main/kotlin/com/filamentvision/core/input/ImageInputSource.kt` only to provide a compatibility adapter; existing simulator behavior remains testable.
- Modify `core/src/main/kotlin/com/filamentvision/core/vision/DetectionPipeline.kt` so `CameraMeasurement` has `source` and nullable `pixelWidth`.
- Modify `core/src/main/kotlin/com/filamentvision/core/runtime/MonitoringRuntime.kt` to route image input through Vision and direct evidence through the explicit fusion adapter.

### Backend input adapter packages

- `backend/.../input/profile/` — immutable profile model, validation, revisions, repository port, and codec-neutral application service.
- `backend/.../input/transport/` — 34-byte parser/codec, receive-capacity policy, IPv4 receiver, and transport errors.
- `backend/.../input/reassembly/` — single-datagram and structured-chunk reassemblers.
- `backend/.../input/framing/` — Application Header/Footer parser only.
- `backend/.../input/decoder/` — decoded-allocation policy plus five payload decoders.
- `backend/.../input/runtime/` — compiled immutable chains, two-camera router, source lifecycle, atomic Apply/restart/rollback.
- `backend/.../input/diagnostics/` — bounded per-camera counters and immutable snapshots.
- `backend/.../input/inspector/` — leases and bounded latest snapshot tap.

### Stable interface sketches

```kotlin
sealed interface CameraInput {
    val cameraId: CameraId
    val sequence: Long
    val receivedAtMonotonicNanos: Long
    val receivedAtEpochMillis: Long
    val sourceTimestampMicros: Long?
}
data class ImageFrameInput(val frame: CameraFrame, override val sourceTimestampMicros: Long?) : CameraInput
data class DirectDiameterInput(val evidence: DirectDiameterEvidence) : CameraInput

interface CameraInputSource {
    val inputs: Flow<CameraInput>
    val state: StateFlow<ImageInputState>
    val diagnostics: StateFlow<ImageInputDiagnostics>
    val activeProducerCount: Int
    suspend fun start()
    suspend fun stop()
}
```

```kotlin
interface UdpInputProfileRepository {
    fun revisions(): UdpInputRevisions
    fun profileSet(revision: Long): UdpInputProfileSet?
    fun save(profileSet: UdpInputProfileSet): UdpInputRevision
    fun markApplied(revision: Long, expectedSavedRevision: Long): Boolean
}

interface AppliedUdpInputRuntime {
    suspend fun prepareAndSwap(candidate: CompiledUdpProfileSet): UdpRuntimeSwap
    suspend fun commit(swap: UdpRuntimeSwap)
    suspend fun rollback(swap: UdpRuntimeSwap)
    fun applied(): CompiledUdpProfileSet?
}
```

`UdpRuntimeSwap` owns the previous compiled profiles, receiver handles, and effective revision until commit. The service sequence while holding the configuration mutex is: verify saved-revision CAS; load/validate/compile candidate; `prepareAndSwap`; call transactional `repository.markApplied`; `commit`; publish. If `markApplied` returns false or throws, call `rollback` and verify runtime profile, receiver effective configuration, and persisted applied revision are all still the prior revision before returning failure.

### Receive-buffer ownership and Runtime admission

`UdpReceiver` invokes its processor synchronously before the next socket receive reuses the backing array. `TransportDatagramParser` may therefore return a borrowed payload view, but that type is not allowed in reassembler state or `CameraInput`. `StructuredChunkFrameReassembler` takes an owned bounded copy of only the accepted post-DATAGRAM-framing chunk. `SingleDatagramFrameReassembler` creates one owned bounded completed-payload copy before output. `PacketInspectorTap` copies only its preview limit.

`UdpCameraInputSource` preserves the existing simulator/source backpressure contract: one pending input per Camera, therefore total pending capacity is exactly two. Offering a newer input for the same Camera replaces and closes the older **pending** image input and increments that Camera's internal `preAdmissionDroppedInputs`; Direct Diameter replacement increments the same counter. This reason-specific count contributes to the already approved public `framesDropped` diagnostic rather than adding a new REST field. An input becomes admitted when the source flow removes it from the keyed mailbox and calls the `MonitoringRuntime` collector. There is no queue and no DROP policy after that point: collection applies backpressure until Runtime processing returns. Preview and Inspector taps are outside this mailbox and can neither block admission nor discard an admitted input.

### Fixed structured header mapping

All parser/codec tests use the exact approved 34-byte layout and Big Endian order: magic `FVU1` at `0..3`; version at `4`; header length at `5`; camera at `6`; flags at `7`; unsigned frameId at `8..15`; chunkIndex at `16..17`; chunkCount at `18..19`; source timestamp micros at `20..27`; payloadLength at `28..29`; framePayloadBytes at `30..33`. No task may move or reinterpret these fields.

---

## Task 1: Define UDP profile types and authoritative validation

**Files:**
- Create `backend/src/main/kotlin/com/filamentvision/backend/input/profile/UdpInputProfile.kt`
- Create `backend/src/main/kotlin/com/filamentvision/backend/input/profile/UdpInputProfileValidator.kt`
- Create `backend/src/test/kotlin/com/filamentvision/backend/input/profile/UdpInputProfileValidatorTest.kt`

**Tests first / RED:** Add table-driven tests for both cameras, distinct enabled ports, optional normalized IPv4 literals, IPv6 rejection, port/range bounds, transport limits, payload-specific required fields, finite diameter scale/offset/confidence, 64-byte framing limits, valid/invalid HEX and ASCII, decoded hard limits, and checked dimension arithmetic. RED must be unresolved profile/validator types, not an unrelated test harness failure.

**Minimal GREEN:** Add sealed `PayloadProfile` variants (`Gray8`, `Binary`, `Jpeg`, `Rgb888`, `Diameter`), approved enums, `ApplicationFramingProfile`, `TransportLimits`, `UdpInputProfile`, `UdpInputProfileSet`, and a validator returning stable field-path errors. Keep profile types Backend-only. Do not open a socket or persist JSON.

**Focused:** `./gradlew.bat :backend:test --tests "com.filamentvision.backend.input.profile.UdpInputProfileValidatorTest"`

**Regression:** Run separately: `./gradlew.bat :backend:test --tests "com.filamentvision.backend.api.RequestMediaContractTest"`; then `./gradlew.bat :core:test`.

**Done / commit / rollback:** All limits in sections 3, 5, 6, and 7 validate before allocation; IPv6 is rejected. Commit `feat(udp): define input profiles and validation`. Revert removes only Backend model/validation files.

## Task 2: Parse and encode the fixed 34-byte transport header

**Files:**
- Create `backend/src/main/kotlin/com/filamentvision/backend/input/transport/StructuredTransportHeader.kt`
- Create `backend/src/main/kotlin/com/filamentvision/backend/input/transport/TransportDatagramParser.kt`
- Create `backend/src/test/kotlin/com/filamentvision/backend/input/transport/TransportDatagramParserTest.kt`

**Tests first / RED:** Encode fixed byte fixtures and assert each offset, Big Endian round-trip, unsigned frameId preservation, exact datagram length, magic/version/headerLength/flags/camera mismatch, chunk index/count, source timestamp, payloadLength, framePayloadBytes, and `payloadLength > maxChunkPayloadBytes`. Read the 4-byte `framePayloadBytes` as unsigned 32-bit into `Long` before any comparison or arithmetic; fixtures `0x7FFFFFFF`, `0x80000000`, and `0xFFFFFFFF` must all be safely rejected against configured/hard limits without negative length, overflow, copy, or allocation. Spy callbacks must prove an over-limit field is rejected before payload copy or framing/reassembly.

**Minimal GREEN:** Implement a pure byte parser returning immutable header metadata plus a borrowed bounded payload view whose lifetime is documented as the current synchronous receiver callback. Use unsigned widening such as `Integer.toUnsignedLong(rawInt)` for `framePayloadBytes`, validate it as `Long`, and convert to narrower/index types only after hard-limit checks. The parser accepts expected camera and compiled limits; it performs no Application Header/Footer work, owned-state retention, or socket I/O.

**Focused:** `./gradlew.bat :backend:test --tests "com.filamentvision.backend.input.transport.TransportDatagramParserTest"`

**Regression:** Run separately: `./gradlew.bat :backend:test --tests "com.filamentvision.backend.input.profile.UdpInputProfileValidatorTest"`; then `./gradlew.bat :core:test`.

**Done / commit / rollback:** Every byte and validation rule matches the fixed table; parser has no receiver/reassembler dependency. Commit `feat(udp): parse structured transport headers`. Revert leaves profiles intact.

## Task 3: Compute receive limits and sentinel capacities

**Files:**
- Create `backend/src/main/kotlin/com/filamentvision/backend/input/transport/UdpReceiveCapacity.kt`
- Create `backend/src/test/kotlin/com/filamentvision/backend/input/transport/UdpReceiveCapacityTest.kt`

**Tests first / RED:** Cover structured `34 + maxChunkPayloadBytes`, single-datagram strip=true additions, strip=false non-addition, mixed DATAGRAM/FRAME scope, checked overflow, 65507 cap, `accepted+1` sentinel when below the cap, and exact 65507 capacity at the cap.

**Minimal GREEN:** Add a pure capacity calculator returning `acceptedLimit`, `receiveCapacity`, and `sentinelEnabled`. It must not create `DatagramSocket`.

**Focused:** `./gradlew.bat :backend:test --tests "com.filamentvision.backend.input.transport.UdpReceiveCapacityTest"`

**Regression:** `./gradlew.bat :backend:test --tests "com.filamentvision.backend.input.profile.UdpInputProfileValidatorTest" --tests "com.filamentvision.backend.input.transport.TransportDatagramParserTest"`

**Done / commit / rollback:** Both transport modes have exact, overflow-safe bounds. Commit `feat(udp): calculate bounded receive capacity`. Revert affects no parsing behavior.

## Task 4: Add the bounded IPv4 UDP receiver lifecycle

**Files:**
- Create `backend/src/main/kotlin/com/filamentvision/backend/input/transport/UdpReceiver.kt`
- Create `backend/src/main/kotlin/com/filamentvision/backend/input/transport/Ipv4SourceFilter.kt`
- Create `backend/src/main/kotlin/com/filamentvision/backend/input/transport/ReceivedDatagram.kt`
- Create `backend/src/test/kotlin/com/filamentvision/backend/input/transport/UdpReceiverTest.kt`
- Create `backend/src/test/kotlin/com/filamentvision/backend/input/transport/UdpReceiveBufferOwnershipTest.kt`

**Tests first / RED:** Use loopback sockets to verify explicit IPv4 `0.0.0.0` bind, matching/nonmatching IPv4 filters, maximum legal datagram reception without truncation, sentinel-sized reception reported as `DATAGRAM_TOO_LARGE_OR_TRUNCATED`, parser callback not invoked for sentinel rejection, OS receive queue configuration, start/stop/restart, bind failure, and socket/job closure. Include a deliberately oversized sender packet within the IPv4 UDP hard maximum. Process two different datagrams through the same injected receive array and prove the second receive cannot mutate the first packet's owned downstream fixture; also prove a borrowed view is never retained after the synchronous callback.

**Minimal GREEN:** Implement one receiver child with injectable socket factory and monotonic/epoch clocks. Capture timestamps once at receive. Filter source before transport parsing. Invoke datagram processing synchronously and reuse the mutable receive array only after it returns; require the consumer to create bounded ownership before any asynchronous handoff. Expose a lightweight mismatch callback so a future Inspector can observe it, but do not allocate previews or Inspector objects yet.

**Focused:** `./gradlew.bat :backend:test --tests "com.filamentvision.backend.input.transport.UdpReceiverTest" --tests "com.filamentvision.backend.input.transport.UdpReceiveBufferOwnershipTest"`

**Regression:** `./gradlew.bat :backend:test --tests "com.filamentvision.backend.input.profile.UdpInputProfileValidatorTest" --tests "com.filamentvision.backend.input.transport.TransportDatagramParserTest" --tests "com.filamentvision.backend.input.transport.UdpReceiveCapacityTest" --tests "com.filamentvision.backend.e2e.LongRunBoundednessTest"`

**Done / commit / rollback:** No reassembly exists in this commit; truncation has its own result/counter hook. Commit `feat(udp): receive bounded IPv4 datagrams`. Revert removes the socket layer without affecting pure transport parsing.

## Task 5: Implement single-datagram reassembly

**Files:**
- Create `backend/src/main/kotlin/com/filamentvision/backend/input/reassembly/FrameReassembler.kt`
- Create `backend/src/main/kotlin/com/filamentvision/backend/input/reassembly/SingleDatagramFrameReassembler.kt`
- Create `backend/src/test/kotlin/com/filamentvision/backend/input/reassembly/SingleDatagramFrameReassemblerTest.kt`

**Tests first / RED:** Assert one accepted datagram yields one completed frame, carries received monotonic/epoch timestamps, has null source timestamp, rejects payload over `maxFrameBytes`, and retains no in-flight state after success/failure.

**Minimal GREEN:** Define transport-neutral reassembly input/output and the stateless implementation. Do not share structured chunk state or inspect payload format.

**Focused:** `./gradlew.bat :backend:test --tests "com.filamentvision.backend.input.reassembly.SingleDatagramFrameReassemblerTest"`

**Regression:** `./gradlew.bat :backend:test --tests "com.filamentvision.backend.input.transport.TransportDatagramParserTest" --tests "com.filamentvision.backend.input.transport.UdpReceiveCapacityTest" --tests "com.filamentvision.backend.input.transport.UdpReceiverTest" --tests "com.filamentvision.backend.input.transport.UdpReceiveBufferOwnershipTest"`

**Done / commit / rollback:** SINGLE_DATAGRAM has explicit provenance and bounded output. Commit `feat(udp): add single datagram reassembler`. Revert leaves receiver/parser usable.

## Task 6: Implement structured chunk ordering and duplicate semantics

**Files:**
- Create `backend/src/main/kotlin/com/filamentvision/backend/input/reassembly/StructuredChunkFrameReassembler.kt`
- Create `backend/src/test/kotlin/com/filamentvision/backend/input/reassembly/StructuredChunkFrameReassemblerOrderingTest.kt`

**Tests first / RED:** Cover in-order and out-of-order completion, identical duplicate ignored/counted, conflicting duplicate dropping the frame, interleaved frame IDs, source endpoint in frame key, inconsistent source timestamp/header metadata conflict, and exact ordered concatenation. Reuse one backing receive buffer for two chunks, overwrite it between calls, and prove the first retained chunk and completed frame still contain their original owned bytes.

**Minimal GREEN:** Store owned bounded per-index chunk copies keyed by `(cameraId, source endpoint, frameId)`; never store parser `ByteBuffer` slices or borrowed array ranges. Use first accepted chunk monotonic time and separate completion time. Do not add eviction or timeout yet.

**Focused:** `./gradlew.bat :backend:test --tests "com.filamentvision.backend.input.reassembly.StructuredChunkFrameReassemblerOrderingTest"`

**Regression:** `./gradlew.bat :backend:test --tests "com.filamentvision.backend.input.reassembly.SingleDatagramFrameReassemblerTest" --tests "com.filamentvision.backend.input.transport.TransportDatagramParserTest" --tests "com.filamentvision.backend.input.transport.UdpReceiveBufferOwnershipTest"`

**Done / commit / rollback:** Ordering/duplicate behavior is deterministic and independent of sockets. Commit `feat(udp): reassemble ordered structured chunks`. Revert preserves the common reassembler contract.

## Task 7: Enforce structured reassembly safety and timeout limits

**Files:**
- Modify `backend/src/main/kotlin/com/filamentvision/backend/input/reassembly/StructuredChunkFrameReassembler.kt`
- Create `backend/src/test/kotlin/com/filamentvision/backend/input/reassembly/StructuredChunkFrameReassemblerLimitsTest.kt`

**Tests first / RED:** Cover missing chunk, timeout, bounded tombstone late chunk, framePayloadBytes mismatch, max in-flight oldest eviction, max buffered bytes, max chunk count, oversized frame declaration, no eager `framePayloadBytes` allocation, and released byte accounting after every drop path. Include DATAGRAM strip=true and strip=false chunk lengths to prove `framePayloadBytes` counts only bytes entering reassembly.

**Minimal GREEN:** Add monotonic expiry, oldest-incomplete eviction, per-camera byte accounting, bounded tombstones, declaration checks, and one bounded final merge after completion.

**Focused:** `./gradlew.bat :backend:test --tests "com.filamentvision.backend.input.reassembly.StructuredChunkFrameReassemblerLimitsTest"`

**Regression:** `./gradlew.bat :backend:test --tests "com.filamentvision.backend.input.transport.TransportDatagramParserTest" --tests "com.filamentvision.backend.input.transport.UdpReceiveCapacityTest" --tests "com.filamentvision.backend.input.transport.UdpReceiverTest" --tests "com.filamentvision.backend.input.transport.UdpReceiveBufferOwnershipTest" --tests "com.filamentvision.backend.input.reassembly.SingleDatagramFrameReassemblerTest" --tests "com.filamentvision.backend.input.reassembly.StructuredChunkFrameReassemblerOrderingTest" --tests "com.filamentvision.backend.input.reassembly.StructuredChunkFrameReassemblerLimitsTest"`

**Done / commit / rollback:** All section 5 bounds are enforced and state returns to zero after drop/timeout. Commit `feat(udp): bound structured reassembly`. Revert returns to Task 6 behavior without touching other layers.

## Task 8: Parse Application Header/Footer at explicit scopes

**Files:**
- Create `backend/src/main/kotlin/com/filamentvision/backend/input/framing/ApplicationFramingParser.kt`
- Create `backend/src/main/kotlin/com/filamentvision/backend/input/framing/FramingBytesParser.kt`
- Create `backend/src/test/kotlin/com/filamentvision/backend/input/framing/ApplicationFramingParserTest.kt`

**Tests first / RED:** Cover valid/invalid header/footer, HEX spaced/continuous parsing, invalid HEX, ASCII/subset rejection, DATAGRAM vs FRAME selection, header/footer with mixed scopes, strip=true/false, payload too short, 64-byte bound, SINGLE_DATAGRAM two-stage invocation, and structured `framePayloadBytes` after per-chunk stripping.

**Minimal GREEN:** Compile profile text once into immutable byte arrays; parse one requested scope and return payload plus matched region metadata. Do not call transport parser/reassembler/decoder from this class.

**Focused:** `./gradlew.bat :backend:test --tests "com.filamentvision.backend.input.framing.ApplicationFramingParserTest"`

**Regression:** `./gradlew.bat :backend:test --tests "com.filamentvision.backend.input.profile.UdpInputProfileValidatorTest" --tests "com.filamentvision.backend.input.transport.TransportDatagramParserTest" --tests "com.filamentvision.backend.input.transport.UdpReceiveCapacityTest" --tests "com.filamentvision.backend.input.transport.UdpReceiverTest" --tests "com.filamentvision.backend.input.transport.UdpReceiveBufferOwnershipTest" --tests "com.filamentvision.backend.input.reassembly.SingleDatagramFrameReassemblerTest" --tests "com.filamentvision.backend.input.reassembly.StructuredChunkFrameReassemblerOrderingTest" --tests "com.filamentvision.backend.input.reassembly.StructuredChunkFrameReassemblerLimitsTest"`

**Done / commit / rollback:** Application framing is a standalone layer with precise strip semantics. Commit `feat(udp): parse application framing`. Revert has no wire-header impact.

## Task 9: Add checked decoded-allocation policy and GRAY8 decoder

**Files:**
- Create `backend/src/main/kotlin/com/filamentvision/backend/input/decoder/DecodedAllocationPolicy.kt`
- Create `backend/src/main/kotlin/com/filamentvision/backend/input/decoder/PayloadDecoder.kt`
- Create `backend/src/main/kotlin/com/filamentvision/backend/input/decoder/Gray8Decoder.kt`
- Create `backend/src/test/kotlin/com/filamentvision/backend/input/decoder/DecodedAllocationPolicyTest.kt`
- Create `backend/src/test/kotlin/com/filamentvision/backend/input/decoder/Gray8DecoderTest.kt`

**Tests first / RED:** Assert valid grayscale conversion, exact payload-size rejection, zero/oversized dimensions, multiplication overflow, hardMaxWidth/Height/Pixels/DecodedBytes, ARGB 4-byte allocation check, and no allocator invocation on invalid input.

**Minimal GREEN:** Centralize checked add/multiply/ceil division and approved hard constants; emit Backend `DecodedImage` for `DecodedFrameAdapter`, never Core pixels directly.

**Focused:** `./gradlew.bat :backend:test --tests "com.filamentvision.backend.input.decoder.DecodedAllocationPolicyTest" --tests "com.filamentvision.backend.input.decoder.Gray8DecoderTest"`

**Regression:** `./gradlew.bat :backend:test`

**Done / commit / rollback:** GRAY8 is exact-size and allocation-safe. Commit `feat(udp): decode bounded gray8 payloads`. Revert removes decoder base without changing transport.

## Task 10: Decode both BINARY packing modes

**Files:**
- Create `backend/src/main/kotlin/com/filamentvision/backend/input/decoder/BinaryDecoder.kt`
- Create `backend/src/test/kotlin/com/filamentvision/backend/input/decoder/BinaryDecoderTest.kt`

**Tests first / RED:** Cover BYTE_PER_PIXEL zero/nonzero mapping, BIT_PACKED MSB-first order, ignored final padding bits, exact `ceil(pixels/8)`, wrong sizes, checked arithmetic, and decoded-output limits.

**Minimal GREEN:** Reuse `DecodedAllocationPolicy`; output 0/255 grayscale represented by `DecodedImage`.

**Focused:** `./gradlew.bat :backend:test --tests "com.filamentvision.backend.input.decoder.BinaryDecoderTest"`

**Regression:** `./gradlew.bat :backend:test`

**Done / commit / rollback:** Both approved packing modes pass fixed pixel vectors. Commit `feat(udp): decode binary payloads`. Revert affects only BINARY.

## Task 11: Decode RGB888 with RGB and BGR order

**Files:**
- Create `backend/src/main/kotlin/com/filamentvision/backend/input/decoder/Rgb888Decoder.kt`
- Create `backend/src/test/kotlin/com/filamentvision/backend/input/decoder/Rgb888DecoderTest.kt`

**Tests first / RED:** Cover RGB/BGR channel vectors, exact `width*height*3`, wire and ARGB decoded-byte limits, overflow, wrong payload size, and alpha=255.

**Minimal GREEN:** Reuse checked policy and emit canonical ARGB `DecodedImage`.

**Focused:** `./gradlew.bat :backend:test --tests "com.filamentvision.backend.input.decoder.Rgb888DecoderTest"`

**Regression:** Run separately: `./gradlew.bat :backend:test`; then `./gradlew.bat :core:test --tests "com.filamentvision.core.vision.VisionGoldenTest"`.

**Done / commit / rollback:** Channel order is profile-driven and bounded. Commit `feat(udp): decode rgb888 payloads`. Revert affects only RGB888.

## Task 12: Decode JPEG only after metadata safety validation

**Files:**
- Create `backend/src/main/kotlin/com/filamentvision/backend/input/decoder/JpegDecoder.kt`
- Create `backend/src/main/kotlin/com/filamentvision/backend/input/decoder/JpegMetadataReader.kt`
- Create `backend/src/test/kotlin/com/filamentvision/backend/input/decoder/JpegDecoderTest.kt`
- Create `backend/src/test/resources/udp/jpeg-valid-2x2.jpg`
- Create `backend/src/test/resources/udp/jpeg-oversized-dimensions.jpg`

**Tests first / RED:** Cover valid decode, invalid bytes, compressed maxFrameBytes, optional expected dimensions, oversized metadata width/height/pixels/decoded bytes, and an injectable raster allocator/reader proving full raster allocation is never requested before metadata validation. Verify metadata is reused for Inspector result rather than decoded twice.

**Minimal GREEN:** Use Backend-only `ImageIO` reader metadata calls, validate dimensions/stride/ARGB bytes through the shared policy, then decode once into `DecodedImage`; close streams/readers deterministically.

**Focused:** `./gradlew.bat :backend:test --tests "com.filamentvision.backend.input.decoder.JpegDecoderTest"`

**Regression:** Run separately: `./gradlew.bat :backend:test`; then `./gradlew.bat :core:verifyCorePlatformBoundary`.

**Done / commit / rollback:** Compressed and decoded bounds are demonstrably separate. Commit `feat(udp): decode jpeg after metadata validation`. Revert removes JPEG adapter/dependency usage only.

## Task 13: Decode direct diameter payloads

**Files:**
- Create `backend/src/main/kotlin/com/filamentvision/backend/input/decoder/DiameterDecoder.kt`
- Create `backend/src/test/kotlin/com/filamentvision/backend/input/decoder/DiameterDecoderTest.kt`

**Tests first / RED:** Cover FLOAT32 Big/Little Endian, FLOAT64, INT16, INT32, ASCII decimal/scientific notation, exact binary lengths, ASCII 128-byte cap, malformed/non-finite numbers, finite scale/offset result, and MM/MICROMETER/INCH normalization.

**Minimal GREEN:** Return Backend `DecodedDiameter` with raw value/encoding/unit and normalized millimetres; do not create `CameraMeasurement` or fabricate confidence/pixel width.

**Focused:** `./gradlew.bat :backend:test --tests "com.filamentvision.backend.input.decoder.DiameterDecoderTest"`

**Regression:** `./gradlew.bat :backend:test`

**Done / commit / rollback:** All five encodings and both byte orders are exact. Commit `feat(udp): decode direct diameter payloads`. Revert affects only DIAMETER.

## Task 14: Preserve Direct Diameter evidence through the Core fusion boundary

**Files:**
- Create `core/src/main/kotlin/com/filamentvision/core/input/CameraInput.kt`
- Modify `core/src/main/kotlin/com/filamentvision/core/vision/DetectionPipeline.kt`
- Create `core/src/main/kotlin/com/filamentvision/core/fusion/DirectDiameterMeasurementAdapter.kt`
- Modify `core/src/test/kotlin/com/filamentvision/core/fusion/FusionGoldenTest.kt`
- Create `core/src/test/kotlin/com/filamentvision/core/fusion/DirectDiameterMeasurementAdapterTest.kt`
- Modify `backend/src/main/kotlin/com/filamentvision/backend/api/dto/MeasurementDtos.kt`
- Modify `backend/src/main/kotlin/com/filamentvision/backend/api/dto/DtoMappers.kt`
- Modify `backend/src/main/kotlin/com/filamentvision/backend/persistence/sqlite/SqliteRepositories.kt`
- Modify `backend/src/test/kotlin/com/filamentvision/backend/api/RestContractTest.kt`

**Tests first / RED:** Assert direct evidence retains raw encoding/unit, normalized diameter, nullable device confidence, sequence and both timestamp provenances; adapter sets `source=DIRECT_DIAMETER`, `pixelWidth=null`, and profile fusion confidence; Vision output remains `source=VISION` with real pixel width. Freeze existing Fusion golden outputs before changing the model and assert them unchanged afterward.

**Minimal GREEN:** Add `CameraMeasurementSource` as a defaulted field, make pixel width nullable, add the explicit adapter, expose nullable/source fields in DTO mapping, and make the existing SQLite row mapper preserve nullable pixel width while treating pre-V003 rows as VISION in memory. Do not add the source column until Task 18, and do not modify fusion equations or pairing policy.

**Focused:** Run separately: `./gradlew.bat :core:test --tests "com.filamentvision.core.fusion.DirectDiameterMeasurementAdapterTest" --tests "com.filamentvision.core.fusion.FusionGoldenTest"`; then `./gradlew.bat :backend:test --tests "com.filamentvision.backend.api.RestContractTest"`.

**Regression:** Run separately: `./gradlew.bat :core:test`; then `./gradlew.bat :backend:test`.

**Done / commit / rollback:** No path emits `pixelWidth=0`; default fusion confidence is not labeled device confidence. Commit `feat(core): preserve direct diameter evidence`. Revert is model/API atomic and precedes persistence migration.

## Task 15: Let MonitoringRuntime consume CameraInput without changing ownership

**Files:**
- Create `core/src/main/kotlin/com/filamentvision/core/input/CameraInputSource.kt`
- Modify `core/src/main/kotlin/com/filamentvision/core/input/ImageInputSource.kt`
- Modify `core/src/main/kotlin/com/filamentvision/core/runtime/MonitoringRuntime.kt`
- Modify `core/src/test/kotlin/com/filamentvision/core/runtime/RuntimeTestSupport.kt`
- Create `core/src/test/kotlin/com/filamentvision/core/runtime/MonitoringRuntimeCameraInputTest.kt`
- Modify `core/src/test/kotlin/com/filamentvision/core/runtime/MonitoringRuntimeConcurrencyTest.kt`
- Modify `backend/src/main/kotlin/com/filamentvision/backend/simulator/SimulatorImageInputSource.kt`
- Modify `backend/src/test/kotlin/com/filamentvision/backend/simulator/SimulatorImageInputSourceTest.kt`

**Tests first / RED:** Feed mixed image/direct inputs, prove image still executes Vision, direct bypasses Vision but enters pairing/fusion, pairing uses monotonic receive time, source timestamp is ignored, and frames close on every path. Re-run concurrent/repeated Start, Stop, failure, close, and restart tests with `activeProducerCount <= 1`.

**Minimal GREEN:** Change Runtime to one `CameraInputSource`; adapt legacy `ImageInputSource` and simulator to `ImageFrameInput`. Select input handling inside the existing acceptance lock; leave ownership/finalization algorithms intact.

**Focused:** `./gradlew.bat :core:test --tests "com.filamentvision.core.runtime.MonitoringRuntimeCameraInputTest" --tests "com.filamentvision.core.runtime.MonitoringRuntimeConcurrencyTest"`

**Regression:** Run separately: `./gradlew.bat :core:test`; then `./gradlew.bat :backend:test --tests "com.filamentvision.backend.simulator.SimulatorImageInputSourceTest" --tests "com.filamentvision.backend.simulator.SimulatorScenarioVisionTest"`.

**Done / commit / rollback:** Runtime still owns exactly one producer and all existing golden behavior is equal. Commit `refactor(core): accept protocol neutral camera inputs`. Revert restores the compatibility adapter and old constructor as one unit.

## Task 16: Assemble the UDP processing chain and two-camera source

**Files:**
- Create `backend/src/main/kotlin/com/filamentvision/backend/input/runtime/CompiledUdpInputProfile.kt`
- Create `backend/src/main/kotlin/com/filamentvision/backend/input/runtime/UdpCameraProcessor.kt`
- Create `backend/src/main/kotlin/com/filamentvision/backend/input/runtime/UdpCameraInputSource.kt`
- Create `backend/src/test/kotlin/com/filamentvision/backend/input/runtime/UdpCameraProcessorTest.kt`
- Create `backend/src/test/kotlin/com/filamentvision/backend/input/runtime/UdpCameraInputSourceTest.kt`

**Tests first / RED:** Verify the exact layer order for both modes, DATAGRAM then FRAME framing, image and direct outputs, parser/framing/reassembly/decoder errors isolated to one input, A/B socket and state isolation, timestamp provenance, and two child receivers under one source producer reservation. Assert source start twice does not duplicate receiver jobs and stop releases both. With the exact two-entry keyed mailbox, hold Runtime collection, offer two inputs for Camera A and one for B, and prove only A's older pending input is closed/dropped with `preAdmissionDroppedInputs=1`; after A or B is removed for collection, overwrite/reuse the socket receive array and prove the admitted input is unchanged. Assert no Preview/Inspector backpressure changes mailbox contents.

**Minimal GREEN:** Compile validated profiles into immutable parser/reassembler/framing/decoder chains. `UdpCameraInputSource.activeProducerCount` is 0 or 1 for its overall lifecycle, never 2 for cameras. Implement the exact keyed mailbox described above: capacity two total/one per Camera, replace only the older same-Camera pending item, close owned image resources, and increment per-camera `preAdmissionDroppedInputs`. Define admission as removal followed by invocation of the Runtime collector; after admission use direct Flow backpressure with no second queue or overflow policy. Preview/Inspector taps remain outside this path.

**Focused:** `./gradlew.bat :backend:test --tests "com.filamentvision.backend.input.runtime.UdpCameraProcessorTest" --tests "com.filamentvision.backend.input.runtime.UdpCameraInputSourceTest"`

**Regression:** Run separately: `./gradlew.bat :core:test --tests "com.filamentvision.core.runtime.MonitoringRuntimeConcurrencyTest" --tests "com.filamentvision.core.runtime.MonitoringRuntimeFailureTest"`; then `./gradlew.bat :backend:test`.

**Done / commit / rollback:** A complete in-memory UDP path exists without persistence/routes/Inspector. Commit `feat(udp): assemble camera input source`. Revert leaves all individual layers independently tested.

## Task 17: Implement revisioned Save/Apply and atomic runtime reconfiguration

**Files:**
- Create `backend/src/main/kotlin/com/filamentvision/backend/input/profile/UdpInputProfileRepository.kt`
- Create `backend/src/main/kotlin/com/filamentvision/backend/input/runtime/UdpInputConfigurationService.kt`
- Create `backend/src/main/kotlin/com/filamentvision/backend/input/runtime/UdpInputRouter.kt`
- Create `backend/src/test/kotlin/com/filamentvision/backend/input/runtime/UdpInputConfigurationServiceTest.kt`
- Create `backend/src/test/kotlin/com/filamentvision/backend/input/runtime/UdpInputRouterTest.kt`

**Tests first / RED:** Cover saved/applied revision independence, expected-saved CAS, invalid candidate never applied, parser-only immutable atomic swap, receiver restart for enabled/sourceIp/port/mode, bind failure rollback to old receiver/profile, old in-flight state cleared/counted, repeated Apply idempotence, Camera A-only change not restarting B, concurrent Apply serialization, and unchanged `MonitoringRuntime.activeProducerCount` during monitoring. Add deterministic barriers for Apply revision N racing with Save N+1; Runtime swap succeeding before `markApplied` returns CAS false; Runtime swap succeeding before repository persistence throws; successful compensation restoring old runtime profile, old effective receiver configuration, and old persisted applied revision; and unchanged unrelated Camera B state when only A failed.

**Minimal GREEN:** Add a repository port and application service; serialize Save and Apply with the same configuration mutex. Compile before mutation. `prepareAndSwap` returns a rollback token owning old compiled profiles/receivers; call transactional `markApplied` only after the swap works; publish/commit the runtime token only after persistence succeeds. If CAS returns false or persistence throws, rollback the runtime/receiver token and verify the old triple before returning. Start replacement receiver before retiring the old one when ports permit; for same-port replacement, stop/bind with explicit rollback. Never persist first and never expose a new runtime profile without its matching persisted applied revision.

**Focused:** `./gradlew.bat :backend:test --tests "com.filamentvision.backend.input.runtime.UdpInputConfigurationServiceTest" --tests "com.filamentvision.backend.input.runtime.UdpInputRouterTest"`

**Regression:** Run separately: `./gradlew.bat :core:test --tests "com.filamentvision.core.runtime.MonitoringRuntimeConcurrencyTest" --tests "com.filamentvision.core.runtime.MonitoringRuntimeFailureTest" --tests "com.filamentvision.core.config.ConfigurationServiceTest"`; then `./gradlew.bat :backend:test`.

**Done / commit / rollback:** After every success or failure, runtime applied profile, receiver effective configuration, and persisted applied revision identify the same revision; concurrent Save may advance only saved revision. Every failed Apply leaves the old working triple intact. Commit `feat(udp): apply revisioned input profiles atomically`. Revert removes the application service/router while leaving source/layers intact.

## Task 18: Persist UDP revisions and measurement evidence in SQLite

**Files:**
- Create `backend/src/main/resources/db/migration/V003__udp_input_profiles_and_measurement_source.sql`
- Modify `backend/src/main/kotlin/com/filamentvision/backend/persistence/sqlite/SqliteDatabase.kt`
- Create `backend/src/main/kotlin/com/filamentvision/backend/persistence/sqlite/UdpInputProfileCodec.kt`
- Modify `backend/src/main/kotlin/com/filamentvision/backend/persistence/sqlite/SqliteRepositories.kt`
- Modify `backend/src/test/kotlin/com/filamentvision/backend/persistence/sqlite/SqliteRepositoryContractTest.kt`
- Modify `backend/src/test/kotlin/com/filamentvision/backend/persistence/sqlite/InMemoryRepositoryContractTest.kt`
- Create `backend/src/test/kotlin/com/filamentvision/backend/persistence/sqlite/UdpInputProfileCodecTest.kt`

**Tests first / RED:** Upgrade V001/V002 databases to V003; round-trip every profile enum/field; preserve saved/applied revisions and CAS; reject corrupt stored profiles; round-trip VISION and DIRECT_DIAMETER measurement source plus nullable pixel width; map legacy rows to VISION; prove failed apply does not advance persisted applied revision. Add a repository fault fixture showing `markApplied` is one transaction with the contract “false or exception means no commit,” then repeat Runtime-swap-success/persistence-failure through the real SQLite adapter and assert the runtime profile, effective receiver profile, and database applied revision all remain old.

**Minimal GREEN:** Add versioned UDP revision/state tables and `a_source`/`b_source` columns. Keep SQL behind `SqliteRepositories`; use an explicit versioned codec. Update schema support to 1..3 with transactional migration.

**Focused:** `./gradlew.bat :backend:test --tests "com.filamentvision.backend.persistence.sqlite.UdpInputProfileCodecTest" --tests "com.filamentvision.backend.persistence.sqlite.SqliteRepositoryContractTest"`

**Regression:** Run separately: `./gradlew.bat :backend:test`; then `./gradlew.bat :core:test`.

**Done / commit / rollback:** A fresh and upgraded DB are equivalent at repository level; repository exceptions cannot represent an already-committed applied revision; runtime applied profile, receiver effective configuration, and persisted applied revision are always one revision after success or compensation rollback. Commit `feat(sqlite): persist UDP profiles and evidence source`. Roll back code plus V003 before any later database is treated as production; never edit V001/V002.

## Task 19: Expose authoritative UDP profile Save/Apply REST contracts

**Files:**
- Create `backend/src/main/kotlin/com/filamentvision/backend/api/dto/UdpInputDtos.kt`
- Create `backend/src/main/kotlin/com/filamentvision/backend/api/dto/UdpInputDtoMappers.kt`
- Modify `backend/src/main/kotlin/com/filamentvision/backend/application/BackendApplication.kt`
- Modify `backend/src/main/kotlin/com/filamentvision/backend/ComposedBackendApplication.kt`
- Modify `backend/src/main/kotlin/com/filamentvision/backend/api/Routes.kt`
- Modify `backend/src/test/kotlin/com/filamentvision/backend/api/ApiFixture.kt`
- Create `backend/src/test/kotlin/com/filamentvision/backend/api/UdpInputConfigurationApiTest.kt`

**Tests first / RED:** Contract-test `GET/PUT /api/v1/devices/{deviceId}/udp-input-configuration` and `POST .../udp-input-configuration/apply`: full A/B mapping, command ID replay, revision/CAS conflict, validation 400 with field paths, saved/applied independence, parser-swap/restart/rollback outcome, unknown device, and no internal exception/SQL leakage.

**Minimal GREEN:** Add DTOs separate from domain and façade methods delegating to Task 17. Backend validation remains authoritative even if frontend validated first.

**Focused:** `./gradlew.bat :backend:test --tests "com.filamentvision.backend.api.UdpInputConfigurationApiTest"`

**Regression:** `./gradlew.bat :backend:test --tests "com.filamentvision.backend.api.RestContractTest" --tests "com.filamentvision.backend.api.RequestMediaContractTest" --tests "com.filamentvision.backend.api.CompositionEventContractTest" --tests "com.filamentvision.backend.api.UdpInputConfigurationApiTest"`

**Done / commit / rollback:** REST can save and apply complete independent A/B profiles. Commit `feat(api): expose UDP input configuration`. Revert removes routes/DTOs while persistence remains intact.

## Task 20: Add bounded per-camera UDP diagnostics and REST output

**Files:**
- Create `backend/src/main/kotlin/com/filamentvision/backend/input/diagnostics/UdpDiagnostics.kt`
- Create `backend/src/test/kotlin/com/filamentvision/backend/input/diagnostics/UdpDiagnosticsTest.kt`
- Modify `backend/src/main/kotlin/com/filamentvision/backend/input/runtime/UdpCameraProcessor.kt`
- Modify `backend/src/main/kotlin/com/filamentvision/backend/application/BackendApplication.kt`
- Modify `backend/src/main/kotlin/com/filamentvision/backend/ComposedBackendApplication.kt`
- Modify `backend/src/main/kotlin/com/filamentvision/backend/api/dto/StateDtos.kt`
- Modify `backend/src/main/kotlin/com/filamentvision/backend/api/dto/DtoMappers.kt`
- Modify `backend/src/main/kotlin/com/filamentvision/backend/api/Routes.kt`
- Create `backend/src/test/kotlin/com/filamentvision/backend/api/UdpDiagnosticsApiTest.kt`

**Tests first / RED:** Drive each defined counter and timestamp, prove immutable A/B isolation under concurrency, chunk-only fields are null/not-applicable in SINGLE_DATAGRAM, source-filter and oversized/truncated have distinct counters, and GET `/api/v1/devices/{deviceId}/cameras/{cameraId}/udp-diagnostics` is stable.

**Minimal GREEN:** Use fixed atomic counters/timestamps and construct snapshots on read; do not append events/history. Wire result hooks from receiver/parser/reassembler/framing/decoder.

**Focused:** `./gradlew.bat :backend:test --tests "com.filamentvision.backend.input.diagnostics.UdpDiagnosticsTest" --tests "com.filamentvision.backend.api.UdpDiagnosticsApiTest"`

**Regression:** Run separately: `./gradlew.bat :core:test --tests "com.filamentvision.core.metrics.MonitoringMetricsTest"`; then `./gradlew.bat :backend:test --tests "com.filamentvision.backend.api.UdpDiagnosticsApiTest" --tests "com.filamentvision.backend.e2e.LongRunBoundednessTest"`.

**Done / commit / rollback:** All approved counters are attributable to one camera and bounded. Commit `feat(udp): publish input diagnostics`. Revert removes diagnostic observation, not processing.

## Task 21: Implement Packet Inspector leases and bounded latest snapshots

**Files:**
- Create `backend/src/main/kotlin/com/filamentvision/backend/input/inspector/PacketInspector.kt`
- Create `backend/src/main/kotlin/com/filamentvision/backend/input/inspector/PacketInspectorModels.kt`
- Create `backend/src/test/kotlin/com/filamentvision/backend/input/inspector/PacketInspectorTest.kt`
- Modify `backend/src/main/kotlin/com/filamentvision/backend/input/transport/UdpReceiver.kt`
- Modify `backend/src/main/kotlin/com/filamentvision/backend/input/runtime/UdpCameraProcessor.kt`

**Tests first / RED:** Cover lease create/release/expiry, camera isolation, latest-only replacement, sequence increments, requested 64/128/256 sizes, hard max 512, no full datagram/frame retention, no history, raw vs parsed-frame metadata, source mismatch observable as `SOURCE_IP_MISMATCH` but rejected from parser, and disabled mode performing no preview copy/Hex formatting/heavy parsed allocation. Reuse and overwrite the receiver's backing array for the next datagram and prove the prior Inspector snapshot preserves only its own bounded preview copy unchanged.

**Minimal GREEN:** Store one bounded raw byte copy and lightweight immutable metadata per camera only while a valid lease exists. Attach no control dependency to processing; catch Inspector failures at each tap.

**Focused:** `./gradlew.bat :backend:test --tests "com.filamentvision.backend.input.inspector.PacketInspectorTest"`

**Regression:** `./gradlew.bat :backend:test --tests "com.filamentvision.backend.input.runtime.UdpCameraProcessorTest" --tests "com.filamentvision.backend.input.runtime.UdpCameraInputSourceTest" --tests "com.filamentvision.backend.input.transport.UdpReceiverTest" --tests "com.filamentvision.backend.api.PreviewWebSocketBackpressureTest" --tests "com.filamentvision.backend.api.EventWebSocketTest"`

**Done / commit / rollback:** Disabling/expiring the last lease returns capture overhead to counter-only. Commit `feat(udp): capture bounded inspector snapshots`. Revert removes taps with no pipeline behavior change.

## Task 22: Expose lease-controlled Packet Inspector REST polling

**Files:**
- Create `backend/src/main/kotlin/com/filamentvision/backend/api/dto/PacketInspectorDtos.kt`
- Modify `backend/src/main/kotlin/com/filamentvision/backend/application/BackendApplication.kt`
- Modify `backend/src/main/kotlin/com/filamentvision/backend/ComposedBackendApplication.kt`
- Modify `backend/src/main/kotlin/com/filamentvision/backend/api/Routes.kt`
- Modify `backend/src/test/kotlin/com/filamentvision/backend/api/ApiFixture.kt`
- Create `backend/src/test/kotlin/com/filamentvision/backend/api/PacketInspectorApiTest.kt`

**Tests first / RED:** Cover the exact POST lease, DELETE lease, and GET inspector paths; invalid/expired/wrong-camera lease; previewBytes values and 512 cap; Raw Datagram and Parsed Frame DTOs; transport fields; source mismatch; unchanged sequence response/ETag; on-demand uppercase Hex formatting; and proof API never returns full frame bytes.

**Minimal GREEN:** Add façade methods and DTO mapping. Format preview only in the GET request path. Use sequence as ETag and return 304 when `If-None-Match` matches.

**Focused:** `./gradlew.bat :backend:test --tests "com.filamentvision.backend.api.PacketInspectorApiTest"`

**Regression:** `./gradlew.bat :backend:test --tests "com.filamentvision.backend.api.PacketInspectorApiTest" --tests "com.filamentvision.backend.api.RestContractTest" --tests "com.filamentvision.backend.api.RequestMediaContractTest" --tests "com.filamentvision.backend.input.inspector.PacketInspectorTest"`

**Done / commit / rollback:** The four design-specified Inspector/diagnostic routes are exact and bounded. Commit `feat(api): expose packet inspector polling`. Revert removes HTTP access but not capture state.

## Task 23: Wire production composition to UDP without deleting simulator fixtures

**Files:**
- Modify `backend/src/main/kotlin/com/filamentvision/backend/BackendComposition.kt`
- Modify `backend/src/main/kotlin/com/filamentvision/backend/ComposedBackendApplication.kt`
- Modify `backend/src/main/kotlin/com/filamentvision/backend/BackendApplication.kt`
- Create `backend/src/main/kotlin/com/filamentvision/backend/input/runtime/BackendInputSourceFactory.kt`
- Modify `backend/src/test/kotlin/com/filamentvision/backend/api/CompositionEventContractTest.kt`
- Modify `backend/src/test/kotlin/com/filamentvision/backend/e2e/SimulatedMonitoringE2eTest.kt`
- Create `backend/src/test/kotlin/com/filamentvision/backend/input/runtime/ProductionUdpCompositionTest.kt`

**Tests first / RED:** Prove default application composition builds `UdpCameraInputSource`, restores applied UDP revisions, supports no-profile state without fake samples, shuts down sockets before database/scope close, and preserves one producer. Existing simulator e2e must construct an explicit simulator test factory and retain identical golden behavior.

**Minimal GREEN:** Inject a `BackendInputSourceFactory`; production `open` selects UDP, test helper selects simulator. Load the applied profile into router without allowing UI/routes to own receiver jobs.

**Focused:** `./gradlew.bat :backend:test --tests "com.filamentvision.backend.input.runtime.ProductionUdpCompositionTest" --tests "com.filamentvision.backend.e2e.SimulatedMonitoringE2eTest"`

**Regression:** Run separately: `./gradlew.bat :core:test --tests "com.filamentvision.core.runtime.MonitoringRuntimeConcurrencyTest" --tests "com.filamentvision.core.runtime.MonitoringRuntimeFailureTest"`; then `./gradlew.bat :backend:test --tests "com.filamentvision.backend.api.CompositionEventContractTest" --tests "com.filamentvision.backend.api.PreviewWebSocketBackpressureTest" --tests "com.filamentvision.backend.api.EventWebSocketTest" --tests "com.filamentvision.backend.BackendSmokeTest"`.

**Done / commit / rollback:** Running Backend requires no simulator; tests still retain it as fixture. Commit `refactor(backend): make UDP the production input source`. Revert restores composition selection only, not UDP implementation.

## Task 24: Add frontend UDP contracts, API client, and immediate validation

**Files:**
- Create `frontend/src/domain/udpInput.ts`
- Create `frontend/src/config/udpInputProfile.ts`
- Create `frontend/src/config/udpInputProfile.test.ts`
- Create `frontend/src/services/BackendApiClient.ts`
- Create `frontend/src/services/BackendApiClient.test.ts`

**Tests first / RED:** Validate conditional fields, IPv4-only input, distinct enabled ports, HEX/ASCII feedback, bounds, format recommendations without forcing transport mode, and DTO round-trip. Mock fetch for GET/Save/Apply/diagnostics/lease routes, correlation/command IDs, Backend validation errors, CAS conflict, 304 Inspector polling, and abort cleanup.

**Minimal GREEN:** Add typed DTOs/client and UI-only validation mirroring useful Backend rules. Preserve Backend error field paths as final authority. Do not wire the client into React until Task 25, and do not replace the unrelated local monitoring-threshold store.

**Focused:** From the repository root: `npm --prefix frontend test -- src/config/udpInputProfile.test.ts src/services/BackendApiClient.test.ts`

**Regression:** Run `npm --prefix frontend test -- src/state/MonitoringStore.test.ts src/App.test.tsx`; then `npm --prefix frontend run build`.

**Done / commit / rollback:** Frontend can call stable Backend contracts but renders no new controls yet. Commit `feat(frontend): add UDP backend contracts`. Revert is frontend-only.

## Task 25: Build independent Camera A/B Settings with Save, Apply, and diagnostics

**Files:**
- Create `frontend/src/components/UdpCameraSettingsCard.tsx`
- Create `frontend/src/components/UdpDiagnosticsPanel.tsx`
- Create `frontend/src/components/UdpInputSettings.tsx`
- Create `frontend/src/components/UdpInputSettings.test.tsx`
- Modify `frontend/src/components/SettingsPanel.tsx`
- Modify `frontend/src/App.tsx`
- Modify `frontend/src/main.tsx`
- Modify `frontend/src/styles.css`
- Modify `frontend/src/App.test.tsx`

**Tests first / RED:** Render independent A/B drafts and all required common/format-specific fields; conditionally show Advanced Transport for chunked mode and Advanced UDP Framing; label Transport Header separately; preserve edits across cards; Save full pair; Apply saved revision; show saved/applied revisions, parser swap/restart/rollback, authoritative Backend field errors, per-camera diagnostics, and IPv4-only guidance.

**Minimal GREEN:** Compose accessible controlled forms around Task 24 client. Keep existing monitoring-threshold form intact. Poll diagnostics only while Settings is mounted and cancel on unmount.

**Focused:** From the repository root: `npm --prefix frontend test -- src/components/UdpInputSettings.test.tsx src/App.test.tsx`

**Regression:** Run separately: `npm --prefix frontend test`; `npm --prefix frontend run lint`; `npm --prefix frontend run build`.

**Done / commit / rollback:** All design-listed Settings controls work against Backend; Save is not Apply. Commit `feat(frontend): configure UDP camera inputs`. Revert removes UI while API remains stable.

## Task 26: Build the Packet Inspector UI lifecycle

**Files:**
- Create `frontend/src/components/PacketInspector.tsx`
- Create `frontend/src/components/PacketInspector.test.tsx`
- Modify `frontend/src/components/UdpCameraSettingsCard.tsx`
- Modify `frontend/src/styles.css`

**Tests first / RED:** Cover lease start/stop/unmount release, 2 Hz polling with fake timers, 64/128/256 selection, Raw Datagram vs Parsed Frame, transport/application region labels, structured fields, SOURCE_IP_MISMATCH, Freeze retaining current DTO while Backend polling continues, Resume, Copy Hex, Copy Debug Info, 304 unchanged, expiry recovery, and no request when closed.

**Minimal GREEN:** Add the inspector panel with bounded DTO rendering and Clipboard calls. Freeze is local display state only; never request or retain a full payload.

**Focused:** From the repository root: `npm --prefix frontend test -- src/components/PacketInspector.test.tsx src/components/UdpInputSettings.test.tsx`

**Regression:** Run separately: `npm --prefix frontend test`; `npm --prefix frontend run lint`; `npm --prefix frontend run build`.

**Done / commit / rollback:** UI lease lifetime and bounded polling are deterministic. Commit `feat(frontend): add packet inspector`. Revert removes Inspector UI without changing Settings Save/Apply.

## Task 27: Prove the real UDP integration and complete regression gate

**Files:**
- Create `backend/src/test/kotlin/com/filamentvision/backend/e2e/UdpInputEndToEndTest.kt`
- Create `backend/src/test/kotlin/com/filamentvision/backend/e2e/UdpTestSender.kt`
- Modify `backend/src/test/kotlin/com/filamentvision/backend/e2e/LongRunBoundednessTest.kt`
- Create `docs/implementation/udp-input-and-packet-inspector-verification.md`

**Tests first / RED:** Before any glue fix, add loopback sender cases for structured GRAY8 multi-chunk/out-of-order, invalid Application Header, oversized/sentinel datagram, source mismatch, single-datagram DIAMETER, independent A/B inputs, Save/Apply port restart, Inspector Raw/Parsed views, and reliable measurement/alarm/session/error delivery while preview and Inspector are congested. RED must identify only missing integration wiring.

**Minimal GREEN:** Add only test harness/glue required to exercise the production composition. Do not add protocol features. Extend boundedness assertions for reassembly bytes, tombstones, Inspector snapshot size, receiver jobs, command cache, and `activeProducerCount <= 1` across repeated Apply/start/stop/restart.

**Focused:** `./gradlew.bat :backend:test --tests "com.filamentvision.backend.e2e.UdpInputEndToEndTest" --tests "com.filamentvision.backend.e2e.LongRunBoundednessTest"`

**Full regression gate:**

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

The Kotlin suite must explicitly include existing Vision/Fusion/Measurement/Alarm/Statistics/Session, runtime concurrency/failure, SQLite conformance, Preview backpressure, reliable Event WebSocket, simulator fixture, REST/media contracts, smoke, and long-run tests.

**Done / commit / rollback:** Verification report records commands, pass counts, structured wire fixtures, boundedness observations, and confirms production does not depend on simulator. Commit `test(udp): verify end-to-end input and inspector`. Revert removes only gate fixtures/report; all feature commits remain independently testable.

---

## Dependency and Rollback Chain

```text
Profile model
  -> Transport parser -> Receive bounds -> IPv4 receiver
  -> Single/Structured reassembly -> Application framing
  -> Allocation policy -> GRAY8/BINARY/RGB888/JPEG/DIAMETER
  -> Direct evidence -> CameraInput Runtime boundary
  -> UDP source -> atomic Save/Apply
  -> SQLite -> REST -> diagnostics -> Inspector backend
  -> production composition -> frontend client -> Settings -> Inspector UI
  -> real UDP integration/regression gate
```

Each arrow is a commit boundary. A failed task is rolled back only to its immediate predecessor. Schema migration V003 is never edited after merge; a correction requires V004. Core golden changes, 34-byte wire changes, and new transport modes require a separate design review rather than an in-plan patch.

## Phase Gate

Implementation is complete only when all 27 task commits are individually reviewable, the final commands above are green, the real loopback UDP cases pass, memory/state bounds remain stable, A/B are isolated, Inspector can be entirely disabled without changing output, and `activeProducerCount` never exceeds one. Phase 3 or any unapproved hardware protocol remains out of scope.
