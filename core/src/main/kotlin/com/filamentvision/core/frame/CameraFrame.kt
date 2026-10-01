package com.filamentvision.core.frame

@JvmInline
value class CameraId(val value: String) {
    init {
        require(value.isNotBlank()) { "cameraId must not be blank" }
    }
}

enum class PixelFormat(val elementsPerPixel: Int) {
    ARGB32(1),
}

data class FrameDiagnostics(
    val sourceDroppedFrames: Long = 0,
    val note: String? = null,
) {
    init {
        require(sourceDroppedFrames >= 0) { "sourceDroppedFrames must be non-negative" }
    }
}

/**
 * Decoded frame. [capturedAtEpochMillis] uses a wall clock and
 * [receivedAtMonotonicNanos] uses a source-local monotonic clock; they must not be
 * subtracted from each other. Both timestamps and [sequence] are non-negative.
 *
 * The frame's owner closes it once all consumers finish. Closing releases [lease]
 * exactly once. Consumers must not retain [pixels] after the owner closes the frame.
 */
class CameraFrame(
    val cameraId: CameraId,
    val capturedAtEpochMillis: Long,
    val receivedAtMonotonicNanos: Long,
    val width: Int,
    val height: Int,
    val pixelFormat: PixelFormat,
    val pixels: PixelBuffer,
    val orientationDegrees: Int,
    val mirrored: Boolean,
    val sequence: Long,
    val diagnostics: FrameDiagnostics = FrameDiagnostics(),
    val lease: FrameLease = FrameLease(),
) : AutoCloseable {
    init {
        require(width > 0 && height > 0) { "frame width and height must be positive" }
        val expectedCount = width.toLong() * height * pixelFormat.elementsPerPixel
        require(pixels.count.toLong() == expectedCount) {
            "pixel count ${pixels.count} does not match $width x $height $pixelFormat ($expectedCount expected)"
        }
        require(orientationDegrees in setOf(0, 90, 180, 270)) {
            "orientationDegrees must be 0, 90, 180, or 270"
        }
        require(capturedAtEpochMillis >= 0) { "capturedAtEpochMillis must be non-negative" }
        require(receivedAtMonotonicNanos >= 0) { "receivedAtMonotonicNanos must be non-negative" }
        require(sequence >= 0) { "sequence must be non-negative" }
    }

    override fun close() = lease.close()
}
