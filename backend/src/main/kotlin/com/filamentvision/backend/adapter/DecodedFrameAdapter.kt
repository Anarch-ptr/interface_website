package com.filamentvision.backend.adapter

import com.filamentvision.core.frame.CameraFrame
import com.filamentvision.core.frame.CameraId
import com.filamentvision.core.frame.FrameDiagnostics
import com.filamentvision.core.frame.FrameLease
import com.filamentvision.core.frame.PixelBuffer
import com.filamentvision.core.frame.PixelFormat

/** Backend-owned decoded image. The caller may still mutate [argb]. */
data class DecodedImage(val width: Int, val height: Int, val argb: IntArray)

object DecodedFrameAdapter {
    fun toFrame(
        image: DecodedImage,
        cameraId: CameraId,
        capturedAtEpochMillis: Long,
        receivedAtMonotonicNanos: Long,
        sequence: Long,
        orientationDegrees: Int = 0,
        mirrored: Boolean = false,
        diagnostics: FrameDiagnostics = FrameDiagnostics(),
        lease: FrameLease = FrameLease(),
    ): CameraFrame = CameraFrame(
        cameraId = cameraId,
        capturedAtEpochMillis = capturedAtEpochMillis,
        receivedAtMonotonicNanos = receivedAtMonotonicNanos,
        width = image.width,
        height = image.height,
        pixelFormat = PixelFormat.ARGB32,
        pixels = PixelBuffer.ints(image.argb.copyOf()),
        orientationDegrees = orientationDegrees,
        mirrored = mirrored,
        sequence = sequence,
        diagnostics = diagnostics,
        lease = lease,
    )
}
