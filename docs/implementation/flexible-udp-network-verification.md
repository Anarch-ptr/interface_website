# Flexible UDP Network Configuration Verification

Date: 2026-10-04

## Scope and invariants

This gate verifies the approved flexible IPv4 UDP network delta without changing the 2-byte envelope, the 34-byte Structured Transport Header, Vision golden values, transport modes, or the production default UDP port.

- Saved/applied configuration revision and resolution generation remain independent.
- A same-revision forced Re-Apply can publish a newly resolved immutable effective plan.
- Failed resolution, bind, persistence, or CAS leaves the old plan, generation, and receiver active.
- Candidate receivers remain provisional until the repository commit succeeds.
- DNS resolution, preview state, receivers, runtime admission, Inspector retention, and producer ownership are bounded.

## Build and test environment

- Kotlin Gradle plugin: 2.2.10
- Gradle runtime/toolchain JDK: 21
- Production bytecode/JVM target: 17
- Test framework: JUnit 4.13.2 with `useJUnit()`
- Frontend: Vitest 4.1.11, ESLint, TypeScript and Vite 8.2.2
- Existing editable UDP default port preserved: 5000

## Focused behavior evidence

- `FlexibleUdpNetworkE2ETest`: 4/4 passed using real IPv4 UDP sockets and dynamic fixture ports.
- Same-revision hostname change: preview reports `RESOLUTION_CHANGED` / `REAPPLY_REQUIRED`; forced Apply keeps the saved/applied revision unchanged, increments `resolutionGeneration`, admits the new resolved source, and rejects the old source.
- Wildcard, specific-interface, and manual bind receiver restarts passed.
- Candidate bind failure restored the prior effective receiver.
- Structured multi-chunk JPEG reached Vision and Packet Inspector without changing transport bytes.
- V005 migration coverage passed: legacy schema versions migrate to `PRAGMA user_version=5`; enabled legacy profiles require explicit Re-Apply and migration performs no DNS/interface discovery.

## Long-run boundedness evidence

The complete `LongRunBoundednessTest` passed, including an 8-hour virtual monitoring session, 8MP RGB/JPEG overlap, and flexible network stress.

Flexible network stress included 100 concurrent preview requests, repeated same-revision Re-Apply, 50 stale-generation conflicts, resolution failure, bind failure, and receiver restoration.

Observed maxima:

| Metric | Maximum |
| --- | ---: |
| Resolver active lookups | 1 |
| Resolver queued lookups | 0 |
| Preview active operations per device | 1 |
| Preview queued operations per device | 1 |
| Retained preview results | 1 |
| Committed receivers | 1 |
| Provisional receivers | 1 |
| Active producers | 1 |
| Runtime mailbox entries | 0 |
| Packet Inspector retained preview bytes | 512-byte hard bound |

The virtual 8-hour run processed and persisted 230,400 measurements. Its realtime buffer remained at 300 samples, replay cache at 256, alarm list at 128, command caches at 128/128, and `maximumActiveProducerCount` at 1.

The 8MP tests retained less than the conservative 203,009,159-byte array-payload ceiling for both RGB and JPEG paths, then released receivers, decoder permits, incomplete reassembly, previews, and Inspector bytes on stop/restart.

## Final regression gate

Commands executed from the independent project root:

```text
gradlew.bat clean :core:test :backend:test verifyStandaloneIndependence --no-daemon
npm.cmd test -- --run
npm.cmd run lint
npm.cmd run build
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File .\scripts\Launcher.Tests.ps1
```

Results:

- Gradle gate: PASS in 3m 9s.
- Standalone independence: PASS, 134 production/configuration files scanned.
- Backend: 464 tests passed, 0 failed, 0 skipped.
- Core: 155 tests passed, 0 failed, 0 skipped.
- Combined Gradle: 619 tests passed, 0 failed, 0 skipped.
- Frontend: 15 files / 165 tests passed, 0 failed, 0 skipped.
- Frontend lint: PASS.
- Frontend production build: PASS; 36 modules transformed.
- Windows launcher contract: PASS.
- Production hard-coded address scan: PASS for Backend, Core, Frontend production files, and scripts. Test fixtures are excluded explicitly from the production-only scan.
- `git diff --check`: PASS.

## Regression cleanup discovered by the gate

The full gate found two obsolete test-contract assumptions, not production behavior defects:

1. `NetworkInterfacesApiTest` still expected pre-delta applied/advertised fields from `/network-interfaces`, although the approved contract makes that endpoint inventory-only. The regression now verifies inventory-only behavior and independence from saved configuration.
2. The production network constant scanner included co-located Frontend `*.test.*` fixtures under `frontend/src`. It now excludes test files while continuing to scan all production TypeScript/TSX sources.

The TypeScript production build also identified one test fixture using the old flat `profile.listenPort`; it was corrected to `profile.network.listenPort`. No production business logic changed for these gate repairs.

## Final compatibility statement

The project remains independently buildable and runnable from `webiste2.0`. No Android project dependency, design amendment after the approved docs correction, wire-protocol change, Vision golden-value change, IPv6 support, NAT traversal, unbounded resolver state, or new transport mode was introduced.
