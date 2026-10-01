# Task 01 — Android algorithm characterization baseline

This is temporary migration evidence for the unmodified Android Vision, Fusion, Alarm, and Statistics implementations. `:reference-characterization` is a test-only Gradle module. It compiles 17 named pure Kotlin files directly from the absolute read-only source root `E:/Study/Monash/Y2S2/ENG5105/interface/app/src/main/java`; its complete allowlist is in `reference-characterization/build.gradle.kts`. No production module depends on it. The Android source root is deliberately not copied into this repository as production code.

## Environment and source integrity

The prescribed direct command in `E:\Study\Monash\Y2S2\ENG5105\interface` could not create `E:\Study_Software\Android\.gradle\wrapper\dists\gradle-9.4.1-bin\arn2x92ynaizyzdaamcbpbhtj\gradle-9.4.1-bin.zip.lck` (access denied). Pointing `GRADLE_USER_HOME` inside this writable repository avoided that lock but tried to download Gradle, which the offline sandbox denied. A direct installed-Gradle retry then could not create `interface\.gradle\9.4.1\fileHashes\fileHashes.lock`. To keep all Gradle writes away from the Android reference checkout, the Android source/build scripts were copied mechanically to ignored `.gradle/android-baseline` (excluding `.git`, `.gradle`, `.idea`, and `build`); all Android test/build commands below ran there. This local copy is an execution mirror, not a committed Android fork.

The four implementation files were SHA-256 identical between the reference and execution mirror before characterization and after the full baseline:

| Reference file under `app/src/main/java/com/filamentvision/` | SHA-256 |
|---|---|
| `vision/processing/VisionProcessor.kt` | `0A2E606C4B458916326CA9D6337CC5CF23E9A0B2B0F21D56A101B19E43923DFE` |
| `vision/fusion/FusionEngine.kt` | `9757E11D1DCE5178E8B9BC213364FFE5E66FA4C74632BBFBAC7554DA8E51C5AC` |
| `domain/alarm/AlarmEvaluator.kt` | `BBC3CBC1CF107FB52783BCDCE25F80CF7D2F4038D8231770E03D1CDECADCD3A8` |
| `domain/session/SessionStatisticsAccumulator.kt` | `2275C2797D2B50A156BBE81AE5225919C7B75EDD5C6F38DF70C360406726BC71` |

`git -c safe.directory='E:/Study/Monash/Y2S2/ENG5105/interface' -C 'E:\Study\Monash\Y2S2\ENG5105\interface' status --short` showed the same two pre-existing untracked documentation paths both before and after: `docs/superpowers/plans/2026-10-01-phase2-backend-foundation.md` and `docs/web-migration/`. No tracked Android source/config changes appeared. The local execution mirror and Gradle caches are ignored build artifacts inside this repository.

## Exact verification commands and results

For the selected existing Android baseline, from `.gradle/android-baseline`:

```powershell
$env:GRADLE_USER_HOME='C:\Users\asus\Documents\ChatGPT\5105interface\webiste2.0\.gradle'
$env:JAVA_HOME='E:\Study_Software\Android\.gradle\jdks\eclipse_adoptium-21-amd64-windows.2'
& 'E:\Study_Software\Android\.gradle\wrapper\dists\gradle-9.4.1-bin\arn2x92ynaizyzdaamcbpbhtj\gradle-9.4.1\bin\gradle.bat' :app:testDebugUnitTest --tests "com.filamentvision.vision.processing.VisionProcessorTest" --tests "com.filamentvision.vision.fusion.FusionEngineTest" --tests "com.filamentvision.domain.alarm.AlarmEvaluatorTest" --tests "com.filamentvision.domain.session.SessionStatisticsAccumulatorTest" --offline
```

Result: `BUILD SUCCESSFUL`; 12 tests across 4 classes (Vision 4, Fusion 4, Alarm 2, Statistics 2). The first run used cached Android dependencies copied from `E:\Study_Software\Android\.gradle\caches\modules-2` into the ignored local Gradle home.

For RED, from the standalone repository root, the uncommitted test temporarily asserted `0.41` mm while the independently specified fixture expected `0.4` mm:

```powershell
$env:GRADLE_USER_HOME='C:\Users\asus\Documents\ChatGPT\5105interface\webiste2.0\.gradle'
$env:JAVA_HOME='E:\Study_Software\Android\.gradle\jdks\eclipse_adoptium-21-amd64-windows.2'
.\gradlew.bat :reference-characterization:test --tests "com.filamentvision.characterization.VisionGoldenCharacterizationTest" --offline
```

The JUnit XML recorded `tests="1" failures="1" errors="0"` and the precise failure:

```text
java.lang.AssertionError: expected:<0.41> but was:<0.4>
at com.filamentvision.characterization.VisionGoldenCharacterizationTest.darkStripPreservesEveryPreviewStageAndMeasurementMetadata(VisionGoldenCharacterizationTest.kt:73)
```

The wrong literal was corrected to read the frozen `expectedDiameterMm=0.4` fixture value; no Android production source was edited. GREEN, from the standalone root:

```powershell
$env:GRADLE_USER_HOME='C:\Users\asus\Documents\ChatGPT\5105interface\webiste2.0\.gradle'
$env:JAVA_HOME='E:\Study_Software\Android\.gradle\jdks\eclipse_adoptium-21-amd64-windows.2'
.\gradlew.bat :reference-characterization:test --offline
```

Result: `BUILD SUCCESSFUL`; 9 tests across 3 classes, 0 failures, 0 errors (Vision 1, Fusion 5, Alarm/Statistics 3).

Local SHA-256 hashes of the three final characterization test files:

| Test file under `reference-characterization/src/test/kotlin/com/filamentvision/characterization/` | SHA-256 |
|---|---|
| `VisionGoldenCharacterizationTest.kt` | `A973C313D4692A7E61F84808C90C14247A222C61F42EABACC75B5DCD650F9AF9` |
| `FusionGoldenCharacterizationTest.kt` | `1F630A5A432EB9D691C978BB501DD4C145EC7D799509545F27830F2F65D8C2A4` |
| `AlarmStatisticsGoldenCharacterizationTest.kt` | `E048EAB1A125D6D6EF1AB471D9CD165017406D84AD66A0B8455545FBBE1BABF9` |

For the complete Android baseline and APK, from `.gradle/android-baseline`:

```powershell
$env:GRADLE_USER_HOME='C:\Users\asus\Documents\ChatGPT\5105interface\webiste2.0\.gradle\android-home'
$env:JAVA_HOME='E:\Study_Software\Android\.gradle\jdks\eclipse_adoptium-21-amd64-windows.2'
& 'E:\Study_Software\Android\.gradle\wrapper\dists\gradle-9.4.1-bin\arn2x92ynaizyzdaamcbpbhtj\gradle-9.4.1\bin\gradle.bat' :app:testDebugUnitTest :app:assembleDebug --offline
```

Result: `BUILD SUCCESSFUL`; 78 unit tests across 30 classes, 0 failures, 0 errors; `app-debug.apk` produced (12,903,821 bytes, SHA-256 `55C36192290E21284A81F26437710E7C362BEF7352516C1A9B32E3F5048245BB`). The build used a fresh local Gradle home seeded from the Android dependency cache. The Kotlin daemon could not write its sandboxed `%LOCALAPPDATA%` marker, so Gradle used its in-process fallback. Existing coroutine opt-in warnings and a native-library strip warning remained; neither failed the build.

## Frozen behavior

- Vision: `fixtures/characterization/vision-dark-strip-v1.properties` fixes an 8×4 ARGB frame with background 100, dark pixels 20 at x=2..5, manual threshold 40, edge sensitivity 30, and 0.1 mm/pixel. Each of four grayscale rows is `[100,100,20,20,20,20,100,100]`; binary is `[255,255,0,0,0,0,255,255]`; edge is `[0,255,255,0,0,255,255,0]`, all asserted as full packed ARGB arrays. Camera `A`, timestamp `99`, sequence `3`, ROI `[0,0,8,4)`, edges `2` and `6`, pixel width `4.0`, diameter `0.4` mm, confidence `1.0`, effective threshold `40`, valid measurement, and no error are fixed.
- Fusion: weights `0.25/0.75` on A=`1.7` (confidence `0.9`) and B=`1.8` (confidence `0.8`) produce `1.775`, confidence `0.85`, shape difference/raw difference `0.1`, timestamp `1050`, and `BOTH_CAMERAS`. Missing A/B and low-confidence A/B preserve distinct reasons, failure status, NaN for absent diameter, zero absent confidence, and raw diagnostic difference when both inputs exist. A/B timestamp mismatch at `1000/1500` selects the newer camera, reports `TIMESTAMP_MISMATCH`, and marks timestamps incompatible. Zero weights produce the arithmetic mean `1.75`.
- Alarm: immediately below/above 2% and 5% in both high and low directions is fixed using `2.0399/2.0401`, `2.0999/2.1001`, `1.9601/1.9599`, and `1.9001/1.8999` against target `2.0`. Transitions use measurement timestamps `125`, `375`, `625`, and `875`; repeated samples in the same level/direction emit no transition, while a critical-high to critical-low direction switch does.
- Statistics: samples `1.0/2.0/3.0` with confidences `0.7/0.8/0.9` produce count `3`, mean `2.0`, min `1.0`, max `3.0`, population standard deviation `0.816496580927726`, average confidence `0.8`, and abnormal count `2`. Empty snapshot retains its supplied abnormal count.

## Observed deviations and limits

The temporary standalone test harness invokes installed `kotlinc` 2.2.20 because the offline local Gradle cache did not contain a resolvable Kotlin JVM plugin marker; the Android baseline itself used its Gradle-declared Kotlin version 2.2.10. The harness uses only selected real Android source files and hard-coded/fixture expectations. This version difference is recorded for future equivalence work. The `E:` reference path and installed tool/cache paths are intentionally local and temporary; Task 2 must not make them a runtime dependency. No Core, Backend, simulator, API, SQLite, or Raspberry Pi code was added.
