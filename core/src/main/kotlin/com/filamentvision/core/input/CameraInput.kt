package com.filamentvision.core.input

import com.filamentvision.core.frame.CameraFrame
import com.filamentvision.core.frame.CameraId

enum class DirectDiameterEncoding { FLOAT32, FLOAT64, INT16, INT32, ASCII }
enum class DirectDiameterUnit { MM, MICROMETER, INCH }

sealed interface CameraInput {
    val cameraId: CameraId
    val receivedAtEpochMillis: Long
    val receivedAtMonotonicNanos: Long
    val sequence: Long

    data class Image(
        val frame: CameraFrame,
    ) : CameraInput {
        override val cameraId: CameraId get() = frame.cameraId
        override val receivedAtEpochMillis: Long get() = frame.capturedAtEpochMillis
        override val receivedAtMonotonicNanos: Long get() = frame.receivedAtMonotonicNanos
        override val sequence: Long get() = frame.sequence
    }

    data class DirectDiameter(
        val evidence: DirectDiameterEvidence,
    ) : CameraInput {
        override val cameraId: CameraId get() = evidence.cameraId
        override val receivedAtEpochMillis: Long get() = evidence.receivedAtEpochMillis
        override val receivedAtMonotonicNanos: Long get() = evidence.receivedAtMonotonicNanos
        override val sequence: Long get() = evidence.sequence
    }
}

data class DirectDiameterEvidence(
    val cameraId: CameraId,
    val rawValue: Double,
    val encoding: DirectDiameterEncoding,
    val unit: DirectDiameterUnit,
    val normalizedDiameterMm: Double,
    val deviceConfidence: Double?,
    val receivedAtEpochMillis: Long,
    val receivedAtMonotonicNanos: Long,
    val sourceTimestampMicros: Long?,
    val sequence: Long,
)
