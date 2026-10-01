# Final standalone verification

The root `verifyStandaloneIndependence` task checks active build/settings and
production configuration/source, excludes generated outputs and historical notes,
and rejects the temporary module, Android application/project coupling and the
absolute reference source path. Only `:core` and `:backend` may be registered.
Resolved source roots must stay inside the workspace, and local file dependencies
are rejected; all external libraries use declared repository coordinates.
Module `check` tasks include this gate; run it explicitly alongside direct `test`.

```powershell
.\gradlew.bat --stop
.\gradlew.bat clean verifyStandaloneIndependence :core:test :core:verifyCorePlatformBoundary :backend:test :backend:installDist --offline --rerun-tasks '-Pkotlin.compiler.execution.strategy=in-process'
1..20 | ForEach-Object {
    .\gradlew.bat :core:test --rerun --tests 'com.filamentvision.core.runtime.*' :backend:test --rerun --tests 'com.filamentvision.backend.e2e.*' --offline
    if ($LASTEXITCODE -ne 0) { throw "Repeated verification failed on run $_" }
}
```

`--rerun` belongs to each test task; compile prerequisites can be up-to-date while
both test tasks execute on every serial iteration. The E2E selection includes the
eight-hour virtual-time image workload. Preserve each run's JUnit XML before the
next run overwrites Gradle's result directory.

## Real 30-minute verifier

```powershell
.\gradlew.bat :backend:runSustainedVerifier --offline
```

Alternatively, after `installDist`, from the repository root:

```powershell
java -Xmx256m -cp 'backend/build/install/backend/lib/*' com.filamentvision.backend.SustainedVerifier
```

The duration is fixed at 1800 monotonic real seconds, with four 450-second windows:
NORMAL, CAMERA_MISMATCH, TEMPORARY_DROPOUT, NORMAL recovery. The existing simulator
uses its unchanged nominal 125 ms/camera period. The temporary-dropout scenario
omits B only at ticks 1–3; event polling observes that brief transition. Mismatch
is evidenced by raw 1.65/1.85 mm camera values and shape difference, because the
preserved engine has no new mismatch status.

The driver uses the existing `BackendComposition` directly and adds no API or
monitoring behavior. A one-second preview collector creates real preview loss.
Reliable replay is polled every 20 ms with strict contiguous-sequence assertions.
Every 30 seconds, and at transitions/stop, CSV records wall time, heap used and
committed, producers, accepted/persisted samples, realtime/write/alarm retention,
preview slots/pending/drops, event retention/sequence, snapshot entries/bytes,
command caches, source drops, per-camera processed counts/rates and maximum
instrumented processing latency. All retained-structure capacities are asserted.

Samples are separate read-only observations while processing continues, not an
atomic database/runtime transaction. A row's later persisted count can exceed its
earlier accepted snapshot. Equality is required after stop, including statistics,
measurement events, database acknowledgements, alarm records/events and exactly
one finalization. The final source count and queues must be zero. The nominal
8 Hz rate permits scheduler variation (predeclared 7.0–8.5 processed Hz/camera),
and actual rates are reported. No forced GC is used; heap reserve/JIT/GC behavior
is assessed separately from bounded retained structures.

Output goes only to a new `build/phase2-final-sustained-<epoch>/` directory.
The CSV is flushed at every line; SQLite and snapshot objects remain for audit.
The driver closes the composition and writer in `finally`. A failed check exits
nonzero and cannot print PASS. The Gradle task caps JVM heap at 256 MiB.

On this Windows host the restricted token could create the snapshot directory
but `Path.toRealPath()` returned AccessDenied. The approved normal-user local run
uses the unchanged containment check. This is an execution-environment limitation,
not a reason to remove containment validation. JDK 21 is the installed compiler;
Core/Backend target JVM 17. A physical JDK 17 verification remains outstanding.
