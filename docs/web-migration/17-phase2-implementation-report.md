# Phase 2 implementation report

Status: all ten Phase 3 criteria PASS. **READY to start the Phase 3 Website against
simulator contracts**, subject to the planned independent review of this checkpoint.
This does not authorize or establish real hardware or production readiness.
Run date: 2026-10-02, Australia/Sydney.

## Scope and module boundaries

The standalone repository contains only `:core` and `:backend`. Core owns canonical
decoded frames/leases, portable Vision, injected pairing/Fusion, immediate
Alarm/Statistics, single-owner MonitoringRuntime, repository/configuration
contracts, bounded event/preview/snapshot structures and metrics. Backend owns
image-level simulation, SQLite/JDBC migrations and repositories, filesystem/PNG
adapters, the application composition/facade, explicit REST DTOs/routes, event and
preview WebSockets, executable server and diagnostic/verifier clients.

Images follow source → canonical frame → Vision → camera measurements → pairing
and Fusion → Runtime acceptance → Alarm/Statistics/realtime → acknowledged SQLite
batches → sequenced events and REST history. Preview has separate lossy storage.
Clients never own producer lifetime. One simulator/runtime exists for `simulator-1`.

Vision processing order and numerical formulas were extracted from the frozen
Android behavior. Fusion preserves latest-counterpart reuse, nullable accepted
camera evidence and timestamp fallback; mismatch remains shape difference rather
than an invented status. Alarm retains immediate 2%/5% thresholds and no hysteresis.
Statistics uses the original incremental population-variance calculation. New
runtime ownership and adapters implement the approved portable boundaries.

Android Room, SharedPreferences, codecs, foreground service, Compose, ViewModels,
navigation and production consumers remain in the unchanged Android repository.
Task 14 was a read-only compatibility checkpoint under the approved path override,
not an Android consumer switch. `frontend/` contains documentation only.

## Characterization, RED/GREEN and review history

Task 1 froze a real 8×4 dark-strip fixture: 4 pixels, 0.4 mm, confidence 1,
camera A/timestamp 99/sequence 3, and exact grayscale/threshold/edge arrays. Fusion,
missing/stale/confidence cases, immediate alarms and Welford statistics were also
frozen before extraction. The reference RED observed expected 0.41 versus actual
0.4; Task 14's disposable source mutation observed expected 0.4 versus actual
0.41000000000000003. Both were restored before GREEN. The frozen fixture and
Core golden expectations were never rewritten to fit implementation.

Task 14 independently compared the Android and Core paths with 9 reference tests,
41 focused Core tests and 17 E2E tests. It documented scalar-versus-packed-ARGB
preview representation, nullable-versus-NaN compatibility projection and timing
differences; these are not claimed as byte-identical presentation behavior.
Historical evidence remains in the Task 1 and Task 14 documents and Git history.

Review corrections were isolated: legal Java import parsing; canonical source
orientation/mirroring; simulator cancellation versus Flow short-circuit ownership;
late startup cleanup and reentrant persistence; flush deadlines independent of I/O;
strict REST media/charset decoding; published state/cursor ordering, command retry
and configuration provenance; exact-content SQLite retry and signed-zero handling.
Each reported behavioral defect had executable RED and fresh GREEN evidence in
its task report. The inherited same-line Java-import parser limitation is noted
below; no Core Java production source exists.

The final independence gate first failed on `settings.gradle.kts:15`, identifying
the still-registered temporary characterization module. Removal produced GREEN:
76 active files scanned and exactly Core/Backend registered. An initial overly
broad check also matched Core firewall policy strings; the executable RED recorded
for removal uses the corrected check and names only the actual registration.
Commit `f2dbe0e` removes the module registration and four tracked temporary files.
The tests remain recoverable from Git; fixtures and historical evidence remain.
The final gate additionally checks resolved source roots remain inside the export
and rejects local file dependencies. Disposable init-script probes failed with
`External source root in :core` and `Local file dependency in :core`; the clean
configuration passes. An initial source probe resolved inside the workspace and
correctly passed; the corrected escaping path produced the recorded RED.

## Fresh independent build and automated gates

Environment:

```powershell
$env:GRADLE_USER_HOME='<standalone>/.gradle'
$env:JAVA_HOME='E:\Study_Software\Android\.gradle\jdks\eclipse_adoptium-21-amd64-windows.2'
.\gradlew.bat --stop
.\gradlew.bat clean verifyStandaloneIndependence :core:test :core:verifyCorePlatformBoundary :backend:test :backend:installDist --offline --rerun-tasks --console=plain '-Pkotlin.compiler.execution.strategy=in-process'
```

| Gate | Fresh measured result |
|---|---|
| Main clean forced build/tests/distribution | PASS, 5m17s, 16 executed tasks; Core 117 + Backend 128 = 245 tests |
| Fresh standalone export | PASS, 5m6s, 16 executed / 2 up-to-date tasks; same 245 tests and installDist |
| Final configuration, second fresh export `clean build ... installDist` | PASS, 4m46s, 16 executed / 2 up-to-date tasks; 245 tests, no failures/errors/skips |
| Final main `build ... installDist`, forced after all repetitions | PASS, 4m53s, all 16 tasks executed; 245 tests, no failures/errors/skips |
| JUnit results | Both complete builds: 40 classes; 0 failures, errors or skipped tests |
| Core firewall | PASS in both complete builds and Runtime repetitions |
| Active standalone configuration | PASS; exactly Core and Backend; no temporary reference module |
| Production class bytecode | 376 classes checked, all major version 61 (Java 17) |
| Dependency graphs | Resolved offline; Core only Kotlin/coroutines/annotations; Backend owns Ktor/Jackson/JDBC/image adapters |

The fresh export `.gradle/task15-standalone-export` was created from 140 tracked or
task-owned source files. Before Gradle ran it contained no `.git`, `.gradle`, build
output, ignored Android mirror or local evidence. Its command additionally ran
`:core:dependencies --configuration runtimeClasspath` and the equivalent Backend
task. Only cached declared dependencies were resolved, offline. No Android source
was copied or referenced by active configuration; the original Android repository
was not renamed or modified to simulate unavailability.
After strengthening the gate, `.gradle/task15-final-export` was newly exported
with 143 files and passed `clean build verifyStandaloneIndependence
:backend:installDist --offline --rerun-tasks` with the same compiler flag. All 125
source/build/fixture hashes match the main workspace. Its eight-hour virtual test
took 147.460s and reproduced every count and bound below.
After all twenty repetitions, the final main command was `build
verifyStandaloneIndependence :core:verifyCorePlatformBoundary :backend:installDist
--offline --rerun-tasks --console=plain '-Pkotlin.compiler.execution.strategy=in-process'`.
It followed another daemon stop. This last build preserved the completed wall-run
database/CSV, so it deliberately did not repeat `clean`; the clean main and clean
export evidence above remain separate. No production source changed after the
successful sustained verification and final export.

Core resolves Kotlin stdlib 2.2.10 and coroutines 1.9.0; Backend's existing transitive
graph selects stdlib 2.2.20/coroutines 1.10.2, alongside Ktor 3.3.1 and SQLite JDBC
3.51.1.0. The explicit resolved-file graph check rejects Android/reference coupling.
The compiler is physically JDK 21 with explicit JVM 17 target/release; this is not
a physical JDK 17 test run. No dependency versions changed in Task 15.

The main eight-hour virtual-time test took 159.874s; the export's took 145.959s.
Each drives 230,400 ticks / 460,800 actual generated image frames at a virtual
125 ms per camera through the complete processing and SQLite path. Expected and
asserted accepted = persisted = 460,800, maximum measurement queue 254, alarm
queue 127, realtime 300, replay 256, previews 2, command caches 128/128 and
1,800 database batches. This test uses the supported batch 256/flush 16 seconds
configuration to reduce disk commits. It is eight virtual hours, not eight wall hours.

## Twenty actual serial Runtime and E2E executions

The fixed command executes both test tasks using task-local `--rerun`:

```powershell
.\gradlew.bat :core:test --rerun --tests 'com.filamentvision.core.runtime.*' :backend:test --rerun --tests 'com.filamentvision.backend.e2e.*' --offline --console=plain '-Pkotlin.compiler.execution.strategy=in-process'
```

Each iteration contains 31 Runtime tests and 17 E2E tests (48 total, 9 classes),
including duplicate/distinct concurrent Start, Start/Stop races, Stop during Start,
late generation rejection, failed storage/source cleanup, close/restart, image
values, actual HTTP/WebSockets/DiagnosticClient, checkpoint races and the complete
eight-hour virtual workload. Successful assertions constrain source maxima to one
and successful finalization to complete flush; intentional storage-failure cases
remain explicitly fail-closed. Per-run XML and logs are archived before reuse.

All **20/20** invocations passed, **960 tests** in total, with zero failures,
errors or skipped tests. Aggregate invocation wall time was **3570.913 seconds**.
Every log contains actual `:core:test` and `:backend:test` execution, not
UP-TO-DATE/FROM-CACHE. Every independently archived long-run XML prints
460,800 accepted = persisted images. The table's producer maximum is asserted
by the executed ownership/E2E suites, not estimated from Gradle task state.

| Run | Tests | Invocation seconds | Asserted max producer |
|---|---:|---:|---:|
| 1 | 48 | 197.343 | 1 |
| 2 | 48 | 181.279 | 1 |
| 3 | 48 | 178.134 | 1 |
| 4 | 48 | 178.989 | 1 |
| 5 | 48 | 194.200 | 1 |
| 6 | 48 | 186.189 | 1 |
| 7 | 48 | 176.768 | 1 |
| 8 | 48 | 176.027 | 1 |
| 9 | 48 | 177.274 | 1 |
| 10 | 48 | 173.287 | 1 |
| 11 | 48 | 173.832 | 1 |
| 12 | 48 | 175.882 | 1 |
| 13 | 48 | 174.167 | 1 |
| 14 | 48 | 174.992 | 1 |
| 15 | 48 | 175.557 | 1 |
| 16 | 48 | 173.963 | 1 |
| 17 | 48 | 177.022 | 1 |
| 18 | 48 | 174.572 | 1 |
| 19 | 48 | 174.558 | 1 |
| 20 | 48 | 176.880 | 1 |

## Real sustained wall-clock gate

The minimal development [verifier](../implementation/task-15-verification.md)
uses the existing composition without changing runtime semantics. It runs four
450-second windows (NORMAL, CAMERA_MISMATCH, TEMPORARY_DROPOUT, NORMAL recovery),
polls reliable events every 20 ms, creates slow-preview pressure and writes full
30-second diagnostic samples under `build/phase2-final-sustained-*`.

PASS, process exit 0. Real elapsed duration was **1800.981 seconds**; Stop began
after the full 1800-second monitoring interval. Output is
`build/phase2-final-sustained-1790876003756/samples.csv` and the adjacent SQLite DB.
There are 64 diagnostic rows: 61 regular/start/stop observations plus three
transitions. Maximum regular sampling gap was 30.298 seconds, below 60 seconds.

| Sustained observation | Measured result |
|---|---|
| Accepted / persisted / reliable measurement events | 26,401 / 26,401 / 26,401 |
| Alarm rows / reliable alarm events | 6,616 / 6,616 |
| Session finalizations | Exactly 1; persisted final statistics count 26,401 |
| Source producer max / final / total starts | 1 / 0 / 1 |
| Processed A / B, input drops | 13,202 / 13,199; 0 drops (exact 3-frame B omission) |
| Actual A / B rate over full verifier wall duration | 7.330449 / 7.328784 Hz; nominal 8 Hz, predeclared scheduler tolerance passed |
| Realtime / replay / preview retained maxima | 300 / 256 / 2 |
| Sampled measurement / alarm queue maxima | 8 / 6; both 0 after Stop |
| Preview pending max / final; dropped deliveries | 2 / 0; 24,619 |
| Final event sequence | 46,315; every consumed envelope strictly contiguous |
| Snapshot entries / bytes | 0 / 0 throughout; declared capacities 2 / 1 MiB |
| Runtime / facade command caches at Stop | 2 / 1; capacities 128 each |
| Heap used min / max / final | 7,866,264 / 162,975,544 / 33,482,976 bytes |
| Heap committed | Constant 268,435,456 bytes (256 MiB) |
| Maximum instrumented processing latency | 89 ms; includes downstream backpressure |

Mismatch evidence, CAMERA_B_FAILURE during the dropout window, and recovered
dual-camera 1.75 mm NORMAL evidence all passed. A/B count difference independently
confirms the three omitted B frames; no ingress drops explain that difference.
The 24,619 intentional slow-preview drops did not reduce any reliable durable
count. Final accepted/persisted/event/statistics equality, alarm equality, empty
write queues and producer zero were executable assertions. Heap use showed
repeated GC sawtooth rather than continuous growth; fixed retained structures
stayed bounded independently of sample count. This is observed behavior for this
simulator workload, not a proof of leak freedom for arbitrary future adapters.

A separate read-only SQLite audit after process exit returned `integrity_check=ok`
and independently recomputed all finalized statistics: mean 1.737470171584409,
minimum 1.6500000000000001, maximum 1.75, population SD 0.03310568291751389,
mean confidence 1.0, and abnormal-event count 3,308. There are 3,308 CRITICAL and
3,308 NORMAL transitions, plus 3,308 dual-camera mismatch rows. The initial audit
incorrectly compared abnormal-event count with all transitions; source inspection
confirmed the preserved rule excludes NORMAL recovery transitions, and the
corrected query matched exactly. Mean/variance recomputation tolerance was 1e-10;
counts/extrema/confidence were exact. No runtime or expected golden value changed.

Heap used is assessed separately from the JVM's committed heap. Individual samples
are not atomic cross-system snapshots; final stopped equality is authoritative.
The simulator's nominal period is 125 ms per camera, with a predeclared scheduler
tolerance of 7.0–8.5 processed Hz; actual rates and source drops must be reported.
Snapshot retention in these nonfatal windows may stay empty; nonempty snapshot
admission, cooldown, containment and storage failure are covered by E2E tests.

## Standalone HTTP/WS smoke and SQLite restart

Two separate server JVM lifetimes and a real DiagnosticClient JVM ran against
loopback using the installed distribution. No Gradle or Android runtime was needed.
Normal A/B/fused values were 1.75 mm. Producers were 0 initially, 1 while running,
and 0 after Stop. DiagnosticClient received real measurement/state/finalization
events. Stop finalized and persisted 74 samples; a fresh server over the same
database returned the same 74 rows and identical finalized session statistics.
Both servers exited 0 after `shutdown`; the client exited 0; the port was closed.
Evidence: `build/phase2-final-smoke-e7efc901b7e645358c020e4e6ae38987/result.json`
and adjacent stdout/stderr logs.

The first smoke harness incorrectly read `finalization.statistics` instead of the
documented top-level session `statistics`. Its diagnostic log already showed
76 persisted/finalized samples. Only the harness DTO lookup was corrected; a new
full smoke then produced the 74-row result above. No backend behavior changed.

Windows restricted-token standalone startup hit `Path.toRealPath` AccessDenied at
the newly-created snapshot directory before monitoring began. The same approved
local command under the normal user ran successfully; the production containment
check was preserved. The sustained run encountered the same environment limitation.
The existing SLF4J no-provider/NOP warning is unchanged.

## Android read-only recheck

Fresh ignored mirror:
`.gradle/task15-android-ff71fffd788e405c90ebd6503bd562f1`.
No Gradle command ran inside the original E: reference checkout. The installed
Gradle 9.4.1, cached Android dependencies and in-process Kotlin compiler were used.

| Check | Result |
|---|---|
| Selected Vision/Fusion/Alarm/Statistics | 12 tests / 4 classes, 0 failures/errors, 1m54s, 26 tasks executed |
| Full Android suite | 78 tests / 30 classes, 0 failures/errors |
| `assembleDebug` | PASS; full invocation 39s, 19 executed / 25 up-to-date tasks |
| Original HEAD/status/tracked content | Identical before/after; all 174 SHA-256 values match |
| Mirror tracked content after build | All 174 hashes equal original |
| APK SHA-256 | `55C36192290E21284A81F26437710E7C362BEF7352516C1A9B32E3F5048245BB` |

Original HEAD remains `6c798197d604c27b4414fb9c00bce8f2529cb310`; the same two
pre-existing untracked documentation paths remain. The APK hash matches Task 1
and Task 14. Full integrity snapshots, selected/full XML and logs are retained in
`.gradle/task15-android-evidence`. Existing Android opt-in/native strip warnings
are unchanged. No production, adapter, Gradle or consumer changes were made there.

## All ten Phase 3 criteria

| # | Criterion | Decision and evidence |
|---|---|---|
| 1 | Headless simulator → Vision/Fusion/Alarm/Statistics → SQLite → REST/WS | PASS: fresh full E2E, real standalone diagnostic smoke and restart |
| 2 | Portable Core firewall and dependencies | PASS: fresh firewall, resolved graph, independent export and JVM17 bytecode |
| 3 | Characterization and side-by-side equivalence | PASS: frozen Core golden suite plus retained Task 1/14 independent reference/mutation evidence; original Android hashes unchanged |
| 4 | Concurrent/repeated lifecycle, failure/close/restart, one producer and once-only finalization | PASS: 20 actual serial forced executions, 960 tests, maximum producer 1; all required failure and finalization cases included |
| 5 | Preview pressure drops only previews and preserves metadata | PASS: fresh hub/WS metadata pressure tests; sustained 24,619 preview drops with all 26,401 measurements durably retained |
| 6 | Reliable measurement/alarm/session ordering and completeness | PASS: fresh hub/WS/checkpoint tests; sustained contiguous events, 26,401 measurement/6,616 alarm events and one finalization |
| 7 | Replaceable SQLite repository boundary | PASS: shared in-memory/SQLite conformance; Core/runtime and API depend on contracts, SQL stays in Backend adapter |
| 8 | History survives backend restart | PASS: fresh restart E2E and separate server JVM restart retaining 74 rows |
| 9 | Sustained bounded structures and complete final flush | PASS: 1800.981 real seconds, bounded sampled retention, repeated GC, complete 26,401-row final flush and zero producer/queues |
| 10 | Android unit tests and debug assembly | PASS: fresh selected 12/full 78/assembleDebug and unchanged reference hashes |

READY, after acceptance of this checkpoint's independent review, means ready to begin the Phase 3
Website against simulator contracts. It never implies production hardware readiness.

## Remaining uncertainties and design decisions

- **UNCONFIRMED — Raspberry Pi transport:** protocol, ports, framing, IDs, commands,
  acknowledgements, reconnect policy and authentication require hardware evidence.
- **UNCONFIRMED — Real camera input:** pixel formats, resolution, compression,
  orientation, timestamp clock, synchronization and sequence guarantees.
- **UNCONFIRMED — Compute placement and performance:** central versus Pi-side
  Vision, CPU/thermal/network capacity, real-image error and latency budgets.
- **UNCONFIRMED — Meaning of 8 Hz:** capture, fused output or persistence sampling;
  latest-counterpart reuse versus consume-once pairing remains a product decision.
- **UNCONFIRMED — Calibration:** traceability, real-image golden fixtures and
  acceptable measurement error. Synthetic strips do not establish device accuracy.
- **UNCONFIRMED — Deployment:** topology, device ownership/leases, authorization,
  TLS, request/connection admission and operational supervision.
- **UNCONFIRMED — Production storage:** database/object-store selection, retention,
  backup, crash recovery, orphan cleanup, quotas and snapshot privacy.
- **DESIGN DECISION REQUIRED — Apply while running:** current composition rejects
  all Apply until Stop, including preview settings, to preserve revision provenance.
  Any live barrier or finalize/restart policy needs a separate reviewed change.

Replay is process-local and bounded, not a durable outbox or cross-restart
exactly-once delivery. Slow/expired consumers recover from a coherent snapshot.
Preview provenance remains null/unknown. Disk history is deliberately durable and
grows until a retention policy is chosen; bounded memory does not bound disk.
Processing latency includes downstream storage/backpressure; uninstrumented
hardware/transport counters do not claim real hardware observations. Atomic file
visibility is not crash/fsync durability. Physical JDK17 validation is outstanding.
The existing firewall's same-line Java declaration/import corner remains a known
minor limitation; actual Core production contains Kotlin only and its resolved
runtime graph contains no forbidden platform dependency.

## Revertible checkpoints

| Task | Commits (implementation followed by review corrections) |
|---|---|
| Initialization | `2b148d7` |
| 1 | `0e900ae` |
| 2 | `56e4012`, `e7e3e0e` |
| 3 | `69d8644` |
| 4 | `be0e8a9`, `7794bf7` |
| 5 | `c7fb200` |
| 6 | `0ac524b`, `bfe49be`, `078cdad` |
| 7 | `7980557`, `95e3655`, `362a073` |
| 8 | `4ad1259` |
| 9 | `81d8720` |
| 10 | `14cc32d` |
| 11 | `233ccbd`, `bbe26d7` |
| 12 | `d219f5c` |
| 13 | `7018031`, `ef62085`, `e4813af` |
| 14 | `3bb659b` |
| 15 independence | `f2dbe0e` |
| 15 final verifier/report | The `docs: report phase two backend foundation` checkpoint containing this report; independent review follows |

Raw final evidence is retained in ignored `.gradle/task15-*`,
`.superpowers/sdd/2026-10-01-phase2-backend-foundation/evidence/task15/` and the
named `build/phase2-final-*` directories. Historical ignored task reports preserve
the exact RED/GREEN commands and review iterations. No Phase 3 implementation,
Android migration, real hardware protocol or production deployment was started.
