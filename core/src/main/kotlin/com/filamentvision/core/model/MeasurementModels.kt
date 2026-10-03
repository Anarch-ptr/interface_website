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

/** Historical/generic fusion metadata; never created for single-camera production samples. */
data class LegacyFusionMetadata(
    val cameraA: CameraMeasurement?,
    val cameraB: CameraMeasurement?,
    val shapeDifference: Double,
    val fusionReason: FusionReason,
)

/** Official samples retain only actual evidence, independently of any assembly algorithm. */
data class OfficialMeasurement(
    val evidence: List<CameraMeasurement>,
    val diameterMm: Double,
    val confidence: Double,
    val status: MeasurementStatus,
    val timestamp: Long,
    val legacyFusion: LegacyFusionMetadata? = null,
) {
    /** Compatibility for generic fusion and immutable legacy persistence rows. */
    constructor(
        cameraA: CameraMeasurement?, cameraB: CameraMeasurement?, fusedDiameter: Double,
        shapeDifference: Double, confidence: Double, status: MeasurementStatus,
        timestamp: Long, fusionReason: FusionReason,
    ) : this(
        listOfNotNull(cameraA, cameraB), fusedDiameter, confidence, status, timestamp,
        LegacyFusionMetadata(cameraA, cameraB, shapeDifference, fusionReason),
    )

    val cameraA: CameraMeasurement? get() = legacyFusion?.cameraA
    val cameraB: CameraMeasurement? get() = legacyFusion?.cameraB
    val fusedDiameter: Double get() = diameterMm
    val shapeDifference: Double get() = checkNotNull(legacyFusion) { "No legacy fusion metadata" }.shapeDifference
    val fusionReason: FusionReason get() = checkNotNull(legacyFusion) { "No legacy fusion metadata" }.fusionReason

    /** Rebuild neutral evidence when a legacy consumer changes an accepted camera slot. */
    fun copy(
        cameraA: CameraMeasurement? = this.cameraA,
        cameraB: CameraMeasurement? = this.cameraB,
        fusedDiameter: Double = this.fusedDiameter,
        shapeDifference: Double = this.shapeDifference,
        confidence: Double = this.confidence,
        status: MeasurementStatus = this.status,
        timestamp: Long = this.timestamp,
        fusionReason: FusionReason = this.fusionReason,
    ): OfficialMeasurement = OfficialMeasurement(
        cameraA, cameraB, fusedDiameter, shapeDifference, confidence, status, timestamp, fusionReason,
    )
}
