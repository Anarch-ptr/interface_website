# Task 5 — Fusion, pairing, Alarm and Statistics

The portable Core now accepts the Task 4 `CameraMeasurement` and produces an
`OfficialMeasurement` through `FusionEngine.fuseDetailed`. The Task 1 reference
tests and fixtures remain unchanged. No backend infrastructure or later task
implementation is included.

## Measurement and compatibility

`OfficialMeasurement.cameraA` and `.cameraB` contain the accepted immutable
camera measurements, including identity, timestamp, source sequence, diameter,
confidence and detection evidence. A missing, rejected or stale side is null.
The official result also contains fused diameter, shape difference, combined
confidence, status, timestamp and fusion reason. The focused status enum contains
the three statuses emitted by the existing Fusion engine; no new classification
policy was introduced.

`FusionResult` exposes active camera identities, timestamp compatibility and the
raw input camera difference. The latter still includes a supplied camera rejected
by Fusion, matching the Android diagnostic behavior; it is null if an input is
absent. The reason is derived from the official measurement to avoid duplicated
state. A test-only compatibility projection maps an absent raw diameter or
diagnostic difference to Android's `Double.NaN`, and absent confidence to zero.
Core itself does not use NaN as an absence sentinel.

Frozen examples use weights .25/.75, minimum confidence .7 and tolerance 100 ms:

| Input | Official output | Reason |
| --- | --- | --- |
| A 1.7/.9/1000; B 1.8/.8/1050 | 1.775; confidence .85; shape .1; NORMAL | BOTH_CAMERAS |
| Both weights zero | 1.75 arithmetic mean | BOTH_CAMERAS |
| Only A accepted | A diameter/confidence; CAMERA_B_FAILURE; B null | CAMERA_B_MISSING or CAMERA_B_LOW_CONFIDENCE |
| Only B accepted | B diameter/confidence; CAMERA_A_FAILURE; A null | CAMERA_A_MISSING or CAMERA_A_LOW_CONFIDENCE |
| Both rejected/absent | No official result | No result |
| Valid timestamps 1000/1500 | Newer side only; compatibility false | TIMESTAMP_MISMATCH |

Golden tests also cover the inclusive confidence/timestamp boundaries, negative
weight clamping, invalid flags and reason precedence. Accepted raw sequences
31/42 survive dual fusion; fallback evidence retains sequence 7.

## Pairing contract

`FramePairingStrategy.offer(CameraMeasurement): List<MeasurementPair>` and
`reset()` own pair selection. `LatestCompatiblePairingStrategy` takes distinct
Core `CameraId` identities for A/B, stores the most recently offered result on
each side, and returns one pair per offer, even when its counterpart is absent.
Each counterpart may be reused. Arrival order, rather than maximum timestamp,
defines latest. Timestamp tolerance and confidence filtering remain in Fusion.
Unknown IDs fail explicitly without changing stored evidence; reset clears both
sides. The owner must serialize access.

`FusionCoordinator` receives its strategy, engine and configuration by injection.
It fuses all returned pairs in order and omits unusable results. Tests replace the
strategy with one returning multiple pairs (including an unusable pair), or no
pairs, proving the coordinator does not invent or override pairing policy.
Pairing imports only Core identities and camera measurements: no transport,
simulator, persistence or Web API types. Consume-once pairing can later replace
the strategy without changing infrastructure.

## Alarm and Statistics

Alarm keeps the immediate target-relative rules: below 2% is NORMAL/NONE,
2% inclusive is WARNING, and 5% inclusive is CRITICAL. Direction is LOW/HIGH
outside the normal band. Every observation updates signed deviation percent;
only a change in level or direction emits a transition, carrying the official
timestamp and fused diameter. The evaluator retains the baseline constructor
validation. No hysteresis, debounce or acknowledgement exists.

Statistics use Welford population variance and constant memory. The fixed
diameters 1/2/3 with confidences .7/.8/.9 yield count 3, mean 2, min 1, max 3,
standard deviation .816496580927726 and mean confidence .8. The caller's abnormal
count is copied into each snapshot. Empty snapshots retain zero defaults;
snapshots do not reset accumulated state or change after later samples.

## Verification

Each domain slice observed missing-type compilation RED before its production
implementation, then focused GREEN, in order: Fusion (7 tests), pairing and
coordinator (7 additional tests), Alarm (4), Statistics (4). Expected Task 1
values were not changed. The joint Core domain/reference run passed 22 Core
domain tests plus the unchanged 9 reference tests.

Commands run from this repository with local `.gradle` as `GRADLE_USER_HOME`
and the installed JDK 21 (targeting Java 17 bytecode):

```powershell
.\gradlew.bat :core:test --tests "com.filamentvision.core.fusion.*" --tests "com.filamentvision.core.alarm.*" --tests "com.filamentvision.core.session.*" :reference-characterization:test --offline --rerun-tasks
.\gradlew.bat :core:test :core:verifyCorePlatformBoundary :backend:test :reference-characterization:test --offline --rerun-tasks
```

The full rerun passed 50 Core, 3 backend and 9 reference tests (62 total; zero
failures, errors or skips), including the Core platform firewall. Both whitespace
checks passed, and fixture/reference diffs were empty. The full staged diff
contains only these 13 Task 5 files. Detailed evidence is in the execution report.
The reference harness still requires its existing absolute local Android-source
and compiler paths. Numerical validation beyond existing behavior is intentionally
unchanged; null removes absence sentinels, not all possible non-finite caller
inputs. Pairing, Alarm and Statistics rely on serialized ownership in the future
runtime. Task 6 may consume these contracts after review; it is not implemented
by this change.
