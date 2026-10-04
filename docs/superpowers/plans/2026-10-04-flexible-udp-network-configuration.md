# FilamentVision Flexible UDP Network Configuration Delta Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Backend bind, advertised destination, Raspberry Pi source filtering and UDP listen port independently configurable with IPv4/hostname resolution, immutable applied plans, read-only previews and transactional receiver rollback.

**Architecture:** Replace the coupled interface/source fields with `UdpNetworkProfile`, resolve configuration-plane hostnames through one injected `HostResolver`, and commit an immutable `EffectiveUdpNetworkPlan` with the applied revision. A prepared receiver remains invisible until commit; preview, diagnostics and Settings distinguish current DNS from applied receiver facts.

**Tech Stack:** Kotlin/JVM 21, coroutines, Ktor, SQLite JDBC, JUnit 5, React 19, TypeScript, Vitest, Testing Library, Vite.

**Spec:** `docs/superpowers/specs/2026-10-04-flexible-udp-network-configuration-design.md`

## Global Constraints

- Work only under `E:\Study\Monash\Y2S2\ENG5105\webiste2.0`; the Android repository is not a dependency.
- Do not change the exact 2-byte envelope, 34-byte Structured Transport Header, network byte order, decoder/Vision behavior, measurement/alarm/session semantics or golden expected values.
- Core remains independent of Android, Ktor, DNS, sockets, SQLite, Room, Compose and desktop image APIs.
- IPv4 is the only production UDP family; IPv6 is rejected before Apply.
- Bind address, advertised destination and source filtering are independent.
- Save performs syntax persistence only; Resolve produces preview only; Apply alone changes the running effective plan.
- Candidate receiver data is invisible before repository CAS and runtime publication.
- Runtime profile, effective receiver, persisted applied revision and persisted effective plan must remain consistent.
- `activeProducerCount <= 1`, committed receiver jobs <= 1, provisional receivers <= 1, mailbox <= 1 and existing Inspector bounds remain authoritative.
- No production address, hostname or port is derived from test examples.
- Every production task (Tasks 2-20) follows RED -> verify expected failure -> minimal GREEN -> focused tests -> listed regressions -> `git diff --check` -> `git diff --stat` -> independent commit. Task 1 is the characterization baseline and must pass before production work.
- Do not advance after a focused or regression failure, a wire-format change, a golden mismatch, an unbounded ownership path, or an atomicity invariant failure.

## File and boundary map

New Backend files:

- `backend/src/main/kotlin/com/filamentvision/backend/input/profile/UdpNetworkProfile.kt`: bind/destination/source intent.
- `backend/src/main/kotlin/com/filamentvision/backend/input/network/HostResolver.kt`: bounded IPv4-only resolution boundary.
- `backend/src/main/kotlin/com/filamentvision/backend/input/network/EffectiveUdpNetworkPlanner.kt`: interface, route and AUTO policy.
- `backend/src/main/kotlin/com/filamentvision/backend/input/network/EffectiveUdpNetworkPlan.kt`: immutable applied result and preview models.
- `backend/src/main/kotlin/com/filamentvision/backend/input/network/NetworkResolutionPreviewService.kt`: read-only latest preview.
- `backend/src/main/kotlin/com/filamentvision/backend/input/runtime/PreparedUdpReceiver.kt`: invisible bind-only candidate lifecycle.
- `backend/src/main/kotlin/com/filamentvision/backend/persistence/sqlite/EffectiveUdpNetworkPlanCodec.kt`: bounded plan persistence.
- `backend/src/main/resources/db/migration/V005__effective_udp_network_plan.sql`: atomic applied-plan storage.
- focused tests matching each file plus flexible-network REST/E2E tests.

Existing Backend files modified:

- profile, validator and codec replace old coupled fields while decoding v1;
- receiver/source/router/configuration service consume compiled applied plans;
- repository CAS persists revision and plan together;
- application facade/composition expose preview and applied facts;
- DTOs/routes/diagnostics/Inspector expose the stable contract.

Frontend files modified:

- domain/profile validator/API client adopt nested network DTOs and preview;
- Settings renders simple and Advanced Network sections;
- diagnostics/Inspector label applied facts and preview differences.

## Task execution record

For each task record: task number, commit SHA, changed files, RED reason, focused result, regression result, `git diff --check`, `git diff --stat`, and final `git status -sb`.

### Task 1: Freeze current UDP network behavior and hard-coded-address boundary

**Files:**
- Create: `backend/src/test/kotlin/com/filamentvision/backend/input/network/FlexibleUdpNetworkCharacterizationTest.kt`
- Create: `backend/src/test/kotlin/com/filamentvision/backend/architecture/ProductionNetworkConstantTest.kt`
- Test: `backend/src/test/kotlin/com/filamentvision/backend/input/profile/RaspberryPiUdpInputProfileValidatorTest.kt`
- Test: `backend/src/test/kotlin/com/filamentvision/backend/input/runtime/RaspberryPiUdpInputSourceTest.kt`
- Test: `backend/src/test/kotlin/com/filamentvision/backend/input/runtime/RaspberryPiUdpInputConfigurationServiceTest.kt`

**Interfaces:**
- Consumes current v1 profile, resolver, source and Apply behavior.
- Produces an unchanged-behavior baseline and a source scan that rejects production literals matching private-address examples outside explicit defaults/tests/docs.

- [ ] Write characterization tests for AUTO wildcard bind, SPECIFIC exact address ownership, null source meaning ANY, source mismatch before parsing, Save/Apply CAS and rollback.
- [ ] Add a production-source scan over `backend/src/main`, `frontend/src` and launcher scripts that rejects the two example addresses and any fixture-only hostname; allow `0.0.0.0`, loopback, and the editable default port only where semantically required.
- [ ] Run `./gradlew.bat :backend:test --tests "com.filamentvision.backend.input.network.FlexibleUdpNetworkCharacterizationTest" --tests "com.filamentvision.backend.architecture.ProductionNetworkConstantTest"`; expect PASS because this task freezes existing behavior rather than adding production behavior.
- [ ] Run `./gradlew.bat :backend:test --tests "com.filamentvision.backend.input.runtime.RaspberryPiUdpInputSourceTest" --tests "com.filamentvision.backend.input.runtime.RaspberryPiUdpInputConfigurationServiceTest"`.
- [ ] Inspect diff, commit tests only with `test: characterize UDP network configuration`, and keep the rollback boundary limited to the two new tests.

### Task 2: Define network intent types and syntactic validation

**Files:**
- Create: `backend/src/main/kotlin/com/filamentvision/backend/input/profile/UdpNetworkProfile.kt`
- Create: `backend/src/test/kotlin/com/filamentvision/backend/input/profile/UdpNetworkProfileValidatorTest.kt`
- Modify: `backend/src/main/kotlin/com/filamentvision/backend/input/profile/RaspberryPiUdpInputProfile.kt`
- Modify: `backend/src/main/kotlin/com/filamentvision/backend/input/profile/RaspberryPiUdpInputProfileValidator.kt`
- Modify: `backend/src/test/kotlin/com/filamentvision/backend/input/profile/RaspberryPiUdpInputProfileValidatorTest.kt`

**Interfaces:**
- Produces `UdpNetworkProfile`, `BindProfile`, `AdvertisedDestinationProfile`, `SourceFilterProfile`, `normalizeHostOrIpv4(String): String`, and field-path validation errors.
- Default profile is disabled/AUTO/AUTO/ANY with editable port 5000.

- [ ] Write failing tests for all four bind modes, both destination modes, both source modes, canonical IPv4, hostname/single-label/`.local` normalization, invalid scheme/port/space/label/length, IPv6 rejection and mode-specific required fields.
- [ ] Run `./gradlew.bat :backend:test --tests "com.filamentvision.backend.input.profile.UdpNetworkProfileValidatorTest"`; expect compile failure because the types do not exist.
- [ ] Implement the immutable types and syntactic validator only; do not resolve DNS or enumerate interfaces.
- [ ] Run the focused command, then `./gradlew.bat :backend:test --tests "com.filamentvision.backend.input.profile.RaspberryPiUdpInputProfileValidatorTest" --tests "com.filamentvision.backend.input.decoder.DecodedAllocationPolicyTest"`.
- [ ] Inspect diff and commit `feat: define flexible UDP network profile`. Rollback removes only the new model and restores the old profile fields.

### Task 3: Implement bounded IPv4 HostResolver

**Files:**
- Create: `backend/src/main/kotlin/com/filamentvision/backend/input/network/HostResolver.kt`
- Create: `backend/src/test/kotlin/com/filamentvision/backend/input/network/HostResolverTest.kt`

**Interfaces:**
- Produces `suspend fun resolveIpv4(hostOrIpv4: String): HostResolution` and injectable `HostLookup`/clock/executor seams with 3,000 ms timeout, two worker threads and a two-entry rejecting queue.
- `HostResolution.ipv4s` is an immutable, numerically sorted set capped at 16; the effective plan stores the source result as `resolvedSourceIpv4s`.

- [ ] Write failing tests for literal DNS bypass, hostname success, IPv4-only filtering, normalize/dedupe/stable sort, zero result, timeout, lookup exception, 17-result rejection, repeated lookup returning a changed set, two-worker/two-queued saturation rejection, and no retained request history.
- [ ] Run `./gradlew.bat :backend:test --tests "com.filamentvision.backend.input.network.HostResolverTest"`; expect compile failure for missing resolver types.
- [ ] Implement the bounded resolver on a dedicated bounded IO dispatcher. Map timeout/lookup failure to typed results without throwing platform exceptions across the boundary.
- [ ] Run the focused command, then `./gradlew.bat :backend:test --tests "com.filamentvision.backend.input.profile.UdpNetworkProfileValidatorTest" --tests "com.filamentvision.backend.input.network.Ipv4NetworkInterfaceProviderTest"`.
- [ ] Inspect diff and commit `feat: resolve configurable UDP hostnames`. Rollback is isolated because no runtime consumes the resolver yet.

### Task 4: Build deterministic interface and AUTO destination planner

**Files:**
- Create: `backend/src/main/kotlin/com/filamentvision/backend/input/network/EffectiveUdpNetworkPlanner.kt`
- Create: `backend/src/test/kotlin/com/filamentvision/backend/input/network/EffectiveUdpNetworkPlannerTest.kt`
- Modify: `backend/src/main/kotlin/com/filamentvision/backend/input/network/Ipv4NetworkInterfaceProvider.kt`
- Modify: `backend/src/main/kotlin/com/filamentvision/backend/input/network/BackendIpv4Resolver.kt` (reduce to route-probe adapter or remove after usages migrate)

**Interfaces:**
- Consumes intent, HostResolution, immutable interface inventory and `Ipv4RouteProbe`.
- Produces typed `NetworkPlanCandidate` or exact error code, including `DESTINATION_AMBIGUOUS` when route/interface candidates do not converge; no socket or persistence action.

- [ ] Write failing tests for AUTO/ALL wildcard, SPECIFIC stable ID success/not-found/down/address-changed, MANUAL local/not-local, Wi-Fi+Ethernet+VPN inventory, route convergence for multiple source A records, route disagreement, zero/one/multiple AUTO candidates, and different-subnet warning without failure.
- [ ] Run `./gradlew.bat :backend:test --tests "com.filamentvision.backend.input.network.EffectiveUdpNetworkPlannerTest"`; expect compile failure for the missing planner.
- [ ] Implement the deterministic rules from the spec; delete the old first-candidate/index ordering behavior only after its new tests pass.
- [ ] Run the focused command, then `./gradlew.bat :backend:test --tests "com.filamentvision.backend.input.network.Ipv4NetworkInterfaceProviderTest" --tests "com.filamentvision.backend.input.network.FlexibleUdpNetworkCharacterizationTest"`.
- [ ] Inspect diff and commit `feat: plan UDP bind and destination independently`. Rollback returns to the unused resolver without touching receiver code.

### Task 5: Define immutable effective plans and read-only preview comparison

**Files:**
- Create: `backend/src/main/kotlin/com/filamentvision/backend/input/network/EffectiveUdpNetworkPlan.kt`
- Create: `backend/src/main/kotlin/com/filamentvision/backend/input/network/NetworkResolutionPreviewService.kt`
- Create: `backend/src/test/kotlin/com/filamentvision/backend/input/network/NetworkResolutionPreviewServiceTest.kt`

**Interfaces:**
- Produces immutable `EffectiveUdpNetworkPlan`, `NetworkResolutionPreview`, `NetworkConsistencyStatus`, timestamps/generation and `preview(profile, appliedPlan)`.
- Preview capacity is one active plus one conflated queued request per device, starts no more often than every 250 ms, and stores one latest result.

- [ ] Write failing tests for destination/source timestamp provenance, disabled INACTIVE plan, unique/manual/ambiguous preview, applied equality, `RESOLUTION_CHANGED`, `REAPPLY_REQUIRED`, cancellation, latest-response-wins, one queued refresh and zero runtime mutations through fake observers.
- [ ] Run `./gradlew.bat :backend:test --tests "com.filamentvision.backend.input.network.NetworkResolutionPreviewServiceTest"`; expect compile failure.
- [ ] Implement defensive immutable copies, comparison and bounded preview orchestration; do not reference receiver/source.
- [ ] Run focused tests, then `./gradlew.bat :backend:test --tests "com.filamentvision.backend.input.network.HostResolverTest" --tests "com.filamentvision.backend.input.network.EffectiveUdpNetworkPlannerTest"`.
- [ ] Inspect diff and commit `feat: model applied UDP plans and resolution previews`. Rollback removes an unconsumed application service.

### Task 6: Version profile codec and preserve v1 intent

**Files:**
- Modify: `backend/src/main/kotlin/com/filamentvision/backend/persistence/sqlite/RaspberryPiUdpInputProfileCodec.kt`
- Modify: `backend/src/test/kotlin/com/filamentvision/backend/persistence/sqlite/RaspberryPiUdpInputProfileCodecTest.kt`

**Interfaces:**
- Produces profile codec version 2 while decoding exact v1 bytes into the mapping specified by the design.

- [ ] Add literal v1 byte fixtures before changing production code and failing v2 round-trip tests for every network mode, hostname length bounds and trailing/corrupt data.
- [ ] Run `./gradlew.bat :backend:test --tests "com.filamentvision.backend.persistence.sqlite.RaspberryPiUdpInputProfileCodecTest"`; expect new v2 assertions to fail while literal v1 fixtures decode only under old shape.
- [ ] Implement version dispatch, v1 mapping and bounded v2 encoding. Do not mutate v1 fixture bytes or expected meanings.
- [ ] Run focused tests, then `./gradlew.bat :backend:test --tests "com.filamentvision.backend.input.profile.UdpNetworkProfileValidatorTest" --tests "com.filamentvision.backend.persistence.sqlite.UdpInputProfileCodecTest"`.
- [ ] Inspect diff and commit `feat: persist flexible UDP network intent`. Rollback restores codec v1; no schema change exists yet.

### Task 7: Add bounded EffectiveUdpNetworkPlan codec and SQLite V005

**Files:**
- Create: `backend/src/main/kotlin/com/filamentvision/backend/persistence/sqlite/EffectiveUdpNetworkPlanCodec.kt`
- Create: `backend/src/test/kotlin/com/filamentvision/backend/persistence/sqlite/EffectiveUdpNetworkPlanCodecTest.kt`
- Create: `backend/src/main/resources/db/migration/V005__effective_udp_network_plan.sql`
- Modify: `backend/src/main/kotlin/com/filamentvision/backend/persistence/sqlite/SqliteDatabase.kt`
- Modify: `backend/src/test/kotlin/com/filamentvision/backend/persistence/sqlite/SqliteRepositoryContractTest.kt`

**Interfaces:**
- V005 adds nullable `applied_network_plan` and nonnegative `resolution_generation` to the singleton state.
- Produces versioned plan bytes with max 16 source addresses and bounded strings.

- [ ] Write failing codec tests for full provenance round trip, numeric set order, nullable INACTIVE fields, excessive set/string, corrupt/trailing/version data; add V004->V005 migration tests for disabled INACTIVE and enabled `LEGACY_PLAN_REAPPLY_REQUIRED` without DNS calls.
- [ ] Run `./gradlew.bat :backend:test --tests "com.filamentvision.backend.persistence.sqlite.EffectiveUdpNetworkPlanCodecTest" --tests "com.filamentvision.backend.persistence.sqlite.SqliteRepositoryContractTest"`; expect missing codec/migration failures.
- [ ] Implement codec and V005 transaction. Migration must not resolve or enumerate network state.
- [ ] Run focused tests, then `./gradlew.bat :backend:test --tests "com.filamentvision.backend.persistence.sqlite.RaspberryPiUdpInputProfileCodecTest"`.
- [ ] Inspect diff and commit `feat: migrate persisted UDP effective plans`. Rollback boundary is schema V005 plus codec; do not begin repository API changes in this task.

### Task 8: Atomically persist applied revision and effective plan

**Files:**
- Modify: `backend/src/main/kotlin/com/filamentvision/backend/input/profile/RaspberryPiUdpInputProfileRepository.kt`
- Modify: `backend/src/main/kotlin/com/filamentvision/backend/persistence/sqlite/SqliteRaspberryPiUdpInputProfileRepository.kt`
- Modify: `backend/src/test/kotlin/com/filamentvision/backend/persistence/sqlite/SqliteRepositoryContractTest.kt`
- Modify: repository fakes in `backend/src/test/kotlin/com/filamentvision/backend/input/runtime/`

**Interfaces:**
- Replace `markApplied(expectedAppliedRevision, revision)` with `markApplied(expectedAppliedRevision, revision, plan): Boolean`.
- State returns saved profile, applied profile, applied plan/legacy status from one snapshot.

- [ ] Write failing tests for revision+plan same-transaction update, stale applied CAS, concurrent Save N+1 versus Apply N, persistence exception, reopen equality, and proof that neither column advances alone.
- [ ] Run `./gradlew.bat :backend:test --tests "com.filamentvision.backend.persistence.sqlite.SqliteRepositoryContractTest"`; expect compile/signature failures.
- [ ] Implement repository interface and SQLite transaction, then update test fakes mechanically without adding runtime behavior.
- [ ] Run focused tests, then `./gradlew.bat :backend:test --tests "com.filamentvision.backend.input.runtime.RaspberryPiUdpInputConfigurationServiceTest"`.
- [ ] Inspect diff and commit `feat: commit UDP revision and plan atomically`. Rollback boundary is repository contract plus adapters.

### Task 9: Compile source-filter sets into the receiver hot path

**Files:**
- Modify: `backend/src/main/kotlin/com/filamentvision/backend/input/transport/UdpReceiver.kt`
- Modify: `backend/src/test/kotlin/com/filamentvision/backend/input/transport/UdpReceiverTest.kt`
- Modify: `backend/src/main/kotlin/com/filamentvision/backend/input/runtime/RaspberryPiUdpInputSource.kt`
- Modify: `backend/src/test/kotlin/com/filamentvision/backend/input/runtime/RaspberryPiUdpInputSourceTest.kt`

**Interfaces:**
- Receiver consumes `Set<Inet4Address>?`; null means ANY, non-null accepts any set member.
- Source compiles only an already-applied plan; it never resolves a hostname.

- [ ] Write failing tests for ANY, one/multiple accepted source addresses, mismatch before parser, Inspector mismatch observation, set defensive copy and no DNS/string allocation in packet processing.
- [ ] Run `./gradlew.bat :backend:test --tests "com.filamentvision.backend.input.transport.UdpReceiverTest" --tests "com.filamentvision.backend.input.runtime.RaspberryPiUdpInputSourceTest"`; expect profile/signature failures.
- [ ] Implement compiled set filtering while preserving sentinel capacity, owned-byte boundaries, counters and Inspector lease behavior.
- [ ] Run focused tests, then `./gradlew.bat :backend:test --tests "com.filamentvision.backend.input.transport.UdpReceiveCapacityTest" --tests "com.filamentvision.backend.input.inspector.PacketInspectorTest"`.
- [ ] Inspect diff and commit `feat: filter UDP sources by applied address set`. Rollback restores the single-IP adapter without changing framing.

### Task 10: Prepare an invisible bind-only candidate receiver

**Files:**
- Create: `backend/src/main/kotlin/com/filamentvision/backend/input/runtime/PreparedUdpReceiver.kt`
- Create: `backend/src/test/kotlin/com/filamentvision/backend/input/runtime/PreparedUdpReceiverTest.kt`
- Modify: `backend/src/main/kotlin/com/filamentvision/backend/input/runtime/RaspberryPiUdpInputSource.kt`

**Interfaces:**
- Produces `prepare(plan, profile)`, `commit()`, and `close()`; prepare binds but does not publish/start production receive.
- Maximum one prepared receiver; all resources close on cancellation/failure.

- [ ] Write failing tests that a prepared socket binds but emits no input, counters, Inspector snapshot, activity or state change; commit starts exactly one loop; close discards state; bind failure leaves old source untouched; repeated prepare is rejected/bounded.
- [ ] Run `./gradlew.bat :backend:test --tests "com.filamentvision.backend.input.runtime.PreparedUdpReceiverTest"`; expect missing type failure.
- [ ] Extract socket construction from source into the prepared lifecycle without changing active receiver semantics.
- [ ] Run focused tests, then `./gradlew.bat :backend:test --tests "com.filamentvision.backend.input.runtime.RaspberryPiUdpInputSourceTest" --tests "com.filamentvision.backend.input.transport.UdpReceiverTest"`.
- [ ] Inspect diff and commit `refactor: stage UDP receivers before publication`. Rollback is safe because configuration service still uses the old reconfigure entry point.

### Task 11: Apply resolved plans with idempotent runtime publication

**Files:**
- Modify: `backend/src/main/kotlin/com/filamentvision/backend/input/runtime/RaspberryPiUdpInputRouter.kt`
- Modify: `backend/src/main/kotlin/com/filamentvision/backend/input/runtime/RaspberryPiUdpInputConfigurationService.kt`
- Modify: `backend/src/main/kotlin/com/filamentvision/backend/input/runtime/RaspberryPiUdpInputSource.kt`
- Modify: `backend/src/test/kotlin/com/filamentvision/backend/input/runtime/RaspberryPiUdpInputConfigurationServiceTest.kt`
- Modify: `backend/src/test/kotlin/com/filamentvision/backend/input/runtime/RaspberryPiUdpInputRouterTest.kt`

**Interfaces:**
- Apply builds a plan through resolver/planner, prepares a candidate, CAS-persists revision+plan, publishes the immutable plan, then starts the committed loop.
- Repeated applied revision is a no-op and retains generation/timestamps.

- [ ] Write failing happy-path tests for enabled Apply, disabled Apply without resolution, hostname/source set, bind change, parser-only change, idempotent repeated Apply, candidate invisibility through commit, and exactly one producer/receiver.
- [ ] Run `./gradlew.bat :backend:test --tests "com.filamentvision.backend.input.runtime.RaspberryPiUdpInputConfigurationServiceTest" --tests "com.filamentvision.backend.input.runtime.RaspberryPiUdpInputRouterTest"`; expect failures because Apply has no plan.
- [ ] Implement the serialized staged commit with an injected planner/clock/generation source. Runtime publication after CAS must be a non-throwing atomic state update.
- [ ] Run focused tests, then `./gradlew.bat :backend:test --tests "com.filamentvision.backend.input.runtime.RaspberryPiUdpInputSourceTest" --tests "com.filamentvision.backend.input.runtime.ProductionUdpCompositionTest"`.
- [ ] Inspect diff and commit `feat: apply immutable UDP network plans`. Rollback returns router/configuration service to old profile-only Apply while keeping unused plan infrastructure.

### Task 12: Prove rollback compensation and fail-closed recovery state

**Files:**
- Modify: `backend/src/main/kotlin/com/filamentvision/backend/input/runtime/RaspberryPiUdpInputRouter.kt`
- Modify: `backend/src/main/kotlin/com/filamentvision/backend/input/runtime/RaspberryPiUdpInputConfigurationService.kt`
- Modify: `backend/src/main/kotlin/com/filamentvision/backend/input/runtime/RaspberryPiUdpInputSource.kt`
- Create: `backend/src/test/kotlin/com/filamentvision/backend/input/runtime/FlexibleNetworkApplyRollbackTest.kt`

**Interfaces:**
- Produces `NetworkConfigurationConsistency` and fail-closed `NETWORK_CONFIGURATION_INCONSISTENT`.
- Save stays available when inconsistent; Apply fails before preparation until restart/recovery.

- [ ] Write failing tests for destination/source resolution failure, interface validation failure, candidate bind failure, Save N+1 race, markApplied false, repository exception, successful old-socket restoration, no provisional events, compensation failure, Save allowed and Apply rejected in fail-closed state.
- [ ] Run `./gradlew.bat :backend:test --tests "com.filamentvision.backend.input.runtime.FlexibleNetworkApplyRollbackTest"`; expect missing status and incorrect rollback behavior.
- [ ] Implement exact compensation ordering and safe codes. Never claim old receiver is active after restoration failure.
- [ ] Run focused tests, then `./gradlew.bat :backend:test --tests "com.filamentvision.backend.input.runtime.RaspberryPiUdpInputRouterTest" --tests "com.filamentvision.backend.input.runtime.RaspberryPiUdpInputConfigurationServiceTest"`.
- [ ] Inspect diff and commit `fix: fail closed on UDP network rollback failure`. Rollback removes only the explicit fail-closed extension, not normal Apply.

### Task 13: Bootstrap exclusively from persisted effective plans

**Files:**
- Modify: `backend/src/main/kotlin/com/filamentvision/backend/BackendComposition.kt`
- Modify: `backend/src/main/kotlin/com/filamentvision/backend/input/runtime/RaspberryPiUdpInputSource.kt`
- Modify: `backend/src/test/kotlin/com/filamentvision/backend/input/runtime/ProductionUdpCompositionTest.kt`
- Modify: `backend/src/test/kotlin/com/filamentvision/backend/persistence/sqlite/SqliteRepositoryContractTest.kt`

**Interfaces:**
- Startup accepts persisted applied plan or explicit legacy/inactive state; it never resolves DNS to alter the plan.

- [ ] Write failing tests for restart with identical persisted source/destination, changed fake DNS producing only preview difference, unavailable persisted specific bind producing diagnostics without substitution, enabled legacy plan requiring reapply, disabled INACTIVE startup and offline Backend startup.
- [ ] Run `./gradlew.bat :backend:test --tests "com.filamentvision.backend.input.runtime.ProductionUdpCompositionTest"`; expect composition to rebuild from profile instead of the persisted plan.
- [ ] Wire persisted plan into composition and receiver creation; do not invoke HostResolver on startup.
- [ ] Run focused tests, then `./gradlew.bat :backend:test --tests "com.filamentvision.backend.persistence.sqlite.SqliteRepositoryContractTest" --tests "com.filamentvision.backend.api.HealthApiTest"`.
- [ ] Inspect diff and commit `feat: restore applied UDP network plans on restart`. Rollback returns bootstrap only; persisted data remains readable.

### Task 14: Expose final REST DTOs, preview and error contract

**Files:**
- Modify: `backend/src/main/kotlin/com/filamentvision/backend/api/dto/RaspberryPiUdpInputDtos.kt`
- Modify: `backend/src/main/kotlin/com/filamentvision/backend/api/dto/RaspberryPiUdpInputDtoMappers.kt`
- Modify: `backend/src/main/kotlin/com/filamentvision/backend/api/Routes.kt`
- Modify: `backend/src/main/kotlin/com/filamentvision/backend/application/BackendApplication.kt`
- Modify: `backend/src/main/kotlin/com/filamentvision/backend/ComposedBackendApplication.kt`
- Create: `backend/src/test/kotlin/com/filamentvision/backend/api/FlexibleUdpNetworkApiTest.kt`

**Interfaces:**
- Produces nested `network.bind/destination/sourceFilter/listenPort`, applied plan/consistency/reapply fields and `POST .../udp-network-resolution-preview`.
- `/network-interfaces` becomes inventory only.

- [ ] Write failing API tests for all modes, authoritative malformed hostname/IPv6 errors, preview success/failure/multiple candidates, preview no revision/runtime/diagnostic change, Apply error-code mapping, applied plan response and old flat JSON rejection.
- [ ] Run `./gradlew.bat :backend:test --tests "com.filamentvision.backend.api.FlexibleUdpNetworkApiTest"`; expect compile/404 failures.
- [ ] Implement DTO mapping/routes and safe HTTP statuses: syntax 400, revision 409, resolution/interface/bind 409, storage/inconsistent 503. Do not expose platform exception text.
- [ ] Run focused tests, then `./gradlew.bat :backend:test --tests "com.filamentvision.backend.api.UdpInputConfigurationApiTest" --tests "com.filamentvision.backend.api.RestContractTest" --tests "com.filamentvision.backend.api.RequestMediaContractTest" --tests "com.filamentvision.backend.api.HealthApiTest"`.
- [ ] Inspect diff and commit `feat: expose flexible UDP network REST contract`. Rollback is the HTTP adapter boundary.

### Task 15: Add applied-plan provenance to diagnostics and Packet Inspector

**Files:**
- Modify: `backend/src/main/kotlin/com/filamentvision/backend/input/diagnostics/UdpDiagnostics.kt`
- Modify: `backend/src/main/kotlin/com/filamentvision/backend/input/inspector/PacketInspectorModels.kt`
- Modify: `backend/src/main/kotlin/com/filamentvision/backend/api/dto/PacketInspectorDtos.kt`
- Modify: `backend/src/main/kotlin/com/filamentvision/backend/api/dto/RaspberryPiUdpInputDtoMappers.kt`
- Modify: `backend/src/test/kotlin/com/filamentvision/backend/input/diagnostics/UdpDiagnosticsTest.kt`
- Modify: `backend/src/test/kotlin/com/filamentvision/backend/input/inspector/PacketInspectorTest.kt`
- Modify: `backend/src/test/kotlin/com/filamentvision/backend/api/PacketInspectorApiTest.kt`
- Modify: `backend/src/test/kotlin/com/filamentvision/backend/api/UdpDiagnosticsApiTest.kt`

**Interfaces:**
- Diagnostics/Inspector snapshot applied configured/resolved/bind/source/generation/time values plus actual sender; optional preview is labelled not applied.

- [ ] Add failing tests that snapshots use the immutable applied plan after DNS changes, mismatch shows actual sender, preview refresh does not change sequence/counters, lease bounds remain exact and no full frame/resolution history is retained.
- [ ] Run `./gradlew.bat :backend:test --tests "com.filamentvision.backend.input.diagnostics.UdpDiagnosticsTest" --tests "com.filamentvision.backend.input.inspector.PacketInspectorTest" --tests "com.filamentvision.backend.api.PacketInspectorApiTest" --tests "com.filamentvision.backend.api.UdpDiagnosticsApiTest"`; expect missing fields.
- [ ] Implement bounded provenance fields without placing hostname resolution in the packet path.
- [ ] Run the focused command, then `./gradlew.bat :backend:test --tests "com.filamentvision.backend.input.runtime.RaspberryPiUdpInputSourceTest"`.
- [ ] Inspect diff and commit `feat: diagnose applied UDP network provenance`. Rollback affects diagnostics contracts only.

### Task 16: Update frontend domain, validation and Backend client

**Files:**
- Modify: `frontend/src/domain/raspberryPiUdpInput.ts`
- Modify: `frontend/src/config/raspberryPiUdpInputProfile.ts`
- Modify: `frontend/src/config/raspberryPiUdpInputProfile.test.ts`
- Modify: `frontend/src/services/BackendApiClient.ts`
- Modify: `frontend/src/services/BackendApiClient.test.ts`

**Interfaces:**
- Produces TypeScript discriminated unions matching Backend DTOs and `previewUdpNetwork(device, network, signal)`.

- [ ] Write failing tests for defaults, all discriminated modes, hostname/IPv4 feedback, empty source -> ANY, nested Save body, preview route/body, Backend field errors and request abort.
- [ ] Run `npm.cmd test -- --run src/config/raspberryPiUdpInputProfile.test.ts src/services/BackendApiClient.test.ts`; expect TypeScript/test failures for absent network types.
- [ ] Implement domain/client/instant validation; Backend remains authoritative.
- [ ] Run focused tests, then `npm.cmd run lint`.
- [ ] Inspect diff and commit `feat: model flexible UDP networks in frontend`. Rollback is limited to non-rendered frontend contracts.

### Task 17: Build simple Settings and Advanced Network UI

**Files:**
- Modify: `frontend/src/components/RaspberryPiUdpInputSettings.tsx`
- Modify: `frontend/src/components/RaspberryPiUdpInputSettings.test.tsx`
- Modify: relevant styles in `frontend/src/App.css`

**Interfaces:**
- Normal view shows Enabled, Backend Host/IP, Raspberry Pi Host/IP, port, status, resolved values, last sender, send target and Resolve/Save/Apply.
- Advanced section exposes bind/interface/manual IP and explicit destination/source modes.

- [ ] Write failing interaction tests for AUTO/simple defaults, manual hostnames, empty source ANY, Advanced mode controls, multiple candidates, Resolve preview only, Save without Apply, Apply after Save, resolution changed/reapply labels, offline Save, server validation and navigation cancellation/latest response.
- [ ] Run `npm.cmd test -- --run src/components/RaspberryPiUdpInputSettings.test.tsx`; expect missing controls/labels.
- [ ] Implement the simple-first layout and `<details>` Advanced section. Never display preview as applied or Connected.
- [ ] Run focused tests, then `npm.cmd test -- --run src/config/raspberryPiUdpInputProfile.test.ts src/services/BackendApiClient.test.ts` and `npm.cmd run lint`.
- [ ] Inspect diff and commit `feat: configure flexible UDP addresses in Settings`. Rollback returns the prior Settings component without Backend changes.

### Task 18: Render applied network facts in Diagnostics and Inspector

**Files:**
- Modify: `frontend/src/components/UdpDiagnosticsPanel.tsx`
- Create: `frontend/src/components/UdpDiagnosticsPanel.test.tsx`
- Modify: `frontend/src/components/PacketInspector.tsx`
- Modify: `frontend/src/components/PacketInspector.test.tsx`
- Modify: `frontend/src/components/SystemHealth.tsx`
- Modify: `frontend/src/components/SystemHealth.test.tsx`

**Interfaces:**
- Renders applied destination/bind/source set, actual sender, generation/time and fail-closed/reapply statuses; optional preview is labelled `Not Applied`.

- [ ] Write failing tests for applied versus current resolution, set rendering, actual sender mismatch, Waiting/Receiving distinction, `NETWORK_CONFIGURATION_INCONSISTENT`, `LEGACY_PLAN_REAPPLY_REQUIRED`, Inspector lease cleanup and unchanged bounded preview behavior.
- [ ] Run `npm.cmd test -- --run src/components/UdpDiagnosticsPanel.test.tsx src/components/PacketInspector.test.tsx src/components/SystemHealth.test.tsx`; expect missing fields/status labels.
- [ ] Implement rendering without new polling loops; reuse the Settings/diagnostics cadence and abort lifecycle.
- [ ] Run focused tests, then `npm.cmd test -- --run src/components/RaspberryPiUdpInputSettings.test.tsx` and `npm.cmd run lint`.
- [ ] Inspect diff and commit `feat: show applied UDP network diagnostics`. Rollback is display-only.

### Task 19: Prove real UDP loopback, restart and remote-network semantics

**Files:**
- Create: `backend/src/test/kotlin/com/filamentvision/backend/e2e/FlexibleUdpNetworkE2ETest.kt`
- Modify: `backend/src/test/kotlin/com/filamentvision/backend/e2e/UdpTestSender.kt` only to accept caller-provided destination/source/port; never add a production default.
- Modify: `backend/src/test/kotlin/com/filamentvision/backend/e2e/UdpInputEndToEndTest.kt` only for new profile construction.

**Interfaces:**
- Uses real IPv4 UDP sockets with dynamically allocated ports and injected resolution/interface inventory; production path remains independent of the fixture.

- [ ] Write tests for ANY input, multi-address applied set, mismatch, hostname Apply, hostname change preview then reapply, wildcard->specific/manual restart, bind failure restoration, different-subnet planner acceptance, JPEG structured multi-chunk measurement and Inspector raw/parsed results.
- [ ] Run `./gradlew.bat :backend:test --tests "com.filamentvision.backend.e2e.FlexibleUdpNetworkE2ETest"`; expect failures because the production chain is not fully wired.
- [ ] Make only integration wiring fixes revealed by the tests; do not change transport bytes or Vision expected values.
- [ ] Run focused tests, then `./gradlew.bat :backend:test --tests "com.filamentvision.backend.e2e.UdpInputEndToEndTest" --tests "com.filamentvision.backend.e2e.ReviewRegressionTest"`.
- [ ] Inspect diff and commit `test: verify flexible UDP network end to end`. Rollback removes E2E fixtures/tests and any narrowly identified wiring fix.

### Task 20: Prove long-run bounds and complete the regression gate

**Files:**
- Modify: `backend/src/test/kotlin/com/filamentvision/backend/e2e/LongRunBoundednessTest.kt`
- Modify: `backend/src/main/kotlin/com/filamentvision/backend/input/network/HostResolver.kt` to expose an internal immutable `HostResolverBounds(activeLookups, queuedLookups)` snapshot
- Modify: `backend/src/main/kotlin/com/filamentvision/backend/input/network/NetworkResolutionPreviewService.kt` to expose an internal immutable `PreviewBounds(active, queued, retainedResults)` snapshot
- Modify: `backend/src/main/kotlin/com/filamentvision/backend/input/runtime/RaspberryPiUdpInputSource.kt` to include prepared and committed receiver counts in its existing internal test snapshot
- Create: `docs/implementation/flexible-udp-network-verification.md`

**Interfaces:**
- Produces final evidence only; no new protocol or product behavior.

- [ ] Add boundedness assertions for 100 preview refreshes, 50 resolution/bind/CAS failures, compensation failure isolation, one latest preview, per-device preview active<=1/queued<=1, process resolver active<=2/queued<=2, source set<=16, provisional<=1, committed receiver<=1, Inspector retained bytes unchanged, mailbox<=1 and `activeProducerCount<=1`.
- [ ] Run `./gradlew.bat :backend:test --tests "com.filamentvision.backend.e2e.LongRunBoundednessTest"`; verify RED first for newly exposed counters, then add only test-observability accessors that allocate no production state.
- [ ] Run the focused test and record maxima in the verification document.
- [ ] Run `./gradlew.bat clean :core:test :backend:test verifyStandaloneIndependence --no-daemon` with JDK 21.
- [ ] From `frontend`, run separately: `npm.cmd test -- --run`, `npm.cmd run lint`, and `npm.cmd run build`.
- [ ] From root run `powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File .\scripts\Launcher.Tests.ps1`, `git diff --check`, `git diff --stat`, and `git status --short`.
- [ ] Confirm zero skipped tests, unchanged wire/golden tests, no production example address, and document actual counts/results.
- [ ] Commit `docs: verify flexible UDP network configuration`. Rollback removes only evidence/test-observability additions; any failed gate blocks completion.

## Dependency and rollback chain

```text
1 characterization
 -> 2 profile
 -> 3 resolver
 -> 4 planner
 -> 5 effective plan/preview
 -> 6 profile codec
 -> 7 plan codec/schema
 -> 8 repository atomicity
 -> 9 source filter
 -> 10 prepared receiver
 -> 11 Apply happy path
 -> 12 rollback/fail-closed
 -> 13 bootstrap
 -> 14 REST
 -> 15 diagnostics/Inspector
 -> 16 frontend contracts
 -> 17 Settings
 -> 18 diagnostic UI
 -> 19 E2E
 -> 20 boundedness/gate
```

Tasks are strictly serial. Each commit is a rollback boundary. Schema Task 7 must not be deployed without repository Task 8; implementation execution keeps both locally sequential and does not publish an intermediate release. A stop condition is any need to change the approved protocol, select an ambiguous address, resolve during startup/receive, expose provisional data, advance only one side of revision/plan, exceed a bound, or edit Vision golden values.

## Design-to-plan coverage review

- Independent bind/destination/source intent: Tasks 2, 4.
- IPv4-only bounded HostResolver and multiple A records: Task 3.
- Deterministic AUTO and interface identity: Task 4.
- Immutable plan, provenance and preview separation: Task 5.
- Codec/migration/atomic revision+plan: Tasks 6-8.
- Applied-set source filter and invisible candidate: Tasks 9-10.
- Apply ordering, rollback and fail closed: Tasks 11-12.
- Restart without re-resolution and legacy behavior: Task 13.
- REST, diagnostics and Inspector: Tasks 14-15.
- Simple/Advanced Settings and applied/preview labels: Tasks 16-18.
- Remote routed environments, unchanged image pipeline and actual UDP: Task 19.
- Bounded resolution/Apply lifecycle and full regressions: Task 20.

No approved requirement is deferred and no unconfirmed Raspberry Pi protocol is introduced.
