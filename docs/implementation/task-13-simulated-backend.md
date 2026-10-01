# Standalone simulated backend

The composition owns one `MonitoringRuntime`, one image-level simulator for
`simulator-1`, SQLite repositories, separate event/preview hubs, metrics and
snapshot storage. Clients send commands and observe; connecting clients never
starts a producer. The initial state is connected/IDLE with producer count zero.

## Run

From this repository, with the configured JDK 21 toolchain:

```powershell
.\gradlew.bat :backend:run --args="--database build/phase2-smoke.db --snapshot-root build/phase2-snapshots --host 127.0.0.1 --port 8080 --scenario NORMAL"
```

In another terminal:

```powershell
.\gradlew.bat :backend:runDiagnosticClient --args="--base-url http://127.0.0.1:8080 --device simulator-1 --start --observe-seconds 30 --stop --history"
```

`--database`, `--snapshot-root`, `--host`, `--port` and `--scenario` are server
options. Relative paths resolve from the JVM working directory (Gradle's
`:backend:run` defaults to the Backend project directory). Defaults use `build/`.
Use absolute paths to select a specific project build directory. The default bind
is loopback. This is Phase 2 test mode, without authentication or TLS; it makes no
production deployment claim. Android sources and files are not used at runtime.

`HIGH_DIAMETER`, `LOW_DIAMETER`, `CAMERA_A_ONLY`, `CAMERA_B_ONLY`,
`CAMERA_MISMATCH`, `TIMESTAMP_MISMATCH`, `LOW_CONFIDENCE`,
`TEMPORARY_DROPOUT` and `CONNECTION_FAILURE` are additional simulator scenarios.
The server startup argument and test adapter select them; there is no invented
hardware/scenario REST protocol.

Send `shutdown` to server stdin, or interrupt the foreground JVM, to stop the
engine and await source termination, accepted-data flush, session finalization,
scope termination and database close. `:backend:installDist` also creates a
standalone launcher and dependency directory under `backend/build/install/`.

## Contracts and limits

- Source period is 125 ms per camera. Preserved latest-counterpart fusion can
  accept one official sample per processed frame: two cameras do not imply an
  8 Hz official output. No final diameter DTO is injected by the simulator.
- Default raw strips measure 35 pixels at 0.05 mm/pixel: A/B/fused 1.75 mm.
  The high scenario alternates 36/37 pixels, producing 1.80/1.85 mm and the
  existing immediate warning/critical classifications.
- Measurements use stable session/ordinal IDs and the applied configuration
  revision. Batch acknowledgement precedes reliable measurement/alarm events.
  Session finalization and its event follow accepted measurement/alarm draining.
- This composition conservatively rejects **every Apply while a runtime
  reservation exists**, including preview-only changes, with `REQUIRES_STOP`.
  Save remains available. Save-and-Apply can save a revision while returning
  `REQUIRES_STOP`; applied revision stays unchanged. A new Apply command after
  stop installs it on the same runtime. This preserves buffered sample revision
  provenance; the lower-level configuration service's abstract live-update
  capability does not imply this composition has a live revision barrier.
- Configuration and draft commands keep at most 128 completed results. Runtime
  start/stop deduplication separately retains at most 128 results. At most two
  drafts are retained. Drafts do not change official processing.
- Realtime retention defaults to 300. Event replay holds 256 events for this
  hub lifetime. Clients with expired cursors receive a fresh state snapshot.
  State capture retries if the event sequence changes during capture.
- Preview RAW A/B slots retain two immutable pixel copies. Each of at most 32
  subscribers has at most one pending copy per subscribed key; payloads are
  shared. Preview loss never changes reliable measurement persistence.
- Applied preview FPS caps the client's requested preview rate. Applied
  publication interval controls empty event-replay polling; it never drops or
  changes the count of authoritative samples.
- Error snapshots are PNG, at most two cached entries/1 MiB, admitted at most
  once per camera per five minutes. SQLite holds metadata; retrieval resolves
  the snapshot ID to a trusted key, validates containment and reads the object.
  An unavailable image directory does not prevent session finalization.
- Encoded objects and SQLite history persist until operator retention policy is
  supplied; bounded in-memory retention does not imply bounded disk history.
- Measured camera processing latency includes downstream backpressure/storage
  acknowledgement. Fusion/database counters describe durably acknowledged
  samples; they may lag live accepted state by the bounded write buffer.

## Verification

The E2E suite checks image values/metadata/statistics, concurrent REST commands,
real event/preview WebSockets, restart history, source failure, snapshot
containment/cooldown, configuration provenance and cancellation, and a real
loopback diagnostic client. The sustained test executes 230,400 dual-camera
ticks / 460,800 image frames, equivalent to eight hours at 8 Hz per camera. Its
supported batch setting is 256 records/16 seconds to reduce physical SQLite
commits; the image cadence and official processing are unchanged.

```powershell
.\gradlew.bat :backend:test --tests "com.filamentvision.backend.e2e.*"
.\gradlew.bat test verifyCorePlatformBoundary --rerun-tasks
```

The temporary reference characterization module remains solely for the later
compatibility/independent-build gates. No Android consumer migration or Phase 3
UI work is included here.
