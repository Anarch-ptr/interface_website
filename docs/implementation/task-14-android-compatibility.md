# Task 14 — Android compatibility checkpoint

This checkpoint follows the approved execution-path override. It verifies the
unchanged Android algorithms against the extracted Core and Backend at standalone
commit `e4813af83fca8f1a6cbd2a42d95cefb2c81ea782`; it does not switch Android
consumers. Retained evidence and Android build output are under the standalone
workspace; the only tracked change is this document. Run date: 2026-10-02,
Australia/Sydney.

## Reference integrity and isolated execution

Reference: `E:\Study\Monash\Y2S2\ENG5105\interface`.
Standalone workspace: `C:\Users\asus\Documents\ChatGPT\5105interface\webiste2.0`.
The fresh execution mirror is `.gradle/task14-android-20261002`, copied with:

```powershell
robocopy E:\Study\Monash\Y2S2\ENG5105\interface .gradle\task14-android-20261002 /E /XD .git .gradle .idea .kotlin build .tmp /XF *.iml /R:0 /W:0 /NFL /NDL /NJH /NJS
```

The destination did not exist before copying. `git check-ignore` confirmed that
the Android mirror, disposable characterization harness and raw evidence directory
are ignored. No Gradle command ran with the E: Android checkout as its project
directory; no adapter, dependency, build output or commit was written there.

Before and after checks used `GIT_OPTIONAL_LOCKS=0`, `git -c
safe.directory=E:/Study/Monash/Y2S2/ENG5105/interface -C <reference>` with
`status --porcelain=v1`, `rev-parse HEAD` and `ls-files`, then SHA-256 of all
174 tracked files. Both HEAD values were
`6c798197d604c27b4414fb9c00bce8f2529cb310`. Both status outputs were exactly:

```text
?? docs/superpowers/plans/2026-10-01-phase2-backend-foundation.md
?? docs/web-migration/
```

The complete before/after JSON snapshots are byte-identical, SHA-256
`EB95F84C41E8578E72A70FA547DCAB6725C332A6AE7F92D844412FEB2E654493`.
Thus no tracked content, HEAD or reported working-tree state changed.
All 174 copied tracked files also matched the reference after the disposable
mutation was restored. Selected before/after hashes follow; paths are relative to
the Android root:

| File | SHA-256 before = after |
|---|---|
| `app/src/main/java/com/filamentvision/vision/processing/VisionProcessor.kt` | `0A2E606C4B458916326CA9D6337CC5CF23E9A0B2B0F21D56A101B19E43923DFE` |
| `app/src/main/java/com/filamentvision/vision/fusion/FusionEngine.kt` | `9757E11D1DCE5178E8B9BC213364FFE5E66FA4C74632BBFBAC7554DA8E51C5AC` |
| `app/src/main/java/com/filamentvision/domain/alarm/AlarmEvaluator.kt` | `BBC3CBC1CF107FB52783BCDCE25F80CF7D2F4038D8231770E03D1CDECADCD3A8` |
| `app/src/main/java/com/filamentvision/domain/session/SessionStatisticsAccumulator.kt` | `2275C2797D2B50A156BBE81AE5225919C7B75EDD5C6F38DF70C360406726BC71` |
| `app/src/main/java/com/filamentvision/vision/detection/DetectionPipeline.kt` | `9C9289D648B41909645D15530DFC6D1FA78760C8A2C1A97FA3D2F7DBD3257966` |
| `app/build.gradle.kts` | `CB966AEC188413B3AE71DA4F4A5110A1B35FB126EBE2BFCE29CA8EE882F6D35E` |
| `build.gradle.kts` | `14777525DE5C24503EAB3304B956C2A09788471610870D1E7D6F6A4F492CB062` |
| `settings.gradle.kts` | `E16A0FF146803DD7190B1FB9A88B66CD31ABDA2865CC2DA8AE2DF1D99C45DEE4` |
| `gradle.properties` | `254098FC1C962DABB68777239281A89EC1A59DD9C33B7166EE977239EE6897DF` |
| `gradle/gradle-daemon-jvm.properties` | `231649D2F8E8DC24310F18C921E3AE33FD2715B7F48A7DDCAF27A85003E0729A` |
| `gradle/wrapper/gradle-wrapper.properties` | `70FA201501995E6F45C0C475DEF71093F8115849344C48322CA997CFDDBA8534` |
| `gradle/wrapper/gradle-wrapper.jar` | `55243EF57851F12B070AD14F7F5BB8302DACEEEBC5BCE5ECE5FA6EDB23E1145C` |
| `gradlew` | `AED171FB114F82E6EAEA4970A245A200E0582A7DCC8EC0891CA41B6E4A62B754` |
| `gradlew.bat` | `FEDAD02C18E266EC094995A5751B7FE1EB6E74F66BF75DB64FAE2E50EB22C234` |

## Commands and results

The installed tools and existing local dependency caches were reused offline:

```powershell
$standalone='C:\Users\asus\Documents\ChatGPT\5105interface\webiste2.0'
$env:JAVA_HOME='E:\Study_Software\Android\.gradle\jdks\eclipse_adoptium-21-amd64-windows.2'
$gradle='E:\Study_Software\Android\.gradle\wrapper\dists\gradle-9.4.1-bin\arn2x92ynaizyzdaamcbpbhtj\gradle-9.4.1\bin\gradle.bat'
$env:GRADLE_USER_HOME="$standalone\.gradle\android-home"
Set-Location "$standalone\.gradle\task14-android-20261002"
& $gradle :app:testDebugUnitTest --tests 'com.filamentvision.vision.processing.VisionProcessorTest' --tests 'com.filamentvision.vision.fusion.FusionEngineTest' --tests 'com.filamentvision.domain.alarm.AlarmEvaluatorTest' --tests 'com.filamentvision.domain.session.SessionStatisticsAccumulatorTest' --offline --rerun-tasks --console=plain '-Pkotlin.compiler.execution.strategy=in-process'
& $gradle :app:testDebugUnitTest :app:assembleDebug --offline --console=plain '-Pkotlin.compiler.execution.strategy=in-process'

Set-Location $standalone
$env:GRADLE_USER_HOME="$standalone\.gradle"
.\gradlew.bat :core:test --tests 'com.filamentvision.core.vision.*' --tests 'com.filamentvision.core.fusion.*' --tests 'com.filamentvision.core.alarm.*' --tests 'com.filamentvision.core.session.*' --tests 'com.filamentvision.core.architecture.*' :core:verifyCorePlatformBoundary :backend:test --tests 'com.filamentvision.backend.e2e.*' :reference-characterization:test --offline --rerun-tasks --console=plain
```

| Gate | Result |
|---|---|
| Selected Android algorithms | 12 tests / 4 classes, zero failures/errors/skips; BUILD SUCCESSFUL, 2m 17s, 26 tasks executed |
| Full Android unit suite and debug APK | 78 tests / 30 classes, zero failures/errors/skips; BUILD SUCCESSFUL, 46s, 19 tasks executed / 25 up-to-date |
| Core Vision/Fusion/Alarm/Statistics/pairing/architecture | 41 tests / 9 classes, zero failures/errors/skips |
| Backend E2E | 17 tests / 6 classes, zero failures/errors/skips |
| Read-only E: reference characterization | 9 tests / 3 classes, zero failures/errors/skips |
| Standalone combined gate and Core firewall | BUILD SUCCESSFUL, 5m, all 13 tasks executed |
| Restored disposable characterization | 9 tests / 3 classes, zero failures/errors/skips; BUILD SUCCESSFUL, 37s, 2 tasks executed |
| Resolved production graph | BUILD SUCCESSFUL, 14s; Core 6 components / 3 runtime files; Backend 63 components / 45 runtime files |

The Backend E2E long run took 153.736s and reported 230,400 ticks,
460,800 frames, accepted = persisted = 460,800, virtual duration 28,800,000ms,
max queued measurements 254, max alarms 127, realtime samples 300, replay 256,
previews 2, command caches 128/128 and database batches 1,800. Its assertions also
confirmed one total/max producer and zero active producers at stop.

Debug APK: `.gradle/task14-android-20261002/app/build/outputs/apk/debug/app-debug.apk`,
12,903,821 bytes, SHA-256
`55C36192290E21284A81F26437710E7C362BEF7352516C1A9B32E3F5048245BB`.
This is byte-identical to the Task 1 APK. The full unit test task executed again
without the selected-class filter; its XML was preserved separately from the
12-test run. No device installation was performed.

## Controlled RED and restored GREEN

The existing characterization module and fixtures were mechanically copied to
ignored `.gradle/task14-characterization`, with a minimal settings file including
only that module. Only this disposable build script's source root was redirected
to `../../task14-android-20261002/app/src/main/java`. The copied Android
`DetectionPipeline.kt:84` expression was changed from
`width * calibration.mmPerPixel` to `width * calibration.mmPerPixel + 0.01`.
The tracked algorithm, tests and golden fixture were never edited.

From the disposable root, with the standalone `.gradle` home and same JDK/Gradle:

```powershell
& $gradle :reference-characterization:test --tests 'com.filamentvision.characterization.VisionGoldenCharacterizationTest' --offline --rerun-tasks --console=plain
```

The process exited 1, with exactly one test and one assertion failure:

```text
java.lang.AssertionError: expected:<0.4> but was:<0.41000000000000003>
VisionGoldenCharacterizationTest.darkStripPreservesEveryPreviewStageAndMeasurementMetadata
VisionGoldenCharacterizationTest.kt:74
```

The raw failing JUnit XML was preserved before restoration. The original
`DetectionPipeline.kt` was copied back from E:, and every mirror tracked-file
hash was compared with the pre-run snapshot. All 174 matched. Then the same
disposable harness ran `:reference-characterization:test --offline --rerun-tasks
--console=plain` without a class filter: all nine tests passed. Selected and full
Android verification used this restored source. The frozen fixture SHA-256 remains
`BF3CF76985588D7F40E4580B8B064D4F6EB37B35E158C96F5169D0394CA0A8ED`.
The three tracked characterization test hashes still match Task 1.

## Matched fixture behavior

The original Android sources and current Core were each checked against the same
frozen inputs/expected values. This is fixture equivalence, not a claim of
exhaustive equivalence for every possible input. Vision's fixed measurement and
stage assertions use exact equality; Fusion and Statistics derived floating-point
values use the existing `1e-12` tolerance where specified by their golden tests.

| Case | Matching Android/Core evidence |
|---|---|
| Fixed dark strip | 8×4, A / timestamp 99 / sequence 3; edges 2 and 6; pixel width 4.0; diameter 0.4 mm; confidence 1.0; threshold 40; valid, no error |
| Vision intensity rows | grayscale `[100,100,20,20,20,20,100,100]`; threshold `[255,255,0,0,0,0,255,255]`; edges `[0,255,255,0,0,255,255,0]`; ROI `[0,0,8,4)` |
| Weighted Fusion | A=1.7/.9 at 1000, B=1.8/.8 at 1050, weights .25/.75: diameter 1.775, confidence .85, shape/raw difference .1, timestamp 1050, NORMAL/BOTH_CAMERAS |
| Zero weights | arithmetic mean 1.75; confidence .85; shape difference .1 |
| Missing or rejected B | A=1.7/.9; CAMERA_B_FAILURE; CAMERA_B_MISSING or CAMERA_B_LOW_CONFIDENCE; shape 0 |
| Missing or rejected A | B=1.8/.8; CAMERA_A_FAILURE; CAMERA_A_MISSING or CAMERA_A_LOW_CONFIDENCE; shape 0 |
| Fusion diagnostics | both inputs present but one rejected: raw difference .1 retained; input absent: Core null projects to Android NaN; absent camera diameter/confidence project to NaN/0 |
| Timestamp mismatch | inputs at 1000/1500 choose the newer valid side; timestamp 1500; TIMESTAMP_MISMATCH, compatibility false, raw difference .1; both timestamp directions covered |
| No usable cameras | no fused result |
| Alarm around 2%/5%, target 2.0 | 2.0399 NORMAL/NONE; 2.0401 and 2.0999 WARNING/HIGH; 2.1001 CRITICAL/HIGH; 1.9601 NORMAL/NONE; 1.9599 and 1.9001 WARNING/LOW; 1.8999 CRITICAL/LOW |
| Alarm transitions | NORMAL→WARNING/HIGH at 125 (2.05); →CRITICAL/HIGH at 375 (2.11); →CRITICAL/LOW at 625 (1.88); →NORMAL/NONE at 875 (2.0); repeated same-level/direction samples emit no transition |
| Statistics, diameters 1/2/3 and confidence .7/.8/.9 | count 3, mean 2, min 1, max 3, population standard deviation .816496580927726, average confidence .8, abnormal count 2; empty count/stddev 0 with caller abnormal count 1 |

Core additionally verifies confidence .7 and timestamp difference 100ms inclusive,
negative-weight clamping, invalid-input reason precedence, exact ±2%/±5% alarm
thresholds using integer-representable differences, and large-offset Welford
stability. These are Core checks; they are not presented as additional Android
characterization cases.

Intentional boundary differences remain: Core represents missing accepted camera
evidence/diagnostic differences as nullable values; a test-only legacy projection
uses Android NaN/zero absence conventions. Core retains source sequence in
accepted camera evidence. Core diagnostic intensity buffers are scalar 0–255 and
full resolution, while Android previews pack grayscale ARGB and may resize to
480 pixels for presentation. Core normalizes source orientation/mirroring before
configured geometry. Stage timings are platform-dependent and are not claimed
numerically equivalent. No Android production adapter was introduced.

## Production graph, limitations and Task 15 readiness

Inspection of root/Core/Backend Gradle files found no production file dependency,
source mapping or project edge to the Android checkout or the reference module.
Backend's only local production project dependency is `project(":core")`.
The ignored `production-graph.init.gradle` resolved both runtime classpaths and
asserted that neither component IDs nor runtime files refer to Android `:app`,
`reference-characterization`, the E: checkout or the disposable mirror. It ran as:

```powershell
.\gradlew.bat task14VerifyProductionGraph --init-script .gradle\task14-evidence\production-graph.init.gradle --offline --console=plain
```

Core resolves Kotlin 2.2.10/coroutines 1.9.0; the existing Backend transitive graph
resolves Kotlin 2.2.20/coroutines 1.10.2. No dependency versions changed here.
The production graph is independent, but the complete Gradle configuration is
not yet independent: `settings.gradle.kts` still includes the temporary
`reference-characterization` module, whose configuration requires E: source and
local compiler/cache paths. Task 15 must remove this temporary wiring and prove
build/test/run with the Android path unavailable. That removal/gate is not part
of this checkpoint.

The first selected Android invocation failed before test execution because an
unquoted PowerShell `-Pkotlin.compiler.execution.strategy=in-process` argument
was split. The quoted rerun above succeeded. The standalone gate logged the known
Kotlin daemon marker access denial under `%LOCALAPPDATA%` and successfully used
in-process fallback. Android used explicit in-process compilation. Existing
Android coroutine opt-in and native-symbol strip warnings remain. The temporary
characterization compiler is installed Kotlin 2.2.20; Android's declared Compose
Kotlin plugin and Core plugin remain 2.2.10. Execution uses JDK 21, with standalone
JVM bytecode target 17. No emulator, instrumentation, physical camera, UI or
hardware throughput validation is claimed.

Raw logs, preserved JUnit XML/summaries, graph check and integrity snapshots are
local ignored artifacts under `.gradle/task14-evidence/`. Task 14 authorizes the
documented compatibility checkpoint only; Task 15 and Phase 3 remain separate.
All required checkpoint gates passed. Task 15 can now remove temporary reference
wiring and run its independent-build acceptance gate, subject to its own review.
