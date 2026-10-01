# Task 12 — event and preview WebSocket contracts

The caller installs `installEventWebSocket` and `installPreviewWebSocket` with the
already-owned application facade and Task 9 hubs. Each installer ensures Ktor
WebSockets is installed once. No adapter starts/stops a runtime/source, creates a
producer, persists data or performs vision processing. There is no executable
composition in this task.

## Reliable event stream

`/api/v1/events?deviceId=device&afterSequence=123`

- `deviceId` may be omitted only when the supplied registry has one device.
- Omit `afterSequence` for a `state.snapshot`, followed by subsequent events.
- A cursor is an exclusive, nonnegative decimal Long, scoped to one device and
  this hub lifetime. An available cursor resumes the exact suffix, without an
  extra snapshot. There is no cross-restart delivery guarantee.
- An expired initial cursor produces a fresh snapshot with reason
  `REPLAY_EXPIRED`. Expiry after event delivery produces `SLOW_CONSUMER`.
- Future cursors yield `protocol.error` code `FUTURE_CURSOR` and close 1008.
  Invalid, duplicate, unknown or ambiguous query fields yield
  `INVALID_SUBSCRIPTION` and close 1008. Cursors above Long.MAX_VALUE are invalid.

Every domain envelope has `type`, `sequence`, `deviceId`, `occurredAt`, `payload`.
Types are `session.started`, `session.finalized`, `measurement.sample`,
`alarm.transition`, `error.raised`. Timestamps come from the corresponding
domain record, not the send time. Existing REST DTO mappers preserve camera
values and sanitize errors and non-finite numeric data. No image/base64 is sent.

Snapshot payload is `{ state: <REST StateDto>, reason: "INITIAL" | ... }`.
Its sequence and nested `eventSequence` are the same authoritative boundary;
`occurredAt` uses the injected server clock at snapshot generation. A snapshot
can have sequence 0, or reuse the current event boundary; it is a checkpoint,
not an increment of the domain event sequence.

**Task 13 integration contract:** `BackendApplication.state(device)` must return
a coherent state and eventSequence captured from the same hub lifetime. State
must reflect that boundary. The route validates matching device and sequence
range, but cannot make two independently captured application values atomic.
Publication after snapshot capture is replayed after that exact cursor. Do not
substitute an independently sampled `hub.metrics().published` for atomic state
capture in production composition.

Live delivery polls `replayAfter` every 10 ms when idle. There is no separate
registration step or live queue to lose events between replay and subscription.
One client holds at most one bounded replay window plus its cursor. Sending
suspends that client only; publishing never calls a client. If retention overtakes
the cursor, an explicit snapshot replaces the missing interval. A stale facade
snapshot that cannot advance recovery fails the connection, rather than looping.
This is resumable in-process delivery, not durable acknowledgement or exactly-once
delivery across connections. Consumers resume from their last processed sequence.

## Preview stream

`/api/v1/previews?deviceId=device&cameraId=A&stage=RAW&maxFps=5`

One connection selects one registered device/camera/stage key. Multiple keys use
separate connections, preserving independent bounds and avoiding cross-key
replacement. `deviceId` omission follows the single-device rule. `cameraId` and
`stage` are required. Stages use Core names: RAW, GRAYSCALE, THRESHOLD, EDGE.
`maxFps` is an integer 1–30 (default 5). Unknown/duplicate/invalid fields close
with `INVALID_SUBSCRIPTION` (1008) before a hub subscriber is created.

Each image is a `preview.frame` JSON metadata message immediately followed by
one binary message from the same writer. Metadata contains deviceId, cameraId,
stage, width, height, timestamp, sourceSequence, mediaType and byteLength.
`configurationRevision`, `draftRevision` and `official` are explicitly null:
the current immutable Core PreviewFrame does not carry that provenance. These
fields must not be inferred from the application's current revision or stage.

The Backend `PngPreviewEncoder` converts Core ARGB32/GRAY8 diagnostic bytes to
PNG using ImageIO. Core validates positive dimensions, multiplication overflow
and exact pixel length; the encoder also checks dimensions/length before image
allocation. Only nonempty `image/png` or `image/jpeg` adapter output is accepted.
No image or codec API enters Core. Encoder failures send a safe error and close
only the affected preview connection.

The existing LatestPreviewHub retains one global latest per key and at most one
pending per subscribed key, with its existing subscriber/payload limits. The
adapter adds no buffering flow/channel. A suspended encoder/send leaves one
in-flight image and one replaceable hub pending value. Rate wait uses an
injectable monotonic clock/delay and reselects the newest frame after the wait;
an already-delivered frame is not encoded twice. Cadence begins after the prior
send completes, so slow encoding/transports reduce preview rate. Official
measurement frequency is unaffected.

The hub's retained global latest intentionally survives disconnect; per-client
pending state, send jobs and encoded bytes are released when the connection ends.
Transport buffers in Ktor/OS are outside hub pending metrics. Bytes already sent
to the transport cannot be retracted; newest delivery follows any bounded
in-flight transport data. Tests separately cover the real WebSocket envelope/
binary pairing, a gated encoder, and a suspended send boundary. They do not claim
to measure real network socket buffers or a remote browser's acknowledgements.

## Connection policy and verification

Each connection has one writer and a receive watcher for prompt disconnect
cancellation. Client text/binary messages are rejected with `READ_ONLY_STREAM`
(1008). Transport send/flush has a five-second deadline. A stalled connection
closes 1013 with a reconnect/resume instruction; other internal failures emit
safe `STREAM_UNAVAILABLE` and close 1011. Closing itself has a one-second bound.
No exception text, filesystem paths or SQL reaches the wire.

Focused tests cover initial snapshot/event metadata; suffix/expiry/invalid/future
cursors; publication during snapshot and replay handoff; controlled slow reliable
send expiry while a fast consumer receives every event; 1,000 preview replacements
with every reliable event delivered; camera/stage separation; injected rate wait
newest selection; PNG signatures/pixels/dimensions; encoder failure; disconnect
cancellation; and a real running MonitoringRuntime/Simulator with multiple clients.
Full Core/Backend/reference tests and Core platform firewall remain the regression
gate. Deployment authentication, TLS, restart epochs, transport tuning and global
connection admission are outside this Task 12 adapter contract.
