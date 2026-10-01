# Task 11 REST adapter contract

`Application.installRestApi(BackendApplication, correlationId)` registers the Phase 2
HTTP adapter without starting a server, producer, runtime or database. Task 13 supplies
the application implementation. No authentication or hardware transport is claimed by
this test-mode adapter.

## Response and command rules

Successful JSON is `{ "correlationId": "...", "data": ... }`. Errors have `code`,
`message`, `correlationId`, and nullable `details`. Every response also includes
`X-Correlation-Id`. Correlations identify each HTTP request, so command replay may have
a new correlation while retaining the same application outcome. An injectable generator
supports deterministic tests; blank/header-unsafe generated values fall back to UUIDs.

Every mutation has a JSON body with a nonblank string `commandId`, including DELETE.
The facade must atomically deduplicate commands by device, operation and command ID and
check payload consistency. Routes neither cache commands nor create producers. Start
accepts optional positive `targetDiameter` and `appliedRevision` preconditions, enforced
by the application under its lifecycle guard. Draft updates require `expectedRevision`.

Requests require `application/json`. Unknown fields, duplicate fields, numeric/boolean
values in string fields, null primitive values, malformed/trailing JSON, non-finite or
invalid configuration values, and invalid revisions produce a structured 400 error.
Configuration enum strings use their documented Core enum names. Output uses explicit
API DTOs only, with epoch-millisecond timestamps. Absent camera evidence is a whole-side
null; numerical non-finite measurement/statistical/metric evidence maps to null. A final
serializer guard rejects other non-finite outputs with a safe 500 before sending JSON.

## Endpoint table

Paths below have the `/api/v1` prefix. Success is 200 unless stated otherwise.

| Method/path | Input and response |
|---|---|
| GET `/devices/{deviceId}/state` | Connection, monitoring state, cameras, active session, latest measurement, alarm/statistics, revisions, producer count, event sequence |
| POST `/devices/{deviceId}/monitoring/start` | `commandId`, optional target/revision preconditions; outcome and session |
| POST `/devices/{deviceId}/monitoring/stop` | `commandId`; stopped outcome and nullable session |
| GET `/devices/{deviceId}/measurements/latest` | Nullable record containing ID, session ID, applied revision and measurement |
| GET `/sessions` | `limit`, `cursor`, `from`, `through`; session page |
| GET `/sessions/{sessionId}` | Session with nullable finalization and statistics |
| GET `/sessions/{sessionId}/measurements` | Same pagination/range query; measurement record page |
| GET `/sessions/{sessionId}/alarms` | Alarm transitions with timestamps and numerical evidence |
| GET `/devices/{deviceId}/configuration` | Saved/applied revisions, saved settings, nullable command outcome |
| PUT `/devices/{deviceId}/configuration` | `commandId`, `configuration`; Save response |
| POST `/devices/{deviceId}/configuration/apply` | `commandId`, positive `revision`, `expectedSavedRevision`; Apply response |
| POST `/devices/{deviceId}/configuration/save-and-apply` | `commandId`, `configuration`; combined response |
| POST `/devices/{deviceId}/calibration/drafts` | `commandId`, `cameraId`, `configuration` (vision settings); draft, 201 |
| PATCH `/devices/{deviceId}/calibration/drafts/{draftId}` | `commandId`, positive `expectedRevision`, `configuration`; revised draft |
| DELETE `/devices/{deviceId}/calibration/drafts/{draftId}` | `commandId`; `{ "deleted": true }` |
| GET `/devices/{deviceId}/cameras` | Camera IDs, availability and preview/calibration capabilities |
| GET `/devices/{deviceId}/diagnostics` | Independent rates, latency totals/maxima, queue/retention counts and backend version |
| GET `/errors` | Optional nonblank `sessionId`; public error records |
| GET `/errors/{errorId}/snapshots` | Snapshot metadata with source sequence and encoded API URLs |
| GET `/snapshots/{snapshotId}` | Test-mode encoded PNG/JPEG bytes, attachment filename, correlation and `nosniff` headers |

Configuration settings correspond exactly to current `MonitoringConfiguration`: both
cameras' calibration and geometry, fusion settings, target diameter, write/flush/realtime
bounds, preview rate and publication interval. Omitted settings take documented DTO
defaults. Save supplies a complete new aggregate; PATCH replaces the draft's complete
`configuration` value rather than merging it with prior settings. No unimplemented
transport, alarm-threshold, or snapshot-policy configuration is invented here.

Pagination defaults to 100, accepts 1–500, and returns nullable `nextCursor`. Cursors
encode a version and collection/session scope with the `(timestamp, id)` keyset. They
are opaque continuation tokens, not authorization credentials. Range bounds are inclusive,
nonnegative epoch milliseconds; `from <= through`. Empty/invalid/noncanonical/overlong
cursors, duplicate query parameters and invalid ranges/limits are 400. The session facade
must order ascending by `(startedAt, id)`; history uses `(timestamp, id)`, exclusive after.

## Failure mapping

| Outcome | Status / code |
|---|---|
| Request/typed validation failure | 400 `INVALID_REQUEST` |
| Unsupported mutation content type | 415 `UNSUPPORTED_MEDIA_TYPE` |
| Missing device/session/error/snapshot/revision/draft | 404 `NOT_FOUND` |
| Repository/application conflict or interrupted start | 409 `CONFLICT` |
| Configuration needs stopped runtime | 409 `REQUIRES_STOP`; configuration-result failures include saved/applied revision details |
| Failed/closed start, failed stop, unavailable application | 503 `UNAVAILABLE` |
| Typed storage failure | 503 `STORAGE_UNAVAILABLE` |
| Unexpected exception or invalid output/encoded-artifact metadata | 500 `INTERNAL_ERROR` |

Repository/runtime exception messages, session finalization errors, raw error messages,
object keys, filesystem paths and causes/stacks never enter DTOs. Error history retains
only a validated machine code and a generic public message. Snapshot lookup accepts an
ID only; the facade resolves and authorizes it. The adapter accepts PNG/JPEG media types
and a simple image attachment filename, and never constructs or opens a filesystem path.

## Composition handoff

The facade owns resource existence checks, authorization, command replay, preconditions,
configuration/draft serialization, session listing and error-to-snapshot association.
Its suspend methods must schedule blocking repository/object-store work appropriately.
Existing Core repositories do not yet expose session listing or error-to-snapshot joins;
these are explicit Task 13 composition concerns, not hidden route-side queries. Metrics
and snapshots remain independent of producer lifecycle. WebSockets, preview encoding,
an executable server and production authentication are outside Task 11.
