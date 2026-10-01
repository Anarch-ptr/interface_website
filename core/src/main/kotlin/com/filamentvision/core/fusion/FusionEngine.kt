package com.filamentvision.core.fusion

import com.filamentvision.core.frame.CameraId
import com.filamentvision.core.model.FusionConfiguration
import com.filamentvision.core.model.MeasurementStatus
import com.filamentvision.core.model.OfficialMeasurement
import com.filamentvision.core.vision.CameraMeasurement
import kotlin.math.abs

enum class FusionReason {
    BOTH_CAMERAS, CAMERA_A_MISSING, CAMERA_B_MISSING,
    CAMERA_A_LOW_CONFIDENCE, CAMERA_B_LOW_CONFIDENCE, TIMESTAMP_MISMATCH,
}

data class FusionResult(
    val measurement: OfficialMeasurement,
    val activeCameraIds: Set<CameraId>,
    val cameraDifferenceMm: Double?,
    val timestampsCompatible: Boolean,
) {
    val reason: FusionReason get() = measurement.fusionReason
}

class FusionEngine {
    fun fuseDetailed(cameraA: CameraMeasurement?, cameraB: CameraMeasurement?, profile: FusionConfiguration): FusionResult? {
        var validA = cameraA?.takeIf { it.valid && it.confidence >= profile.minimumConfidence }
        var validB = cameraB?.takeIf { it.valid && it.confidence >= profile.minimumConfidence }
        val timestampsCompatible = cameraA == null || cameraB == null ||
            abs(cameraA.timestamp - cameraB.timestamp) <= profile.maximumTimestampDifferenceMillis
        var reason = when {
            validA == null && cameraA != null -> FusionReason.CAMERA_A_LOW_CONFIDENCE
            validB == null && cameraB != null -> FusionReason.CAMERA_B_LOW_CONFIDENCE
            cameraA == null -> FusionReason.CAMERA_A_MISSING
            cameraB == null -> FusionReason.CAMERA_B_MISSING
            else -> FusionReason.BOTH_CAMERAS
        }
        if (validA != null && validB != null && !timestampsCompatible) {
            reason = FusionReason.TIMESTAMP_MISMATCH
            if (validA.timestamp >= validB.timestamp) validB = null else validA = null
        }
        if (validA == null && validB == null) return null
        val fused = when {
            validA != null && validB != null -> {
                val weightA = profile.cameraAWeight.coerceAtLeast(0.0)
                val weightB = profile.cameraBWeight.coerceAtLeast(0.0)
                val total = weightA + weightB
                if (total == 0.0) (validA.diameterMm + validB.diameterMm) / 2.0
                else (validA.diameterMm * weightA + validB.diameterMm * weightB) / total
            }
            validA != null -> validA.diameterMm
            else -> validB!!.diameterMm
        }
        val measurement = OfficialMeasurement(
            cameraA = validA,
            cameraB = validB,
            fusedDiameter = fused,
            shapeDifference = if (validA != null && validB != null) abs(validA.diameterMm - validB.diameterMm) else 0.0,
            confidence = listOfNotNull(validA?.confidence, validB?.confidence).average(),
            status = when {
                validA == null -> MeasurementStatus.CAMERA_A_FAILURE
                validB == null -> MeasurementStatus.CAMERA_B_FAILURE
                else -> MeasurementStatus.NORMAL
            },
            timestamp = maxOf(validA?.timestamp ?: Long.MIN_VALUE, validB?.timestamp ?: Long.MIN_VALUE),
            fusionReason = reason,
        )
        return FusionResult(
            measurement = measurement,
            activeCameraIds = setOfNotNull(validA?.cameraId, validB?.cameraId),
            cameraDifferenceMm = if (cameraA != null && cameraB != null) abs(cameraA.diameterMm - cameraB.diameterMm) else null,
            timestampsCompatible = timestampsCompatible,
        )
    }
}
