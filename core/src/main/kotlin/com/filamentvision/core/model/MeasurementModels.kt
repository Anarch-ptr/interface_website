package com.filamentvision.core.model

import com.filamentvision.core.fusion.FusionReason
import com.filamentvision.core.vision.CameraMeasurement

data class FusionConfiguration(
    val cameraAWeight: Double,
    val cameraBWeight: Double,
    val minimumConfidence: Double,
    val maximumTimestampDifferenceMillis: Long,
)

enum class MeasurementStatus { NORMAL, CAMERA_A_FAILURE, CAMERA_B_FAILURE }

/** Only accepted camera evidence is retained here; missing/rejected sides are explicitly absent. */
data class OfficialMeasurement(
    val cameraA: CameraMeasurement?,
    val cameraB: CameraMeasurement?,
    val fusedDiameter: Double,
    val shapeDifference: Double,
    val confidence: Double,
    val status: MeasurementStatus,
    val timestamp: Long,
    val fusionReason: FusionReason,
)
