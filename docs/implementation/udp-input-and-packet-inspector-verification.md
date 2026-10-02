# UDP Input and Packet Inspector Verification

## Scope

Task 27 verifies the approved UDP pipeline through the production `BackendComposition`. The production composition uses `UdpCameraInputSource`; the simulator remains an explicit test fixture and is not a runtime dependency of the UDP path.

## RED evidence

The first valid loopback test run failed for two integration-only reasons:

- `invalidHeaderCount` remained zero because the framing parser emitted `HEADER_MISMATCH` while diagnostics recognized only the older normalized `INVALID_HEADER` name.
- parser-only Apply timed out because a running receiver callback retained the processor that existed when the socket was opened instead of resolving the atomically replaced per-camera processor.

The minimal fix maps both equivalent diagnostics names and makes the receiver resolve the current processor at datagram consumption time. The per-camera processor and diagnostics maps are concurrent bounded maps containing exactly Camera A and Camera B.

## UDP loopback fixtures

`UdpInputEndToEndTest` sends real IPv4 loopback datagrams and verifies:

- STRUCTURED_CHUNKED GRAY8 frames split across multiple 34-byte transport headers and delivered out of order;
- independent Camera A and Camera B ports;
- invalid FRAME-scope Application Header rejection and diagnostics;
- parser-only Apply without socket restart;
- listen-port Apply with safe receiver restart;
- SINGLE_DATAGRAM ASCII DIAMETER input;
- IPv4 source mismatch rejection with Inspector observation;
- sentinel detection for oversized/truncated datagrams;
- Inspector Raw Datagram transport metadata and Parsed Frame data;
- reliable session, measurement, alarm, and finalization events while Inspector snapshots are not polled.

No wire fixture changes the approved 34-byte structured transport layout.

## Boundedness observations

The focused `LongRunBoundednessTest` passed with no skipped tests.

```text
UDP_BOUNDED cycles=16 maxProducer=1 receivers=0 reassembly=0 inspector=0 commands=32/50
LONG_RUN ticks=230400 frames=460800 accepted=460800 persisted=460800
virtualMillis=28800000 maxQueued=254 maxAlarms=127 realtime=300 replay=256
previews=2 commands=128/128 dbBatches=1800
```

Assertions cover reassembly bytes, in-flight frames, reassembly tombstones, Inspector retained bytes/snapshots, receiver jobs, input mailbox entries, runtime/facade command caches, and `activeProducerCount <= 1` across repeated Apply/start/stop cycles.

## Phase gate

Executed from the independent `webiste2.0` root:

```text
.\gradlew.bat --no-daemon clean :core:test :backend:test verifyStandaloneIndependence
CORE: 120 tests, 0 failures, 0 skipped
BACKEND: 233 tests, 0 failures, 0 skipped
STANDALONE_INDEPENDENCE PASS scanned=115 modules=core,backend

frontend\> npm test
10 files, 25 tests passed

frontend\> npm run lint
passed

frontend\> npm run build
passed (Vite production build)
```

The Kotlin gate includes the existing Vision, Fusion, Measurement, Alarm, Statistics, Session, runtime concurrency/failure, SQLite, Preview backpressure, reliable event, simulator fixture, REST/media, smoke, and long-run regressions.
