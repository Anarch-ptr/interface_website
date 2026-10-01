# Task 6 — deterministic image simulator

The backend simulator implements `ImageInputSource` and produces canonical
48×8 ARGB32 frames only. It imports no Vision/Fusion measurement, Alarm,
repository, API, transport or persistence type. Core remains unchanged.

## Image scenarios

Pixels use opaque intensity 20 for the centered dark strip and intensity 200
for the background. Source orientation is 0 and mirroring is false. The tested
Vision configuration uses the full 48×8 ROI, threshold 40, edge sensitivity 30,
width limits 1–46 pixels, minimum confidence 0.7 and calibration 0.05 mm/pixel.
Calibration belongs to the consumer, not the simulator.

| Scenario | Image/timing/availability | Verified real Core outcome |
| --- | --- | --- |
| NORMAL | Width 35, both cameras | 1.75 mm, confidence 1, dual-camera fusion |
| HIGH_DIAMETER | Alternates widths 36 and 37 | 1.80/1.85 mm; above 2%/5% high deviation |
| LOW_DIAMETER | Alternates widths 34 and 33 | 1.70/1.65 mm; beyond 2%/5% low deviation |
| CAMERA_A_ONLY | B omitted | 1.75 mm, CAMERA_B_MISSING / CAMERA_B_FAILURE |
| CAMERA_B_ONLY | A omitted | 1.75 mm, CAMERA_A_MISSING / CAMERA_A_FAILURE |
| CAMERA_MISMATCH | A width 33, B width 37 | Raw 1.65/1.85 mm, shape difference 0.20 mm, fused 1.75 mm |
| TIMESTAMP_MISMATCH | Normal pixels, B capture time +500 ms | With 100 ms tolerance, TIMESTAMP_MISMATCH and newer B selected |
| LOW_CONFIDENCE | A strip appears in only four of eight rows; B normal | A confidence 0.5 and Vision LOW_CONFIDENCE at 0.7; admitting A at 0.5 for diagnostics demonstrates Fusion's 0.7 rejection and B fallback |
| TEMPORARY_DROPOUT | B absent on scenario ticks 1–3; present at tick 0 and from tick 4 onward | Stale B causes timestamp fallback, then dual-camera fusion resumes |
| CONNECTION_FAILURE | No images; pending images discarded | ERROR state, current error diagnostic, one retained producer owner |
| Recovery | Explicit `setScenario(NORMAL)` | RUNNING, cleared current error, continuing sequences, no new producer |

The current Fusion engine preserves camera disagreement through shape difference;
it does not introduce a new mismatch status. This task preserves that behavior.
Tests invoke the real VisionProcessor, LatestCompatiblePairingStrategy and
FusionEngine. No test or production simulator constructs measurement values.

## Controls and clocks

Construct with an owning coroutine scope, dispatcher, distinct camera IDs,
positive frame period (default 125 ms), epoch clock and monotonic clock. Defaults
use system clocks; tests inject virtual time. Camera IDs are never inferred from
literal A/B strings. `scenario` is a read-only StateFlow; `setScenario` makes
the selected value observable immediately, and the next producer tick applies
it to the pair. No source restart is needed. Changes are latest-value controls;
several changes between ticks can coalesce. A changed scenario resets its local
tick counter when observed; starting a new producer also resets scenario ticks.

Each emitted camera frame increments that camera's sequence from 0. Omission
does not increment the omitted camera's sequence. Sequences survive stop/start.
Capture times are logical epoch milliseconds: each tick uses at least one
millisecond beyond the largest previous capture time, even if the injected
wall clock stalls or moves backwards. The timestamp scenario adds 500 ms to B;
subsequent logical ticks therefore may advance ahead of wall time. Returning
to NORMAL synchronizes both camera times without moving either backwards.
This synthetic time policy is deliberate, not a claim about hardware clocks.
Receive times use the injected monotonic clock clamped to a non-negative value;
they are never subtracted from epoch time.

## Lifecycle and bounded delivery

A lifecycle mutex reserves and creates one producer Job. Twenty concurrent
starts are idempotent. Stop holds that mutex through cancellation and joining,
then clears the Job and returns IDLE. The producer's completion path does not
acquire the lifecycle mutex, so joining it while holding the lock cannot create
a lock dependency cycle. The stop cleanup uses NonCancellable after acquiring
the lock. Atomic coroutine start guarantees cleanup even when cancellation
happens before first dispatch. The injected scope must still be active to start.

`activeProducerCount` counts owned producer jobs, including the error-state
loop that waits for recovery. `totalProducerStarts` and
`maximumActiveProducerCount` expose lifecycle evidence. Restart yields two
total starts and maximum concurrent ownership one. Thirty rounds of twenty
concurrent start/stop calls exercise command races with Dispatchers.Default
while frame scheduling remains under virtual time.

The frame stream is a work-sharing ingress for the future runtime, not a
broadcast bus: collectors compete for frames and never start or stop the source.
Each collector owns a successfully emitted frame and closes it after use. If
`emit` throws or is cancelled, the source closes the removed frame, counts one
drop and rethrows; lease idempotence protects a frame already closed downstream.
A collector must finish using an interrupted emission's frame before unwinding,
and must not retain it after collection fails or short-circuits. Future preview
fanout belongs downstream of that owner. There is one pending frame per camera
(at most two images), plus one conflated wake-up signal. Replacement closes
the old image and increments `droppedFrames`. Stop/failure also close and count
pending undelivered images exactly once. The queue survives lifecycle commands
but is emptied; collection may stay attached across restart. Already accepted
frames belong to the collector and are not revoked by stop.

Each generated image owns a fresh array, exposed only through PixelBuffer's
read-only view. There is no mutable external input to copy and no array reuse.
The injected frame factory can supply a fresh FrameLease per image, allowing
the composition root to attach resource-release policy without changing delivery.
CameraFrame.close is safe to call repeatedly under its existing lease contract.
Diagnostic/state/scenario flows retain one value each. Identity/sequence maps
have exactly two configured keys. No history, reliable event delivery, runtime,
API, database, Android or Raspberry Pi implementation is added.

## Validation

Lifecycle/normal tests were written and failed for missing simulator types before
implementation. Every additional scenario was added in its own RED/GREEN cycle;
recovery initially failed because the active error remained set. Constructor
validation and lifecycle drop accounting also have observed assertion REDs.
Focused virtual-time tests and the full Core/backend/reference suite plus
Core platform firewall are required before the task commit. The full RED/GREEN
log, counts and commit are recorded in the Task 6 report under `.superpowers`.
