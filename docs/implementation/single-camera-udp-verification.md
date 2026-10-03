# Single-camera UDP verification

Task 24, base `27da1b18a875a6f190c8b6a3e9df05092c76b129`, 2026-10-04.

## Scope and reproducibility

This gate uses the actual Backend composition, IPv4 UDP receiver, envelope and structured parser, reassembler, decoders, ROI Vision, neutral measurement assembler, SQLite, reliable events, Preview and Inspector. Only four E2E fixture/test files and one existing Inspector API test change. No production code, build configuration, dependency, approved design/plan, golden value, 34-byte wire code, or migration (including V004) changes.

Java is the existing `.gradle-user-home/jdks/microsoft-jdk-21/jdk-21.0.12.1+1`. The existing external Gradle cache remains in use. All packet tests use an explicitly IPv4 loopback sender and one listening endpoint; no Raspberry Pi hardware or LAN throughput claim is made.

```powershell
$env:JAVA_HOME = (Resolve-Path '.gradle-user-home/jdks/microsoft-jdk-21/jdk-21.0.12.1+1').Path
./gradlew.bat :backend:test --tests "com.filamentvision.backend.e2e.UdpInputEndToEndTest" --tests "com.filamentvision.backend.e2e.LongRunBoundednessTest"
./gradlew.bat clean :core:test :backend:test verifyStandaloneIndependence
Push-Location frontend
npm test
npm run lint
npm run build
Pop-Location
git diff --check
git status --short
```

The supplemental focused command adds `--tests "com.filamentvision.backend.e2e.ReviewRegressionTest"`. Test counts come from JUnit XML and Vitest, not Gradle task statuses. Kotlin plugin configuration checks marked SKIPPED and Java source sets marked NO-SOURCE are not skipped tests.

## Functional RED and minimal GREEN

The six-format real-loopback test was added before sender glue changes. After correcting a test-only `IntProgression`/`List` argument mismatch, the first functional RED executed nine tests: eight passed, one failed with `Production sender must include the exact two-byte header expected:<0> but was:<22>`. The JPEG frame's 22 datagrams had no production envelope.

The expanded RED executed 13 tests: 11 passed and two failed. The same format case failed, and the 3840×2160 RGB first-chunk case observed one invalid header instead of zero. No protocol or production defect was needed to explain these failures.

`UdpTestSender` now sends fixed compatibility stream byte 1 inside `[AA 01][unchanged 34-byte header][chunk][0D 0A]`. It binds a single IPv4 loopback socket and slices one chunk per send rather than constructing boxed lists/copies of an entire 8MP payload. Reverse/subset ordering remains test-selectable. Invalid-envelope fixtures independently construct their literal wrong boundary bytes.

The initial GREEN including review regressions passed 22 tests (10 UDP E2E, five boundedness, seven review regressions), zero failures/errors/skips. Additional test-only assertions exercise simultaneous Inspector lock contention, reliable failure finalization and explicit release checks.

The first clean full gate exposed two test synchronization timeouts: the new 8MP fixture and an existing Inspector API test polled Inspector under its read lock while awaiting a single optional `tryLock` capture. That polling could itself cause the capture to be dropped, as the approved non-blocking observer contract allows. The 8MP fixture now awaits reliable processing diagnostics; the API fixture awaits its existing volatile copy counter before polling. The new source-filter assertions use the same non-locking readiness observation. No deadline, production behavior or assertion was weakened. The repaired cases plus all Inspector unit regressions passed 18 tests, zero failures/errors/skips.

A subsequent clean run passed all Task 24 tests but had one intermittent failure in the unchanged diagnostic client's one-second WebSocket observation assertion (no observed `measurement.sample`). That run overlapped the frontend test process. The diagnostic test passed unchanged in isolation and in the final clean full gate run alone; concurrent load is a plausible contributor, not a proven root cause. No diagnostic assertion or timeout was edited.

## Loopback matrix

| Case | Observable evidence |
|---|---|
| JPEG, GRAY8, BINARY BYTE_PER_PIXEL and BIT_PACKED, RGB888 RGB and BGR | Each traverses reversed multi-chunk packets, real decoding and Vision; literal 1.75 mm, one `camera` evidence, one Preview key, valid Inspector result, six durable rows. Format Apply retains one producer start. |
| Exact envelope / unchanged transport | Independently wrong header/footer increment only their respective counters; no transport parsing on envelope rejection. Frozen transport fixture is exercised unchanged by the full suite. |
| Source mismatch | Rejected one-byte packet is visible as SOURCE_IP_MISMATCH only in Inspector; no transport/frame metadata, preview or measurement. |
| Sentinel / truncation | Logical accepted size plus one and a 65,000-byte datagram are rejected before Vision, with separate counter increments and Inspector status. |
| Duplicate / conflict / missing / timeout / late | Identical duplicate remains pending; conflicting duplicate discards the frame; one missing chunk expires and increments missing/timeout; late completion is rejected; a subsequent out-of-order complete frame recovers. |
| Save / Apply | Save leaves the effective socket unchanged; envelope/format parser changes work; port restart frees the old port; occupied-port failure restores the old live listener. A real competing SQLite Save causes final Apply CAS failure and restores the old parser/profile/applied revision; old-format loopback input still measures. |
| Observer congestion | A blocked Preview consumer and a concurrently held Inspector read lock coexist while accepted samples, alarms and finalization remain durable and reliably sequenced. The test injects a decoder exception after three valid frames to exercise reliable error finalization on the same real socket path. |
| Silence / ownership | Twelve concurrent starts share one session; after the real grace period status is WAITING_FOR_RASPBERRY_PI with zero samples/preview. Producer and receiver maxima are one. Sixteen Apply/start/stop cycles retain bounded lifecycle state. |
| Cancellation / failure | Existing preview-handoff cancellation/failure tests retain permit-release checks. A new real-UDP gated Vision test stops before admission, proves no stale-generation measurement, then restarts and decodes successfully. |

The competing-repository fixture intercepts only the decision timing and delegates to actual SQLite. The failure fixture substitutes only a deliberate fourth decoder exception; the first three frames use the real decoder. The gated Vision fixture delegates all processing to the actual ROI analyzer. No fake production source or alternate composition is added.

## 8MP overlap accounting

The deterministic image is 3840×2160 (8,294,400 pixels), with a 120-pixel dark strip and calibration ROI `(1000,700)-(1640,1180)`. Both real RGB888 and ImageIO JPEG payloads cross the IPv4 socket, decode and produce the literal 1.2 mm Vision diameter.

At a gate after real ROI processing, the first frame still owns the decoded permit, the actual Preview has been published, and two further incomplete frame identities retain 120,000 chunk bytes. The test deliberately keeps the real decoder's encoded input and scratch ARGB array reachable until this gate. This extends their production lifetimes conservatively; it does not replace decoding or claim those references normally survive handoff. The gate retains the real four returned ROI arrays. A real PNG encoding of the published preview also completes while the gate is held.

| Unique array payload at the overlap gate | RGB888 | JPEG |
|---|---:|---:|
| Incomplete reassembly chunks | 120,000 | 120,000 |
| Current encoded frame (stress-retained) | 24,883,200 | 138,327 |
| Decoder ARGB plus adapter-owned ARGB | 66,355,200 | 66,355,200 |
| Four 640×480 ROI IntArrays | 4,915,200 | 4,915,200 |
| Retained 1280×720 ARGB Preview | 3,686,400 | 3,686,400 |
| Encoded PNG | 9,471 | 6,949 |
| Latest Inspector raw preview | 256 | 256 |
| Additional mailbox image bytes | 0 | 0 |
| **Simultaneous accounted payload** | **99,969,727** | **75,222,332** |

The mailbox is empty while Runtime owns the image: ownership transfers rather than copying a second mailbox image. A separate real-socket test leaves the collector absent, observes exactly one pending image, sends another completed frame, and verifies a pre-decode backpressure drop without increasing decoded/pending frame count. Stop closes the pending lease; restart accepts another frame. Thus mailbox capacity one and decoded permit one are exercised, without double-counting an aliased image in the overlap sum.

The conservative large-array payload ceiling for this calibrated workload with one PNG consumer is **203,009,159 bytes**:

```text
67,108,864  reassembly hard cap
33,554,432  current encoded-frame hard cap
66,355,200  two 8MP ARGB arrays during decoder/adapter ownership transfer
 4,915,200  four actual ROI stage arrays
18,432,000  five bounded Preview arrays (sampling, retained/in-flight, encoder copy/raster)
12,582,912  PNG output plus conservative output-buffer growth allowance
    60,039  physical receiver buffer including sentinel
       512 Inspector aggregate preview cap
-----------
203,009,159
```

This is deterministic array-payload accounting, **not a JVM heap/RSS measurement or universal throughput guarantee**. Sender fixtures, object/array headers, coroutine/collection objects, native ImageIO/JVM state, SQLite buffers and OS UDP queues are outside the byte sum. Detector temporary objects are bounded by the 480 ROI rows and at most 320 candidate runs per row; the fixture has one run per row. The default manual threshold has no Otsu histogram. JPEG raster work occurs inside metadata-validated decoding before adapter/Vision; its 8MP three-channel raster plus ARGB output is 58,060,800 bytes, below the conservative two-ARGB allowance above. Preview consumers are separately capped at 32 by the existing Hub; the measured overlap uses one encoder, not 32 simultaneous encoders.

The raw Preview remains 3,686,400 bytes and every encoded fixture is below 4 MiB; neither the Hub cap nor decoder/reassembly limits change. Existing sampler tests independently instrument the 3,686,400-byte work allocation and preserve direct sampling without a full-resolution copy. Existing Preview backpressure tests exercise slow real WebSockets and reliable event independence.

An additional exact-edge reassembler test uses legal ≤60,000-byte chunk payloads, two 32 MiB frame declarations with one missing byte each, and observes **67,108,862 bytes**, two bytes below 64 MiB. A third identity evicts the oldest before retention can grow; expiry returns bytes/count to zero. Decoded allocation accepts the 8MP image and the exact 64 MiB ARGB edge, rejecting one row above and overflowing dimensions before allocation.

After composition stop, the source processor reference is null, no mailbox/receiver remains, and reacquiring the shared decoded permit succeeds. Restart has zero reassembly bytes/identities. Lease release clears Inspector bytes. Preview keeps at most the documented single latest frame during stop; composition close clears it. Test-only extended references are explicitly cleared.

## Final gate and protected boundaries

Final required focused gate: **15 tests passed** (10 UDP E2E and five boundedness), zero failures/errors/skips, exit 0 in 2m21s.

Final clean Kotlin/standalone gate: **Core 155 tests across 23 suites; Backend 412 tests across 57 suites**, zero failures/errors/skips. Exit 0, `BUILD SUCCESSFUL in 3m 21s`, all 14 Gradle tasks executed. `STANDALONE_INDEPENDENCE PASS scanned=126 modules=core,backend`.

Frontend: 14 files / 159 tests passed, zero skipped; lint passed; TypeScript/Vite production build passed (36 modules).

The eight-hour virtual 8 Hz regression remains unchanged and persists all 230,400 accepted samples. It verifies bounded write/alarm queues, realtime history, reliable replay, Preview, snapshot cache and command caches. Observed queue maxima are 128 writes / 128 alarms; retained realtime=300, replay=256, Preview=1, command caches=128/128, database batches=1,800. This is virtual elapsed time, not an eight-hour wall-clock or hardware soak. The retained test-only 30-minute sustained verifier is compiled by the full gate but is not claimed run here.

Source scans find no Simulator/Fake/Mock, scenario option or sustained-verifier production dependency in Backend, and no legacy dual/fused/simulation vocabulary or test-fixture import in frontend production. The frontend AST import-boundary test remains part of the full gate. Generic Fusion/Pairing/direct-diameter algorithms, generic application framing/single-datagram reassembly and simulator fixtures remain only as approved reusable algorithms or test utilities; production composition instantiates none of the obsolete input paths.

No skip markers are added. Full protected-path diff against the base is empty for production sources, Core, frontend, transport, migrations and approved design/plan. V004 remains Git blob `6e15a5f9f6257b38755cda4bcc239948c70bbe3f`. No hard limit, wire byte, production feature or V004 change is required. `git diff --check` passes. The final commit contains only the five test/support files and this report.
