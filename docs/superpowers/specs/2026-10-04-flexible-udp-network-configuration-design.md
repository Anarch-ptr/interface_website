# FilamentVision Flexible UDP Network Configuration Design Amendment

**Date:** 2026-10-04
**Status:** Approved requirements consolidated for implementation planning
**Amends:** `2026-10-03-single-camera-udp-design.md`
**Scope:** Backend UDP network configuration, resolution, persistence, REST diagnostics and Settings UI only

## 1. Authority and unchanged boundaries

This amendment replaces the single-camera design's coupled `NetworkInterfaceSelection` and `raspberryPiIpv4` network model. Raspberry Pi and Backend PC addresses, hostnames, interfaces and UDP listen port are operator configuration, never production constants. IPv4 literals appearing in tests are examples only.

The following remain unchanged: one production Raspberry Pi image stream, the exact 2-byte Datagram envelope, the 34-byte Structured Transport Header and network byte order, structured reassembly, JPEG/GRAY8/BINARY/RGB888 decoding, decoded allocation limits, bounded latest-frame admission, Vision, measurement/session/alarm reliability, Packet Inspector lease bounds, `activeProducerCount <= 1`, and the rule that Core has no socket/DNS/Ktor/SQLite/Compose dependency.

IPv4 remains the only production UDP family in this version. Hostname support resolves to IPv4. IPv6 and NAT traversal are not introduced.

## 2. Chosen architecture

The UDP profile owns user intent; an immutable effective plan owns the result of one successful Apply:

```text
RaspberryPiUdpInputProfile
  `-- UdpNetworkProfile
        |-- BindProfile
        |-- AdvertisedDestinationProfile
        |-- SourceFilterProfile
        `-- listenPort

Save -> persisted intent
Resolve/Refresh -> read-only NetworkResolutionPreview
Apply -> immutable EffectiveUdpNetworkPlan + receiver
```

`HostResolver`, interface enumeration and OS route probing are Backend adapters. `UdpReceiver`, Vision, frontend and Core never perform DNS. Bind address and advertised destination are independent values.

Rejected alternatives are: extending the old shared interface field, silently selecting the first interface/A record, continuously refreshing DNS inside the receiver, or treating resolution preview as an applied change.

## 3. Authoritative configuration model

```kotlin
data class UdpNetworkProfile(
    val bind: BindProfile,
    val destination: AdvertisedDestinationProfile,
    val sourceFilter: SourceFilterProfile,
    val listenPort: Int,
)

sealed interface BindProfile {
    data object Auto : BindProfile
    data object AllInterfaces : BindProfile
    data class SpecificInterface(val interfaceId: String, val ipv4: String) : BindProfile
    data class ManualIp(val ipv4: String) : BindProfile
}

sealed interface AdvertisedDestinationProfile {
    data object Auto : AdvertisedDestinationProfile
    data class Manual(val hostOrIpv4: String) : AdvertisedDestinationProfile
}

sealed interface SourceFilterProfile {
    data object Any : SourceFilterProfile
    data class IpOrHost(val hostOrIpv4: String) : SourceFilterProfile
}
```

`RaspberryPiUdpInputProfile` contains `network: UdpNetworkProfile` plus its existing payload, envelope and transport limits. Default intent is disabled, AUTO bind, AUTO destination, ANY source, and the existing editable default port. The port is a default, not a protocol constant, and remains editable in Settings.

Syntactic validation accepts canonical IPv4 literals or ASCII hostnames. A hostname is 1..253 characters, contains labels of 1..63 letters/digits/hyphens, permits a single-label host and `.local`, has no scheme, slash, port, bracket, whitespace or empty label, and is normalized to lowercase without a final dot. IPv6 literals and IPv4-mapped IPv6 are rejected. `SpecificInterface.interfaceId` is opaque UTF-8 with the existing bounded codec rule; its saved IPv4 remains canonical.

## 4. Bind semantics

| Mode | Actual socket bind | Interface validation |
|---|---|---|
| `AUTO` | `0.0.0.0` | None; Backend manages wildcard binding |
| `ALL_INTERFACES` | `0.0.0.0` | None; explicit operator intent |
| `SPECIFIC_INTERFACE` | saved interface IPv4 | Re-enumerate by stable `interfaceId`; interface exists, is up, and still owns the saved IPv4 |
| `MANUAL_IP` | configured IPv4 | Address belongs to an up local interface |

`AUTO` and `ALL_INTERFACES` have identical receive scope but different configuration meaning. Neither `0.0.0.0` nor a bind address is automatically advertised to the Raspberry Pi.

Specific failures are `INTERFACE_NOT_FOUND`, `INTERFACE_NOT_UP`, `INTERFACE_ADDRESS_CHANGED`, and `MANUAL_BIND_IP_NOT_LOCAL`. No address is silently substituted. The effective plan records the actual local interface ID for specific/manual binding and `null` for wildcard binding.

## 5. HostResolver

```kotlin
interface HostResolver {
    suspend fun resolveIpv4(hostOrIpv4: String): HostResolution
}

data class HostResolution(
    val configuredValue: String,
    val ipv4s: Set<String>,
    val status: ResolutionStatus,
    val resolvedAtEpochMillis: Long,
)
```

Canonical IPv4 literals return a one-element set without DNS. Hostnames use the system resolver on a bounded IO executor. Results retain only IPv4, normalize, deduplicate and sort numerically before being exposed as an immutable insertion-ordered set. DNS response ordering therefore cannot choose behavior. At most 16 IPv4 results are retained; excess results fail with `HOSTNAME_RESULT_LIMIT_EXCEEDED` rather than allocate unbounded state.

Resolution is configuration-plane work only. Apply is serialized. Each lookup has a 3,000 ms caller-visible timeout and preview accepts at most one active lookup plus one conflated queued refresh per device, with a 250 ms minimum interval between preview starts. Platform lookups run on one process-scoped `ThreadPoolExecutor` with two worker threads, a two-entry `ArrayBlockingQueue`, and rejection instead of an unbounded queue. A timed-out platform lookup may finish on that executor but its late result is discarded. DNS timeout or executor saturation is reported as `HOSTNAME_RESOLUTION_FAILED`; neither crashes Backend. Resolution success is not network connectivity and never means `Receiving`.

Destination requires exactly one final IPv4. Zero results are failure and multiple results are `DESTINATION_AMBIGUOUS`. Source `IP_OR_HOST` intentionally retains a `Set<String>` and accepts any address in the applied set. Source `ANY` performs no hostname resolution and no source filtering.

## 6. AUTO advertised destination

Destination AUTO follows this deterministic algorithm:

1. If Source is `IP_OR_HOST` and resolves, perform a no-send OS IPv4 route lookup for every resolved source address.
2. Use the routed local address only when every valid result converges on the same eligible local interface/address.
3. Different route results, missing results mixed with valid results, or more than one local result produce `DESTINATION_AMBIGUOUS`.
4. If Source is unavailable for routing (including ANY), enumerate eligible, up, non-loopback local IPv4 addresses.
5. Exactly one candidate is selected; zero produces `NO_DESTINATION_CANDIDATE`; more than one produces `DESTINATION_AMBIGUOUS`.

Interface index, display name, list ordering, Wi-Fi preference and “first candidate” are never tie-breakers. Virtual/VPN/Tailscale/ZeroTier interfaces are eligible when up and non-loopback. Same-subnet analysis is diagnostics only; a different subnet is allowed.

Destination MANUAL resolves the configured hostname/IP independently of bind mode. The UI advertises the configured hostname when manual and its unique resolved IPv4 separately. AUTO advertises its resolved IPv4 because it has no user-configured hostname.

## 7. Immutable EffectiveUdpNetworkPlan

```kotlin
data class EffectiveUdpNetworkPlan(
    val actualBindIpv4: String,
    val actualBindInterfaceId: String?,
    val configuredDestination: String?,
    val resolvedDestinationIpv4: String?,
    val configuredSource: String?,
    val resolvedSourceIpv4s: Set<String>,
    val destinationStatus: ResolutionStatus,
    val sourceStatus: ResolutionStatus,
    val destinationResolutionTime: Long?,
    val sourceResolutionTime: Long?,
    val planCreatedAtEpochMillis: Long,
    val appliedAtEpochMillis: Long,
    val resolutionGeneration: Long,
)
```

The successful plan is immutable and persisted with its applied revision. Receiver configuration, source filtering, diagnostics and Inspector use only this plan. They do not re-resolve DNS while running. Initial migrated INACTIVE state uses generation 0; every successful Apply of a different saved revision stores `previousGeneration + 1`. Failed Apply and idempotent Apply do not consume a generation.

Each HostResolution owns its resolution timestamp. `planCreatedAtEpochMillis` means candidate plan construction completion. `appliedAtEpochMillis` is captured immediately before the successful SQLite CAS transaction and persisted in that transaction; a failed transaction's timestamp is discarded. Neither timestamp is a DNS TTL. `resolutionGeneration` increases only on a successful Apply that commits a new plan; repeated idempotent Apply of the same applied revision returns the existing plan without resolving again.

Enabled profiles require a complete runnable plan and a unique destination. Disabled profiles create an INACTIVE plan: no receiver, destination/source status `NOT_REQUIRED`, and DNS/interface availability does not block Apply.

## 8. Applied resolution versus preview

`NetworkResolutionPreview` is a read-only snapshot containing configured values, current destination candidates, current source set, interfaces, route-selected candidate, warnings, differences from the applied plan, and preview timestamps. Resolve/Refresh may report `RESOLUTION_CHANGED` and `REAPPLY_REQUIRED` but must not Save, Apply, restart a socket, alter source filtering, publish runtime state, update UDP diagnostics/Inspector/Last Packet, or change a revision.

On process restart the persisted plan is authoritative. A non-destructive preview may reveal changed DNS; the old plan continues running and UI shows `REAPPLY_REQUIRED`. If its persisted bind/interface is no longer usable, receiver startup fails with the exact interface/bind diagnostic; Backend does not substitute or re-resolve.

Preview calls are latest-response-wins in the UI, cancellable by navigation, rate-limited, and bounded to one active resolver operation plus one queued refresh per device. They retain only the latest preview, never history.

## 9. Save and Apply transaction

Save validates syntax and bounds, persists user intent with saved-revision CAS, and performs no DNS, interface validation or receiver action. Offline/unresolvable hosts therefore do not prevent Save.

Apply is serialized with existing UDP configuration commands:

```text
load saved/applied state
validate revision and syntax
resolve destination/source
validate selected/local interfaces
build immutable candidate plan
prepare compiled processor/source filter
prepare and bind candidate socket
perform controlled provisional socket swap when required
CAS-persist applied revision + effective plan in one SQLite transaction
atomically publish runtime profile + plan
start/publish the committed receive loop
```

`PreparedUdpReceiver` is private provisional state. Before the repository commit it cannot update MonitoringRuntime admission, production UDP counters, Packet Inspector, last packet/sender, operational status, measurements, alarms or sessions. The preferred implementation binds without starting the receive loop. If a platform forces early receive, bytes remain in a bounded private provisional buffer/counter and are discarded on both commit and rollback; they are never replayed.

Wildcard and specific sockets on the same port may not coexist. A controlled swap may briefly stop the old socket, attempt candidate bind, and restore the old socket on failure while the old persisted plan remains authoritative. Resolution, interface, bind, CAS or persistence failure stops the candidate and restores the old receiver/profile/plan/revision. No state may expose a new receiver with an old revision or vice versa.

If compensation cannot restore the old receiver, runtime enters `NETWORK_CONFIGURATION_INCONSISTENT`. Persisted revision/plan remain the last committed fact, but the UI must not claim they are running. Save remains allowed; Apply is rejected until explicit process restart/recovery. This is not `BIND_FAILED`.

## 10. Source filtering and data plane

`UdpReceiver` receives a compiled immutable `Set<Inet4Address>` from the effective plan. ANY uses no set/filter. `IP_OR_HOST` accepts any source IPv4 in the applied set. A mismatch is rejected before transport parsing/reassembly/decoder/measurement and increments `sourceFilteredDatagrams`.

With an active Inspector lease, a mismatch may still copy the existing bounded raw preview and endpoint metadata and is labelled `SOURCE_IP_MISMATCH`; it never enters the pipeline. Inspector disabled retains no preview copy. DNS, strings and hostname objects are absent from the packet hot path.

## 11. Persistence migration

`RaspberryPiUdpInputProfileCodec` version 2 writes `UdpNetworkProfile` and continues to read version 1:

- old AUTO -> `BindProfile.Auto`;
- old SPECIFIC -> `BindProfile.SpecificInterface`;
- old null Raspberry Pi IPv4 -> `SourceFilterProfile.Any`;
- old Raspberry Pi IPv4 -> `SourceFilterProfile.IpOrHost`;
- old destination -> `AdvertisedDestinationProfile.Auto`.

`EffectiveUdpNetworkPlanCodec` is a separate bounded, versioned codec. SQLite V005 adds the applied-plan payload and resolution generation to `raspberry_pi_udp_input_state`. `markApplied` becomes a single CAS transaction that writes applied revision and plan together. Repository reads both in one snapshot.

A disabled legacy applied profile is migrated to an INACTIVE plan. An enabled legacy profile without a trustworthy persisted plan is marked `LEGACY_PLAN_REAPPLY_REQUIRED`; its receiver does not start until an explicit Apply. Migration does not perform DNS or enumerate/select an address. Corrupt/oversized codec data fails safely as storage invalid, preserving existing migration rollback.

## 12. REST contract

The profile JSON contains:

```json
{
  "network": {
    "bind": { "mode": "AUTO" },
    "destination": { "mode": "MANUAL", "hostOrIpv4": "filamentvision-pc.local" },
    "sourceFilter": { "mode": "IP_OR_HOST", "hostOrIpv4": "filament-pi.local" },
    "listenPort": 5101
  }
}
```

Mode-specific DTO fields are rejected when malformed; Backend validation is authoritative. Existing configuration GET/Save/Apply routes remain. Configuration state adds `appliedEffectiveNetworkPlan`, `networkConsistencyStatus`, `reapplyRequired`, and safe `lastApplyError`.

New read-only route:

```http
POST /api/v1/devices/{deviceId}/udp-network-resolution-preview
Content-Type: application/json

{ "network": <UdpNetworkProfileDto> }
```

The response includes configured destination/source, destination candidates, source set, eligible interfaces, route candidate, actual bind preview, warnings, comparison with applied plan, `RESOLUTION_CHANGED`, and `REAPPLY_REQUIRED`. It is not a command, creates no revision, and does not use command idempotency caches.

`GET /api/v1/network-interfaces` remains a platform inventory endpoint and no longer presents an address as applied/advertised truth. It returns all eligible entries and no silently selected address.

## 13. Error and status model

Authoritative Apply/preview codes are:

- `INVALID_HOST_OR_IPV4`, `IPV6_NOT_SUPPORTED`;
- `HOSTNAME_RESOLUTION_FAILED`, `HOSTNAME_RESULT_LIMIT_EXCEEDED`;
- `DESTINATION_AMBIGUOUS`, `NO_DESTINATION_CANDIDATE`;
- `SOURCE_RESOLUTION_FAILED`;
- `INTERFACE_NOT_FOUND`, `INTERFACE_NOT_UP`, `INTERFACE_ADDRESS_CHANGED`;
- `MANUAL_BIND_IP_NOT_LOCAL`;
- `UDP_BIND_FAILED`;
- `LEGACY_PLAN_REAPPLY_REQUIRED`;
- `NETWORK_CONFIGURATION_INCONSISTENT`;
- existing `CONFLICT`, `STORAGE_UNAVAILABLE`, and consistency failures.

Resolution statuses are `NOT_REQUIRED`, `RESOLVED`, `FAILED`, and `AMBIGUOUS`. Consistency statuses are `HEALTHY`, `REAPPLY_REQUIRED`, `LEGACY_PLAN_REAPPLY_REQUIRED`, and `NETWORK_CONFIGURATION_INCONSISTENT`. Operational status remains driven by the committed receiver: Disabled, UDP Ready, Waiting for Raspberry Pi, Receiving, No Packets Recently, Source Mismatch or Bind Failed. A successful DNS lookup never means Receiving.

## 14. Diagnostics and Packet Inspector

Diagnostics and Inspector identify applied facts explicitly:

- configured Backend destination and applied resolved destination;
- actual bind IPv4/interface;
- configured Raspberry Pi source and applied resolved source set;
- actual sender IPv4/port;
- applied resolution generation and timestamps;
- resolution-changed/reapply-required indicator.

An optional preview block is labelled `Not Applied` and is never used for packet acceptance. Raw Inspector snapshots remain bounded to the selected 64/128/256-byte preview, one latest snapshot, hard maximum 512 bytes and lease expiry. No full frame or DNS history is retained.

## 15. Settings layout

The normal view remains simple:

```text
Raspberry Pi UDP Input
Enabled
Backend Host / IP          [AUTO or manual hostname/IPv4]
Raspberry Pi Host / IP     [hostname/IPv4/empty]
UDP Listen Port
Current Status
Resolved Backend IPv4
Resolved Raspberry Pi IPv4(s)
Last Actual Sender
Raspberry Pi should send UDP to: <configured host or resolved AUTO IPv4>:<port>
Resolve / Refresh    Save    Apply
```

Empty Raspberry Pi host maps to Source ANY. Manual Backend host maps to Destination MANUAL. Advanced Network Settings contains Bind Mode, interface picker, manual local bind IPv4, explicit Destination Mode, Source Filter Mode, interface/route candidates, timestamps, applied-versus-preview details, subnet warning and resolution diagnostics.

The UI labels Resolve as preview only, Save as persisted intent only, and Apply as the only action that changes the running effective plan. Example change presentation:

```text
Applied:            10.97.199.28
Current Resolution: 10.97.199.42
RESOLUTION_CHANGED - REAPPLY_REQUIRED
```

The example addresses never become defaults or production constants.

## 16. Remote-network and NAT boundary

No validation requires same subnet or private address ranges. Routed LAN, Ethernet, Wi-Fi, VPN, Tailscale, ZeroTier and port-forwarded IPv4 are allowed. Diagnostics may say `DIFFERENT_SUBNET_ROUTE_REQUIRED`, but it is a warning.

Hostname editing and DNS do not provide NAT traversal. Independent NAT networks still require VPN/overlay routing, router UDP forwarding or another external routing solution. FilamentVision only configures bind, destination guidance, source filtering and diagnostics.

## 17. Safety and boundedness

- Host strings, interface IDs, address sets and preview responses have explicit size/count limits.
- DNS resolution runs outside packet processing with bounded concurrency and timeout reporting.
- Route probing sends no payload.
- Preview keeps one latest result and cannot enqueue unbounded refreshes.
- Effective plans and source sets are immutable defensive copies.
- Apply owns at most old plus one provisional receiver; only one committed receiver job exists.
- Provisional data never reaches reliable or preview event paths.
- Repeated Apply of the applied revision is a no-op and does not increment resolution generation.
- Save/preview cannot alter `activeProducerCount`.
- Long-run state remains bounded across refresh, failed Apply, rollback and restart cycles.

## 18. Test strategy

Tests must use injected resolver/interface/route/socket/time adapters; no unit test depends on the developer's real DNS or adapters.

Profile/hostname tests cover IPv4, single-label and `.local` hostname syntax, normalization, empty/oversized/malformed hosts, IPv6 rejection, port bounds and all mode-specific fields. Resolver tests cover literal bypass, DNS success/failure/timeout, dedupe/stable numeric order, IPv6 exclusion, multiple destination ambiguity, multiple source acceptance, result limit and changed result after refresh.

Network planning tests cover both wildcard modes, stable interface identity, removed/down/address-changed interface, manual local IP, Wi-Fi/Ethernet/VPN candidates, route convergence, route disagreement, zero/one/multiple candidates, different subnet warning, disabled INACTIVE plan and timestamp provenance.

Apply tests cover Save while offline, preview immutability, hostname change, bind change, parser-only change, idempotent Apply, bind failure rollback, resolution failure rollback, CAS race, persistence exception, candidate invisibility, old receiver restoration, compensation failure fail-closed, restart from persisted plan, and A/B legacy infrastructure remaining unused.

Persistence tests cover codec v1->v2 mapping, v2 round trip, plan codec bounds, V004->V005 migration for enabled/disabled legacy state, atomic revision+plan CAS, reopen, corrupt data and migration rollback.

REST/frontend tests cover DTO validation, preview read-only behavior, error mapping, simple/advanced layout, Resolve/Save/Apply separation, applied-versus-preview labels, multiple candidates, source set, last sender, offline operation, Settings navigation cancellation, Diagnostics and Inspector applied provenance.

Loopback integration uses only loopback or dynamically discovered test addresses/ports. It proves ANY and set-based filtering, source mismatch, receiver restart, unchanged 2-byte/34-byte/JPEG pipeline, Packet Inspector, no fake input and continued measurement flow. No test fixture establishes a production default address.

LongRunBoundedness repeats at least 100 preview refreshes, 50 failed Apply/rollback cycles and receiver start/stop/restart while asserting: one latest preview, resolver concurrency bound, no retained provisional packets, receiver count <= 1 committed, candidate count <= 1, source set <= 16, Inspector bounds unchanged, mailbox <= 1, and `activeProducerCount <= 1`.

## 19. Regression gate

The implementation gate runs focused profile/resolver/planner, receiver/source, repository/migration, REST, frontend and loopback tests; then full `:core:test`, `:backend:test`, frontend test/lint/build, launcher contract, `git diff --check`, and clean status. Golden Vision, measurement, alarm, session, preview and reliable-event tests must remain unchanged. The Structured Transport Header and image algorithm golden values cannot be edited to make this change pass.

## 20. Defined non-goals

No IPv6 socket, mDNS daemon, custom DNS server, DNS polling, dynamic receiver mutation, NAT traversal, VPN management, router configuration, Raspberry Pi remote configuration, new transport mode, camera protocol change, Vision change or website redesign is included.
