# Phase 3 frontend reservation

This directory reserves the Website frontend for Phase 3. No React application is
implemented in Phase 2. Start only after the final Phase 2 gate is accepted.

Use the simulator-backed contracts:

- [REST `/api/v1`](../docs/implementation/task-11-rest-contracts.md)
- [Event and preview WebSockets](../docs/implementation/task-12-websocket-contracts.md)
- [Composed backend behavior and run commands](../docs/implementation/task-13-simulated-backend.md)
- [Final Phase 2 evidence and readiness](../docs/web-migration/17-phase2-implementation-report.md)

Clients observe state and issue idempotent commands. Subscribing, navigating or
disconnecting must not create or stop a producer. Missing camera evidence is
nullable; raw A/B metadata and fused output are distinct. Reliable events and
lossy previews use separate connections. Replay cursors are scoped to a backend
lifetime; reconnect without an old cursor after restart, and handle snapshot
recovery when replay expires. All configuration Apply commands require a stopped
runtime in the current composition. Preview revision/draft/official metadata is
currently unknown (`null`).

Readiness concerns only the Website against simulator contracts. Real hardware,
deployment, authentication, TLS and production retention remain unconfirmed.
