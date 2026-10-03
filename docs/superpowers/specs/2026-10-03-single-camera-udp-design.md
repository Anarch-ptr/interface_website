# FilamentVision Single-Camera UDP Design Amendment

日期：2026-10-03
状态：待用户审核
基线：`a8c039ad51d426569e2d41e3cd3ba42999c94a6a`
范围：从已完成的双 Camera UDP 实现迁移到一个 Raspberry Pi、一个 Camera、一个 UDP endpoint；本 Amendment 不修改 34-byte Structured Transport Header。

## 1. Amendment authority

本文 supersede `docs/superpowers/specs/2026-10-02-udp-input-and-packet-inspector-design.md` 中以下 production 假设：

- Camera A / Camera B 是两个 production image stream；
- 两个独立 listen port、receiver、reassembly identity、diagnostics 和 Inspector；
- production runtime 必须进行 A/B pairing 与 fusion；
- production UI 显示 A/B preview、confidence 与 view difference；
- production 可以选择 `DIAMETER` payload 或 `SINGLE_DATAGRAM` image transport；
- Application Header/Footer 可以使用 ASCII、任意长度、DATAGRAM/FRAME scope 或 `strip=false`；
- Simulator 或前端 Mock source 可以作为 production default。

原设计以下约束继续有效：IPv4 source filtering、sentinel truncation、34-byte Structured Transport Header、bounded structured reassembly、checked allocation、JPEG metadata-first validation、Save/Apply revision/CAS、rollback consistency、Inspector isolation、preview latest-first、measurement/alarm/session reliable delivery、one authoritative runtime producer，以及 Core 不依赖 Android/Ktor/SQLite/Room/Compose。

本文未覆盖的原设计条款仅在不与本 Amendment 冲突时继续有效。

## 2. Confirmed production topology

```text
Physical Camera (~8 MP)
  -> cable
Raspberry Pi
  -> Wi-Fi / LAN
  -> one IPv4 UDP endpoint
Backend UdpReceiver
  -> Datagram Envelope validation
  -> Structured Transport Header parser
  -> StructuredChunkFrameReassembler
  -> selected image decoder
  -> CameraInput.Image
  -> VisionProcessor
  -> single-camera OfficialMeasurement
  -> Alarm / Statistics / SQLite persistence / reliable events
  -> one latest-first Preview stream
  -> Web Monitoring UI
```

There is no production second Camera, second UDP endpoint, source multiplexing, or direct-diameter UDP stream. `SingleDatagramFrameReassembler`, `FusionEngine`, and dual-view golden fixtures may remain as independently tested generic utilities, but production composition does not instantiate them.

## 3. Migration approach

### 3.1 Chosen: delta adapters with explicit single-stream domain

Keep the proven receiver, transport codec, reassembler, decoders, runtime ownership, persistence transaction, event, preview and Inspector machinery. Replace the dual profile/source/composition/API/UI boundaries with single-stream equivalents. Introduce characterization tests before changing each boundary.

This removes obsolete production concepts without risking a rewrite of packet safety algorithms.

### 3.2 Rejected: keep Camera A and disable Camera B

This would minimize code changes but would leave `cameraA`, `cameraB`, two profile slots, failure statuses and dual UI semantics in production contracts. It does not satisfy the confirmed topology.

### 3.3 Rejected: rewrite UDP around the Raspberry Pi

A rewrite would discard the already verified unsigned parsing, ownership, truncation, reassembly and boundedness behavior. It adds risk without providing a required capability.

## 4. Production identity model

Production uses one stable logical image source:

```text
deviceId = "filamentvision-1"
cameraId = "camera"
transport compatibility camera byte = 1
```

`cameraId="camera"` is Core/API/preview identity. It is not an instruction to edit the structured wire layout. The byte at Structured Header offset 6 remains present and must equal `1`. It is documented as `streamId/reservedCameraId` for v1 compatibility. Value `2` and any other value are rejected by the production parser. The header remains exactly 34 bytes with every other offset and byte order unchanged.

## 5. Final wire layout

Every production UDP Datagram has this exact layout:

```text
offset 0..1       2-byte configurable HEX Datagram Header
offset 2..35      existing 34-byte Structured Transport Header
offset 36..N-3    image chunk payload; length = Structured payloadLength
offset N-2..N-1   2-byte configurable HEX Datagram Footer
```

Equivalent notation:

```text
[2-byte Header][34-byte Structured Header][Image Chunk Payload][2-byte Footer]
```

Processing order is fixed:

```text
UdpReceiver
  -> source IPv4 filter
  -> sentinel/receive-size guard
  -> DatagramEnvelopeParser validates and strips 2 + 2 bytes
  -> TransportDatagramParser parses the unchanged 34-byte header
  -> StructuredChunkFrameReassembler
  -> PayloadDecoder
  -> CameraInput.Image
```

The outer Header/Footer are not part of `payloadLength` or `framePayloadBytes`. `payloadLength` continues to mean the bytes after the 34-byte Structured Header in that Datagram. `framePayloadBytes` continues to mean the total image payload entering reassembly. A valid raw Datagram length is exactly `2 + 34 + payloadLength + 2`.

The receiver accepted raw byte limit is checked `38 + maxChunkPayloadBytes`. When this is below the IPv4 UDP payload maximum, one sentinel byte is added. A Datagram filling the sentinel capacity is dropped as `DATAGRAM_TOO_LARGE_OR_TRUNCATED` before envelope or transport parsing.

## 6. Exact 2-byte HEX envelope

Production replaces `ApplicationFramingProfile` with:

```kotlin
data class DatagramEnvelopeProfile(
    val headerHex: String,
    val footerHex: String,
)
```

Both values are mandatory and normalize to uppercase, space-separated two-byte text. Accepted examples `AA 01`, `AA01`, and `aa01` all persist and return as `AA 01`. Empty input, `AA`, `AA 01 02`, `GG 01`, odd digits and non-HEX characters are rejected. Runtime compilation converts each value once into an owned `ByteArray(size = 2)`.

Fresh installations use editable, disabled defaults `headerHex="AA 01"` and `footerHex="0D 0A"`. These defaults are not protocol constants; the profile stays disabled until the user verifies the Raspberry Pi values and applies the revision.

`DatagramEnvelopeParser` returns the stripped structured bytes plus matched header/footer metadata. It does not parse structured fields and does not allocate the image payload. Header failure increments `invalidHeaderCount`; footer failure increments `invalidFooterCount`. With an Inspector lease, the bounded raw snapshot records the actual two boundary bytes and match flags. Without a lease, invalid packets create no hex strings or preview copies.

The generic `ApplicationFramingParser` may remain for non-production characterization tests, but production source, DTO, persistence and UI no longer expose it.

The envelope and payload selections are Backend expected-input configuration only. This release defines no Raspberry Pi remote-control or configuration channel. Saving or applying `JPEG`, `GRAY8`, `BINARY`, `RGB888`, Header, or Footer values changes how Backend validates and decodes incoming bytes; it does not instruct the Raspberry Pi to change what it sends. A sender/profile mismatch is reported through framing, payload-size, or decode/configuration diagnostics and must never be described as a successfully synchronized Pi configuration.

## 7. Single UDP input profile

Final Backend model:

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

sealed interface NetworkInterfaceSelection {
    data object Auto : NetworkInterfaceSelection
    data class Specific(val interfaceId: String, val ipv4: String) : NetworkInterfaceSelection
}

sealed interface ImagePayloadProfile {
    data class Jpeg(/* compressed max and optional expected dimensions */) : ImagePayloadProfile
    data class Gray8(val width: Int, val height: Int) : ImagePayloadProfile
    data class Binary(val width: Int, val height: Int, val packing: BinaryPacking) : ImagePayloadProfile
    data class Rgb888(val width: Int, val height: Int, val channelOrder: RgbChannelOrder) : ImagePayloadProfile
}
```

There is no `UdpInputProfileSet`, `UdpCameraId`, production `TransportMode` choice, or `PayloadProfile.Diameter`. Production always uses `STRUCTURED_CHUNKED`. The fixed protocol version remains `1` and is displayed read-only.

Fresh default profile:

```text
enabled = false
networkInterface = AUTO
listenPort = 5000
raspberryPiIpv4 = empty (accept any IPv4 sender)
payload = JPEG(maxFrameBytes=16 MiB, expectedWidth/Height empty)
envelope = AA 01 / 0D 0A
maxFrameBytes = 32 MiB
frameTimeoutMs = 1000
maxInFlightFrames = 2
maxChunkCount = 32768
maxChunkPayloadBytes = 1400
```

`maxChunkCount` validation expands to the unsigned 16-bit wire capacity `1..65535`; default 32768 supports a 32 MiB frame with 1400-byte chunks. This changes only a configured safety limit, not the 2-byte field or wire encoding. `maxInFlightFrames=2` plus the existing per-stream 64 MiB reassembly cap prevents four full 32 MiB images from being retained.

## 8. IPv4 interface enumeration and binding

`Ipv4NetworkInterfaceProvider` is a Backend platform adapter using `java.net.NetworkInterface`. It returns immutable entries:

```text
id
displayName
ipv4
isUp
isLoopback
isVirtual
supportsMulticast
```

Only up, non-loopback IPv4 addresses are selectable. VPN, Wi-Fi and Ethernet entries remain visible and labelled; the Backend does not silently remove VPN adapters.

- `AUTO`: receiver binds internally to `0.0.0.0`.
- `SPECIFIC`: receiver binds to the selected local IPv4. Apply fails before runtime swap if the address is no longer owned by an up interface.

The UI never presents `0.0.0.0` as a Raspberry Pi target. `advertisedBackendIpv4` is resolved as follows:

1. With `raspberryPiIpv4`, ask the OS routing table for the local IPv4 that would route to that address using a temporary unbound IPv4 UDP socket; no packet is sent.
2. Otherwise choose deterministically from eligible entries: non-virtual before virtual, then interface index, then numeric IPv4.
3. If no eligible address exists, return no advertised target and status detail `NO_USABLE_IPV4_INTERFACE`.

The Network Interface REST response includes all eligible choices, the current selection, `advertisedBackendIpv4`, and whether AUTO had multiple candidates. Settings displays exactly:

```text
Raspberry Pi should send UDP to: <advertisedBackendIpv4>:<listenPort>
```

or an explicit no-usable-interface message; it never substitutes `0.0.0.0`.

## 9. UDP operational status

UDP is connectionless; socket bind success is never labelled `Connected`. `UdpOperationalStatus` is derived from receiver state and monotonic packet activity:

```text
DISABLED
UDP_READY
WAITING_FOR_RASPBERRY_PI
RECEIVING
NO_PACKETS_RECENTLY
SOURCE_IP_MISMATCH
BIND_FAILED
```

Rules:

- `DISABLED`: applied profile disabled or monitoring source stopped.
- `UDP_READY`: socket successfully bound and still inside the initial 2-second waiting grace.
- `WAITING_FOR_RASPBERRY_PI`: bound, but no accepted Datagram has arrived after the grace.
- `RECEIVING`: an accepted source Datagram arrived within the last 3 seconds.
- `NO_PACKETS_RECENTLY`: at least one accepted Datagram arrived since bind, but none within 3 seconds.
- `SOURCE_IP_MISMATCH`: the newest recent Datagram was rejected by source filtering and no accepted Datagram is newer.
- `BIND_FAILED`: bind/interface validation failed; detail contains a safe machine code, not a platform exception.

No periodic history is allocated. Status is calculated from atomically retained receiver state, last accepted/mismatched monotonic times and the current clock. Diagnostics additionally expose last packet source IPv4/port, last packet epoch time, packets and bytes received, valid frame time, framing errors, reassembly counters and decoder failures.

## 10. One receiver, processor and bounded handoff

`RaspberryPiUdpInputSource` owns exactly one `UdpReceiver`, one immutable compiled processor chain, one reassembler and one capacity-one latest input mailbox. `activeProducerCount` represents the source lifecycle reservation and remains `0` or `1`; receiver jobs also remain `0` or `1`.

Apply behavior remains transactional:

- enabled, selected bind IPv4 or listen port change: restart the single receiver;
- expected sender IPv4 change: restart receiver/filter;
- payload, envelope or transport limit change: atomically replace the compiled processor and discard old in-flight frame state;
- failed bind or repository CAS: compensate to the previous runtime processor/receiver/profile and applied revision;
- repeated Apply of the same revision is a no-op.

The postcondition remains:

```text
runtime applied profile
== receiver effective profile
== persisted applied revision profile
```

No preview or Inspector queue is allowed to participate in CameraInput admission. A completed frame is admitted to Runtime only when the capacity-one mailbox accepts/replaces it; a replaced image is closed and counted before admission.

## 11. Eight-megapixel memory and throughput policy

Existing image safety limits remain:

```text
hardMaxWidth = 8192
hardMaxHeight = 8192
hardMaxDecodedPixels = 16,777,216
hardMaxDecodedBytes = 64 MiB
hardMaxCompressed/WireFrameBytes = 32 MiB
hardMaxReassemblyBufferedBytes = 64 MiB per stream
```

All dimension, stride, `width*height`, RGB byte, bit-packed ceil and ARGB byte operations continue using checked arithmetic. JPEG dimensions are validated from metadata before raster allocation.

Additional production bounds:

- one decoded-frame permit spans decode through Runtime `CameraFrame.close()`; if a completed frame arrives while the permit is held, it is dropped before full decode with `processingBackpressureDrops` incremented;
- Vision accepts at most one frame concurrently;
- the receiver never holds more than bounded reassembly chunks plus the current Datagram;
- Preview is generated by direct nearest-neighbour sampling into at most `1280x720x4 = 3,686,400` bytes; it never first copies the full ARGB frame;
- Preview Hub retains one frame for the one preview key and its byte cap is 4 MiB;
- preview encoding occurs after FPS admission and remains latest-first/lossy;
- measurement, alarm, session and error events remain on the reliable path and cannot be dropped by preview/Inspector pressure.

The integration bound applies to the aggregate simultaneous working set, not to each component in isolation. The boundedness gate must account together for reassembly chunks, the current encoded frame, one decoded image, Vision ROI working arrays, Preview working/retained state, the Runtime mailbox, and Inspector preview retention. A real or representative 8MP JPEG/RGB888 workload that cannot remain within these approved bounds is a stop condition supported by measured evidence; implementation must not silently raise a hard limit.

The current Vision pipeline can allocate multiple full-resolution intermediate `IntArray` values. Before optimization, characterization tests must measure its peak retained working set for a deterministic 3840x2160 frame. The migration may replace full-frame intermediate retention with ROI-bounded processing only if Vision golden output remains identical for the configured ROI. The hard gate is bounded memory with one frame, not an assumed frame rate. No implementation may raise the existing 64 MiB decoded or reassembly hard limits to hide pressure.

## 12. Single-camera measurement boundary

Production no longer feeds `LatestCompatiblePairingStrategy` or `FusionEngine`. Introduce a protocol-neutral `MeasurementAssembler` boundary:

```kotlin
interface MeasurementAssembler {
    fun reset()
    fun accept(evidence: CameraMeasurement): List<OfficialMeasurement>
}
```

Production uses `SingleCameraMeasurementAssembler(cameraId="camera")`. One valid Vision measurement produces one official measurement with the same diameter and confidence. Invalid/no-edge evidence produces no official sample and increments Vision diagnostics. Existing dual-view fusion classes and golden tests remain available as generic Core algorithms but are not referenced by `BackendComposition`.

`OfficialMeasurement` and its public DTO become evidence-neutral:

```text
evidence: list containing one CameraMeasurement in production
diameterMm
confidence
status
timestamp
```

Legacy fusion metadata may be read from historical SQLite rows but is not emitted as current A/B production state. The UI and new event/REST DTO use `diameterMm`, never `fusedDiameter`, `diameterA/B`, `cameraA/BConfidence`, `shapeDifference` or Camera A/B failure status.

`MonitoringConfiguration` contains one `vision: VisionConfiguration`, target/alarm/write/realtime/preview settings, and no production fusion configuration. Configuration codec v2 reads v1 by choosing Camera A Vision configuration as the deterministic legacy source; original revision blobs remain immutable.

## 13. Persistence migration

Schema migration is additive `V004`; V001/V003 files are never edited.

- create `raspberry_pi_udp_input_revisions` and singleton state table for the new profile codec;
- seed one disabled JPEG revision, optionally carrying compatible Camera A port/source/image/limit values from the latest v1 profile, but never carrying DIAMETER, ASCII, FRAME-scope or variable framing semantics;
- use exact 2-byte defaults when old framing cannot map safely;
- keep old `udp_input_*` tables read-only for historical/audit compatibility;
- create a new `measurements_v2` table for current writes with neutral columns: measurement id, session id, captured/source timestamp, diameter mm, confidence, status, source id, pixel width (nullable when evidence does not provide it), and the existing ROI/quality fields that are still produced by Vision;
- do not write fabricated Camera B, shape difference, fusion reason or second-camera values into the legacy `measurements` table merely to satisfy its old non-null schema;
- repository reads union `measurements_v2` with legacy `measurements`; legacy rows map their persisted fused diameter and available A/B evidence into the neutral history domain, while new rows map directly from `measurements_v2`;
- new writes target `measurements_v2` only, and old V001/V003 rows remain immutable so existing golden history is not rewritten.

On migration the new UDP profile is disabled and marked applied consistently. The user must review network interface, Pi IPv4 and envelope values, then Save/Apply to enable it. Runtime profile, receiver effective profile and persisted applied revision remain atomic.

## 14. Packet Inspector

There is one Inspector lease namespace and one latest snapshot. Routes no longer contain `{cameraId}`. The snapshot is still bounded to 64/128/256 requested bytes and hard max 512, retains no history and defaults disabled.

Raw Datagram view exposes:

```text
sourceIpv4 / sourcePort
backendListenPort
datagramSize
headerHex / footerHex
headerMatched / footerMatched
raw status and bounded hex preview
Structured fields: protocolVersion, compatibility stream byte,
frameId, chunkIndex, chunkCount, sourceTimestampMicros,
payloadLength, framePayloadBytes
```

Parsed Frame view exposes payload format (`JPEG|GRAY8|BINARY|RGB888`), dimensions when known, decode result, completed frame bytes and bounded preview. Header or Footer mismatch updates the raw snapshot with `HEADER_MISMATCH` or `FOOTER_MISMATCH`; it never reaches the structured parser. Source mismatch remains observable only while a lease is active and never enters envelope parsing.

The Raw Hex Preview is a bounded prefix of the complete received Datagram and therefore includes the outer Header and, when the requested bounded preview reaches the end of a short Datagram, its Footer. Parsed Transport metadata is produced only from the stripped 34-byte Structured Header. Outer envelope bytes never alter structured offsets.

## 15. Backend REST and WebSocket contracts

Retained configuration routes:

```text
GET  /api/v1/devices/{deviceId}/udp-input-configuration
PUT  /api/v1/devices/{deviceId}/udp-input-configuration
POST /api/v1/devices/{deviceId}/udp-input-configuration/apply
```

New single-stream routes:

```text
GET    /api/v1/network-interfaces
GET    /api/v1/devices/{deviceId}/udp-diagnostics
POST   /api/v1/devices/{deviceId}/packet-inspector/lease
DELETE /api/v1/devices/{deviceId}/packet-inspector/lease/{leaseId}
GET    /api/v1/devices/{deviceId}/packet-inspector?leaseId=...&previewBytes=...
```

Old `/cameras/{cameraId}/udp-diagnostics` and Inspector routes are removed after the bundled frontend migrates in the same release. Backend validation remains authoritative; frontend validation is feedback only.

Reliable Event WebSocket remains `/api/v1/events`. Measurement event DTOs use the neutral single-camera measurement fields. Preview WebSocket remains `/api/v1/previews` but accepts one key; the bundled frontend supplies `deviceId`, `stage=RAW`, `maxFps` and no Camera A/B selector. Preview metadata reports `cameraId="camera"` for compatibility with `PreviewKey`.

## 16. Final Settings model and layout

Settings contains one `Raspberry Pi UDP Input` section:

```text
Network
  Enabled
  Network Interface: AUTO | <displayName + IPv4>
  Backend IPv4 (read-only resolved value)
  UDP Listen Port
  Raspberry Pi IPv4 (optional expected source)
  Raspberry Pi should send UDP to: <IPv4>:<port>
  UDP Status
  Last Packet Source / Last Packet Time

Camera Stream
  Payload Format: JPEG | GRAY8 | BINARY | RGB888
  Header: exactly 2-byte HEX
  Footer: exactly 2-byte HEX
  format-specific fields

Advanced Transport
  Protocol Version: 1 (read-only)
  Frame Timeout
  Max Frame Bytes
  Max In-flight Frames
  Max Chunk Count
  Max Chunk Payload

Diagnostics
  Packets / Bytes / Frames Completed / Frames Dropped
  Timeouts / Decode Failures / Last Valid Frame

Packet Inspector
```

Labels and help text call Payload Format and Header/Footer the expected input protocol. The page shows saved and currently applied values separately and states that Apply configures Backend parsing only; it does not reconfigure the Raspberry Pi.

JPEG is the default/recommended format. GRAY8, both BINARY packings, RGB/BGR are production options. DIAMETER, Transport Mode, ASCII framing, scope, strip and second Camera card are absent.

## 17. Final Monitoring UI

Normal Monitoring page order:

```text
UDP / Raspberry Pi status banner
Live Camera Preview (one latest-first image)
Current Diameter: 1.xxx mm + confidence + timestamp
Diameter Trend / History
Warning / Alarm status and recent alarm context
```

No Camera A/B preview, confidence pair, view difference or “fused dual-camera” copy remains. Before any accepted frame, the preview and diameter panels show `Waiting for Raspberry Pi`; they never synthesize an image or measurement.

The frontend production entry point constructs a Backend-backed data source using REST + reliable Event WebSocket + Preview WebSocket. It never imports `MockFilamentDataSource`. Closing/reopening the browser releases only browser subscriptions; Backend `MonitoringRuntime` remains authoritative. Start/stop commands remain explicit and idempotent Backend commands, not ownership of producer jobs by React components.

## 18. Simulator and fake-data disposition

The following move from main/production imports to test fixtures:

- `SimulatorImageInputSource`, `SimulatorFrameFactory`, `SimulationScenario`;
- `SimulatorBackendInputSourceFactory` and the dispatcher overload that silently selects it;
- frontend `MockFilamentDataSource`.

Tests may continue using deterministic images, fake clocks/repositories, loopback UDP sender and a test-only simulator factory. `BackendComposition.open(database, snapshotRoot)` always creates the real single UDP source. CLI removes `--scenario`; production startup cannot select fake data.

## 19. Component disposition audit

### Reuse unchanged or with naming-only adaptation

- `BorrowedDatagramBytes` ownership rule and receive loop;
- IPv4 source filter;
- 34-byte Structured codec field layout and unsigned parsing;
- structured chunk ordering, duplicate/conflict, missing/late/timeout, eviction and bounded merge algorithms;
- decoded allocation policy and `Gray8Decoder`, `BinaryDecoder`, `Rgb888Decoder`, `JpegDecoder`;
- `CameraInput` boundary and frame lease ownership;
- runtime reservation, stop/finalize, write buffer, realtime ring buffer, alarm and statistics;
- configuration/UDP revision CAS and compensation pattern;
- repository ports, REST envelope/error handling, reliable events;
- latest Preview Hub/backpressure and Inspector lease/latest-only model;
- long-run and platform independence gates.

### Modify

- UDP profile/validator/codec/repository/DTOs;
- receive capacity and bind address selection;
- processor ordering to envelope-before-transport;
- transport expected stream identity and reassembly keys;
- single receiver/source/router/status/diagnostics;
- Monitoring configuration, measurement assembly and current measurement DTO;
- SQLite additive migration and dual-history read adapter;
- Backend composition, CLI, REST/WS paths and preview sizing;
- frontend API/domain/store, Settings, Inspector and Monitoring dashboard.

### Production obsolete

- `UdpCameraId.A/B`, `UdpInputProfileSet`, second receiver and per-Camera maps;
- direct Diameter payload/profile/production decoder path;
- A/B pairing/fusion in `MonitoringRuntime` composition;
- dual Camera device view, draft routing and preview keys;
- A/B Settings cards and preview/quality copy;
- arbitrary Application framing controls;
- production scenario flag and mock source selection.

### Test-only useful

- simulator scenarios and synthetic frames;
- generic dual-view Fusion/Pairing golden tests;
- `SingleDatagramFrameReassembler` tests;
- old Application framing characterization where useful for historical compatibility;
- deterministic decoder fixtures and loopback sender.

## 20. Error and rollback behavior

External packet errors drop only that Datagram or incomplete frame. They never terminate Backend or Runtime. Apply bind failure restores the previous socket/profile. Header/Footer errors cannot be misreported as structured magic errors. Processed frames always release their lease on success, drop, cancellation and stop.

Each implementation task is independently revertible. V004 is never edited after merge; corrections require V005. No task changes the 34-byte header. If implementation evidence requires a wire-layout change, work stops for a new design amendment.

## 21. Verification gate

New loopback E2E must cover one physical IPv4 socket and:

- JPEG multi-chunk and out-of-order;
- GRAY8, BYTE_PER_PIXEL/bit-packed BINARY and RGB/BGR multi-chunk;
- exact 2-byte header/footer valid and invalid cases;
- source mismatch and Inspector observation;
- sentinel oversized/truncated Datagram;
- missing, duplicate, conflicting, late and timeout chunks;
- a bounded 3840x2160 fixture and 8MP declaration/allocation rejection edges;
- one live Preview and Vision-derived diameter;
- warning/alarm/session reliable events under preview/Inspector congestion;
- Save/Apply parser swap, format change, port restart, bind rollback and CAS rollback;
- no packets produces `WAITING_FOR_RASPBERRY_PI`, no fake measurement/preview;
- `activeProducerCount <= 1`, receiver jobs `<=1`;
- reassembly bytes, decoded permit, mailbox, Inspector and Preview retention return to zero/bounds after stop/restart.

Existing Vision/Fusion golden, decoder, receiver ownership, SQLite conformance, runtime concurrency/failure, reliable event, Preview backpressure, REST security, long-run and standalone-independence suites remain regression gates. Tests that assert dual production topology are replaced; generic algorithm golden tests are retained without changing expected values.

