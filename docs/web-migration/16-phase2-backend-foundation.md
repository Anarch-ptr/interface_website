# 16 — Phase 2 Backend Foundation Design

## Decision and scope

Phase 2 adds two Gradle modules beside the existing Android `:app` module:

```text
:app                 existing Android application; remains buildable
:core                pure Kotlin/JVM domain, vision, monitoring contracts
:backend             Ktor server, REST/WebSocket adapters, simulator, SQLite adapter
```

The backend is the authoritative owner of monitoring. Browser or diagnostic clients observe state and submit idempotent commands; they never create a producer. The initial executable input is an image-level simulator using the same `ImageInputSource` boundary that a future Raspberry Pi adapter will implement.

This phase does not build the React UI, delete Android code, invent a Raspberry Pi protocol, add real Bluetooth/Wi-Fi/camera drivers, or change the current 2%/5% immediate alarm policy.

## Reuse classification

Classification is based on the current source, not package names alone. Extraction will be incremental and verified by tests before Android call sites are switched.

| Class/file group | Current package | Android dependency | Pure Kotlin dependency | Phase 2 target | Action | Reason |
|---|---|---:|---|---|---|---|
| `VisionMeasurement`, status/session/source/device models | `model` | No | Kotlin | `:core/model` | REUSE DIRECTLY | Domain values are platform-neutral. JSON DTOs remain separate. |
| `InputProfiles` | `model` | No | Kotlin | `:core/config` | EXTRACT MINOR | Split confirmed generic image/geometry settings from unconfirmed transport fields. |
| `IntensityFrame` and processing result models | `vision.preprocessing`, `vision.processing` | No | Kotlin | `:core/vision` | EXTRACT MINOR | Replace UI-facing preview types with core diagnostic-stage types. |
| grayscale, threshold, edge processors | `vision.preprocessing` | No | Kotlin | `:core/vision` | REUSE DIRECTLY | Deterministic platform-neutral algorithms. |
| geometry transformer | `vision.geometry` | No | Kotlin | `:core/vision` | REUSE DIRECTLY | Platform-neutral crop/orientation/mirror behavior. |
| detection pipeline and `VisionProcessor` | `vision.detection`, `vision.processing` | No | Kotlin | `:core/vision` | EXTRACT MINOR | Inject timing/diagnostic sink; preserve numeric behavior. |
| `FusionEngine` | `vision.fusion` | No | Kotlin | `:core/vision` | REUSE DIRECTLY | Preserve A/B/single-camera/mismatch/timestamp outcomes. |
| `PacketAssembler`, configurable frame parser, raw decoder | `input.packet`, `input.frame`, `input.decode` | No | Kotlin | `:core/input` | EXTRACT MINOR | Useful only behind a confirmed adapter; keep limits and tests but do not claim Pi compatibility. |
| compressed image decoder interface | `input.decode.ImageDecoder` | No | Kotlin | `:core/input` | REIMPLEMENT INTERFACE | Core owns contract; backend codec implementation must not use Android `BitmapFactory`. |
| `AndroidCompressedImageDecoder` | `input.decode` | Yes | Android graphics | `:app` | KEEP ANDROID ONLY | Android-specific codec. |
| input source, transport and diagnostics contracts | `input` | Mostly no | Coroutines/Flow | `:core/input` | EXTRACT MINOR | Remove Android assumptions; one bounded frame stream per source. |
| unavailable source/factory/registry | `input`, `hardware` | Some application wiring | Coroutines | Android adapter plus backend factory | REIMPLEMENT INTERFACE | Current production factory intentionally has no driver. Backend gets simulator only. |
| `VisionPipelineRuntime` | `vision.runtime` | No direct Android API | Coroutines/Flow | `:core/runtime` | EXTRACT MINOR | Separate engine from Android composition and expose metrics/events. |
| alarm evaluator | `domain.alarm` | No | Kotlin | `:core/alarm` | REUSE DIRECTLY | Existing 2% warning and 5% critical rule is authoritative. |
| session statistics accumulator | `domain.session` | No | Kotlin | `:core/session` | REUSE DIRECTLY | Incremental statistics are portable. |
| realtime/write buffers | `domain.monitor`, `domain.monitoring` | No | Kotlin/coroutines | `:core/runtime` | EXTRACT MINOR | Preserve 300 realtime bound and lossless write-buffer semantics. |
| trend downsampler | `domain.trend` | No | Kotlin | `:core/history` | EXTRACT MINOR | Remove dependency on `ui.components.chart.TrendChartPoint`. |
| `MonitoringRuntime` and state | `domain.monitoring` | Android service controller indirectly through composition | Coroutines/Flow | `:core/runtime` | REIMPLEMENT INTERFACE | Preserve lifecycle/finalization invariants while making repositories/events explicit. |
| connection validator and profile codec | `domain.connection`, `data.settings` | Codec no; persistence yes | Kotlin | `:core/config` | EXTRACT MINOR | Validation is reusable; storage and secret handling are backend adapters. |
| repository interfaces | `data.repository` | No | Kotlin/Flow | `:core/repository` | EXTRACT MINOR | Replace Room entities with domain records and suspend contracts. |
| Room DAOs/entities/database/repositories | `data.local`, Room implementations | Yes | Room | `:app` | KEEP ANDROID ONLY | Backend uses SQLite JDBC through the same conceptual contracts. |
| SharedPreferences settings repository | `data.settings` | Yes | Android preferences | `:app` | KEEP ANDROID ONLY | Backend configuration is revisioned repository data. |
| camera visual models/source and snapshot policy | `camera` | Models mostly no | Kotlin/coroutines | `:core/preview`, `:core/snapshot` | EXTRACT MINOR | Remove Compose/image-holder types and connect cache to canonical frames. |
| snapshot file store | `storage` | Android context/files | Android I/O | `:app` | KEEP ANDROID ONLY | Backend gets a bounded filesystem adapter behind `SnapshotRepository`. |
| foreground service, controller, `Application`, Activity | `service`, root | Yes | Android lifecycle | `:app` | KEEP ANDROID ONLY | No server responsibility. |
| Compose screens, ViewModels, navigation, theme | `ui`, `navigation` | Yes | Compose/lifecycle | `:app` | KEEP ANDROID ONLY | Phase 2 has no final Web UI. |

## Runtime architecture and ownership

```text
SimulatorImageSource                         Future Pi source
        |                                  (same contract only)
        +------------ ImageInputSource -------------+
                              |
                    bounded latest-frame ingress
                              |
                 per-camera VisionProcessor workers
                              |
                  CameraMeasurement A / B
                              |
                         FusionEngine
                              |
                   MonitoringRuntime (authority)
              +---------------+---------------+
              |               |               |
       AlarmEvaluator   Statistics       Realtime ring
              |               |               |
       event repository  session state    WebSocket hub
              +---------------+---------------+
                              |
                    batched repositories
                              |
                         SQLite adapter
```

One `BackendMonitoringRuntime` exists per configured device identity inside a `DeviceRuntimeRegistry`. Phase 2 ships one simulator device, but the registry boundary prevents a process-wide God object and supports later device leases. The registry serializes commands per device. Creating HTTP or WebSocket clients does not create a runtime or producer.

### Authoritative ownership

| State/data | Owner | Observers/adapters |
|---|---|---|
| connection and input state | runtime + active `ImageInputSource` | REST state, WebSocket state |
| monitoring lifecycle and session identity | runtime | clients, repositories |
| camera frames | input source until accepted; pipeline frame lease while processing | preview encoder, snapshot cache |
| Camera A/B measurements | vision pipeline | fusion, diagnostics |
| official fused measurement/latest diameter | runtime after fusion acceptance | alarm, statistics, repositories, API |
| preview stages | preview publisher/cache | preview WebSocket only |
| alarm state and transitions | alarm coordinator in runtime | alarm repository/event hub |
| statistics | session accumulator in runtime | session repository/API |
| history | repositories | history service/API |
| active errors | runtime/error coordinator | error repository/API |
| saved/applied configuration revisions | configuration service/runtime | repository/API |

## Lifecycle and single-producer invariant

Backend monitoring states are `IDLE`, `STARTING`, `RUNNING`, `STOPPING`, `ERROR`, with `RECONNECTING` reserved for a future real source. Connection readiness is reported separately so `IDLE` can mean connected but not recording.

```text
IDLE --start--> STARTING --source ready/session persisted--> RUNNING
RUNNING --stop--> STOPPING --producer joined/flush/finalize--> IDLE
STARTING/RUNNING --fatal source or persistence error--> STOPPING --> ERROR
ERROR --reset/reconnect--> IDLE (simulator) or RECONNECTING (future source)
```

Rules:

- `start(commandId)` is idempotent. While `STARTING` or `RUNNING`, it returns the current session and never launches another producer.
- producer creation and state transition occur inside one per-device mutex/serialized command actor;
- `activeProducerCount` is a reported invariant and must remain `0..1`;
- stop first closes measurement acceptance, then cancels source production, awaits producer termination, flushes measurements and alarms, finalizes statistics/session, publishes final state, and only then returns to `IDLE`;
- duplicate stop never repeats flush/finalization;
- fatal interruption uses the same finalization pipeline with an interrupted outcome;
- WebSocket subscribe/unsubscribe and REST polling have no producer lifecycle effects.

## Canonical frame model and memory lifetime

The platform-neutral boundary is:

```kotlin
data class CameraFrame(
    val cameraId: CameraId,
    val capturedAtEpochMillis: Long,
    val receivedAtNanos: Long,
    val width: Int,
    val height: Int,
    val pixelFormat: PixelFormat,
    val pixels: ReadOnlyPixelBuffer,
    val orientationDegrees: Int,
    val mirrored: Boolean,
    val sequence: Long,
    val diagnostics: FrameDiagnostics,
)
```

`ReadOnlyPixelBuffer` exposes a bounded byte view plus a `FrameLease`. The source owns mutable storage until emission; ingress either transfers a lease or performs exactly one defensive copy. Geometry and preprocessing operate on views where possible. Preview encoding may allocate a compressed output but does not clone the official frame for each subscriber. A lease is released after all pipeline/preview/snapshot consumers finish. Slow consumers receive the latest frame; stale preview frames are dropped. Measurement, alarm and persistence events are never dropped by preview backpressure.

Phase 2 supports deterministic grayscale simulator frames. Additional pixel formats require an installed decoder and capability declaration; configuration validation must reject formats without a decoder.

## Vision and dual-camera semantics

The official path remains:

```text
frame -> orientation/mirror -> ROI -> grayscale -> threshold -> edge map
      -> edge detection -> pixel width -> mm conversion -> confidence
      -> CameraMeasurement -> A/B fusion -> official VisionMeasurement
```

- raw A and B results, confidences, timestamps and source sequences are retained;
- matching camera timestamps within the configured tolerance can produce dual-camera fusion;
- one healthy camera can produce an explicit single-camera outcome with the other value `null` at API boundaries;
- disagreement preserves `shapeDifference` and mismatch status;
- stale/timestamp-mismatched pairs do not masquerade as synchronized measurements;
- processing order and existing numeric algorithms are preserved by golden tests.

**DESIGN DECISION REQUIRED:** real-hardware evidence must define whether a camera measurement may participate in more than one fused output. Phase 2 preserves the current latest-counterpart behavior and exposes pair sequences so the choice can later be changed safely.

## Rates, backpressure and metrics

Rates are independent configuration/observability concepts:

- source frame rate per camera;
- accepted/processed frame rate per camera;
- camera-measurement and fused-measurement rates;
- dropped ingress and preview-frame counts;
- capture-to-receive, processing and fusion latency;
- WebSocket measurement publication rate;
- preview publication/encoding rate;
- database batch and row write rate.

The simulator defaults are chosen to exercise the pipeline, not to manufacture an artificial 8 Hz result. Official output is driven by accepted frames and fusion. Persistence batches flush at 8 records or 1 second, preserving current semantics. WebSocket measurement and preview limits are separate and cannot change official processing or storage rates.

**DESIGN DECISION REQUIRED:** real camera evidence must determine whether “8 Hz” is a capture target, fused-output target, storage sampling target, or only the historic simulator rate.

## Alarm, statistics and snapshots

Alarm responsibilities remain separate:

1. `AlarmEvaluator` classifies deviation using the current immediate 2% warning / 5% critical thresholds.
2. `AlarmStateMachine` records the current transition.
3. `AlarmEventRepository` persists transitions/events.
4. future delivery adapters may notify external systems; none are added in Phase 2.

No hysteresis, debounce, acknowledgement workflow or new alarm policy is introduced.

Statistics consume only accepted official measurements and finalize through the common stop pipeline.

`LatestFrameSnapshotCache` is bounded by camera and diagnostic stage. It keeps the newest relevant encoded artifact/reference, uses the existing five-minute error-snapshot cooldown, and never stores unbounded raw frames. Defaults and limits are explicit configuration: maximum entries, maximum bytes, allowed encoding, retention, cooldown and repository reference. SQLite stores metadata/path references; binary files remain behind `SnapshotObjectStore`.

## Repository boundaries and SQLite

`:core` defines suspend interfaces for:

- `SessionRepository`;
- `MeasurementRepository` with transactional batch append and ranged/keyset history queries;
- `AlarmEventRepository`;
- `SnapshotRepository` plus `SnapshotObjectStore`;
- `ConfigurationRepository` with revision compare-and-set;
- `ErrorRepository`.

`:backend` implements these with SQLite JDBC and schema migrations. SQL types/entities remain inside the adapter. Runtime/application services depend only on core repository interfaces, allowing a later PostgreSQL adapter without changing monitoring or API code. In-memory repositories are used only in focused tests, not as the default server persistence.

## Configuration and calibration contract

The configuration aggregate includes target diameter, warning/critical thresholds, camera A/B calibration, connection metadata, image geometry, fusion/timestamp tolerance, capture/preview/publication rates, batch limits and snapshot policy.

It maintains `savedRevision` and `appliedRevision`:

- `SAVE`: validate and persist a new revision; running runtime is unchanged;
- `APPLY`: apply an already-saved revision. If a restart-sensitive field changes while running, finalize the old session and start a new session only when explicitly requested by the command;
- `SAVE_AND_APPLY`: atomic command semantics from the caller’s perspective: save, then apply that exact revision or return a structured failure;
- calibration draft updates affect preview only; Save and Apply remain explicit;
- target/threshold changes are revisioned. The measurement records the applied revision used;
- connection, decoder, geometry and camera identity changes are restart-sensitive;
- UI publication/preview limits may be live-applied because they do not change official measurements.

**DESIGN DECISION REQUIRED:** applying a restart-sensitive revision during monitoring needs a product decision between reject-until-stop and finalize-then-auto-restart. Phase 2 defaults to reject-until-stop to avoid an implicit new session.

## REST contract (`/api/v1`)

All mutation requests use `commandId`; responses include `correlationId`. JSON uses UTC epoch milliseconds and nullable missing camera values.

| Method/path | Responsibility |
|---|---|
| `GET /api/v1/devices/{deviceId}/state` | authoritative connection, monitoring, cameras, session, latest, alarm, revisions, producer count and event sequence |
| `POST /api/v1/devices/{deviceId}/monitoring/start` | idempotent start with target/applied revision preconditions |
| `POST /api/v1/devices/{deviceId}/monitoring/stop` | idempotent finalization |
| `GET /api/v1/devices/{deviceId}/measurements/latest` | latest official A/B/fused measurement |
| `GET /api/v1/sessions` | keyset-paged sessions |
| `GET /api/v1/sessions/{sessionId}` | session and finalized statistics |
| `GET /api/v1/sessions/{sessionId}/measurements` | ranged/keyset history |
| `GET /api/v1/sessions/{sessionId}/alarms` | alarm events |
| `GET/PUT /api/v1/devices/{deviceId}/configuration` | read or Save full revision |
| `POST /api/v1/devices/{deviceId}/configuration/apply` | Apply saved revision |
| `POST /api/v1/devices/{deviceId}/configuration/save-and-apply` | explicit combined command |
| `POST/PATCH/DELETE /api/v1/devices/{deviceId}/calibration/drafts...` | draft preview lifecycle |
| `GET /api/v1/devices/{deviceId}/cameras` | A/B state and capabilities |
| `GET /api/v1/devices/{deviceId}/diagnostics` | rates, latency, drops, queues, storage and versions |
| `GET /api/v1/errors` | active/history errors |
| `GET /api/v1/errors/{errorId}/snapshots` | bounded snapshot metadata/URLs |
| `GET /api/v1/snapshots/{snapshotId}` | authenticated encoded artifact in Phase 2 test mode |

Ktor routes map DTOs to application commands; route handlers contain no vision, lifecycle or SQL logic.

## WebSocket and preview contract

`/api/v1/events` carries typed JSON envelopes:

```json
{
  "type": "measurement.sample",
  "sequence": 124,
  "deviceId": "simulator-1",
  "occurredAt": 0,
  "payload": {}
}
```

Every envelope has a discriminator and timestamp. State, session, measurement, alarm, error and configuration events also have a monotonic per-device sequence. Initial subscription returns `state.snapshot`; reconnect can request `afterSequence`. Phase 2 keeps a bounded replay window and falls back to a fresh snapshot if the requested sequence expired.

Measurement DTOs contain numeric results and references only—never base64 images. Preview uses `/api/v1/previews` as a separate WebSocket endpoint with independently rate-limited binary JPEG/PNG messages preceded by typed metadata containing camera, stage, dimensions, source sequence, configuration/draft revision and official/draft marker. Preview backpressure drops stale preview frames without affecting monitoring.

## Simulator and diagnostic client

`SimulatorImageInputSource` produces deterministic image frames with known filament widths, timestamps, camera identity and sequence. Scenarios:

- normal;
- warning/high diameter and critical/high diameter;
- low diameter warning/critical;
- Camera A only, Camera B only;
- A/B mismatch;
- timestamp mismatch;
- low confidence where the current algorithm can represent it;
- temporary camera dropout;
- connection failure and recovery.

Scenarios modify images/timing/source availability, not final measurement DTOs. Optional recorded fixtures implement the same source contract. A minimal command-line/HTML-free diagnostic client starts/stops sessions, prints state/events and queries persisted history; it is not the Phase 3 React UI.

## Test strategy and implementation order

Production changes follow test-driven slices:

1. create `:core`/`:backend` build skeleton and prove existing Android tests/build still pass;
2. characterize and extract vision/model/fusion behavior with golden unit tests;
3. add canonical frames, simulator scenarios and bounded pipeline tests;
4. implement runtime lifecycle, idempotency, single-producer and finalization tests;
5. add alarm/statistics/configuration semantics tests;
6. define repositories, SQLite migrations and adapter conformance tests;
7. implement sequenced event hub and REST/WebSocket contract tests;
8. run one end-to-end test: simulated A/B images → processing → fusion → alarm/statistics → SQLite → REST history/latest plus WebSocket event;
9. run existing unit tests, new `:core`/`:backend` tests, `assembleDebug`, backend build/test and a sustained simulator run.

Required assertions include producer count never exceeding one, navigation/client count not affecting producers, bounded buffers/replay/snapshots, no continuous memory growth in the sustained test, persisted final accepted batch, monotonic event sequence and complete A/B raw values.

## Phase 2 completion and Phase 3 gate

Phase 2 is complete only when the headless backend can start a simulator session, process image-level A/B frames, produce/fuse measurements, evaluate alarms, maintain statistics, persist/query history, publish sequenced REST/WebSocket state, stop cleanly and restart with persisted history—without Android UI involvement.

The implementation report will be written to `17-phase2-implementation-report.md` and will list changed modules/files, interfaces, reuse decisions, intentionally unmoved Android code, exact test/build/sustained-run results, observed rates/latencies, unresolved uncertainties and all Raspberry Pi assumptions.

## Deferred decisions before Raspberry Pi integration

The following are deliberately not guessed in Phase 2:

- **DESIGN DECISION REQUIRED:** actual Pi transport(s), ports, UIDs, framing, commands, authentication and acknowledgement behavior;
- **DESIGN DECISION REQUIRED:** real camera pixel formats, resolution, orientation, timestamp clock and sequence guarantees;
- **DESIGN DECISION REQUIRED:** central-backend versus Pi-side authoritative vision placement after compute/network measurement;
- **DESIGN DECISION REQUIRED:** paired-frame consumption/cadence and the exact meaning of 8 Hz;
- **DESIGN DECISION REQUIRED:** restart-sensitive Apply behavior during an active session;
- **DESIGN DECISION REQUIRED:** deployment topology, authentication/authorization, TLS and device ownership;
- **DESIGN DECISION REQUIRED:** production database/object storage, retention, backup and snapshot privacy;
- **DESIGN DECISION REQUIRED:** real-image golden fixtures, calibration traceability and acceptable measurement error.

