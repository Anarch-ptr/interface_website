# Task 3 — decoded frame boundary

Core now owns the decoded `CameraFrame` contract. A frame carries camera identity, wall-clock capture time, source-local monotonic receive time, dimensions, ARGB32 format, orientation, mirror flag, sequence, diagnostics, pixels, and a lease. IDs must be non-blank; dimensions positive; timestamps, sequence and dropped-frame count non-negative; orientation must be 0, 90, 180 or 270; pixel count must equal `width × height` for ARGB32. Epoch and monotonic timestamps are different clock domains and must not be subtracted.

`PixelBuffer.ints` accepts ownership of an `IntArray` without copying and exposes only its count and indexed reads. The transferring caller must stop mutating that array. `DecodedFrameAdapter` is the backend ingress boundary: it copies the externally owned `DecodedImage.argb` exactly once, then transfers that copy into the Core buffer. Later consumers share the buffer and frame metadata. `DecodedImage` remains in Backend; encoded images and packet decoding are outside this contract.

The frame owner closes `CameraFrame` after all consumers finish. The frame delegates to its `FrameLease`; an atomic close gate invokes the release callback at most once even when closes race. Consumers cannot safely retain the pixel view past that lifetime. No reference counting is introduced.

`ImageInputSource` is a Core interface for decoded-frame flow, lifecycle (`start`/`stop`), state, diagnostics and active producer count. Implementations own the producer and should report a count in `0..1`; observing the flow does not start production. This task adds the contract only, with no simulator or transport implementation.

Test-first evidence: the focused Core test initially failed compilation on unresolved frame types and then passed with the Core implementation and `verifyCorePlatformBoundary`. The focused adapter test initially failed compilation on unresolved adapter types and then passed after adding the single-copy mapper. The full offline rerun and counts are recorded in the Task 3 report.
