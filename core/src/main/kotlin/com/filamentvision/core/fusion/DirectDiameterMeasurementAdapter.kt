package com.filamentvision.core.fusion

import com.filamentvision.core.input.DirectDiameterEvidence
import com.filamentvision.core.vision.CameraMeasurement
import com.filamentvision.core.vision.CameraMeasurementSource

object DirectDiameterMeasurementAdapter {
    fun toMeasurement(
        evidence: DirectDiameterEvidence,
        defaultFusionConfidence: Double,
    ): CameraMeasurement {
        require(evidence.normalizedDiameterMm.isFinite())
        require(defaultFusionConfidence in 0.0..1.0)
        val confidence = evidence.deviceConfidence ?: defaultFusionConfidence
        require(confidence in 0.0..1.0)
        return CameraMeasurement(
            cameraId = evidence.cameraId,
            pixelWidth = null,
            diameterMm = evidence.normalizedDiameterMm,
            confidence = confidence,
            timestamp = evidence.receivedAtEpochMillis,
            sequence = evidence.sequence,
            valid = true,
            source = CameraMeasurementSource.DIRECT_DIAMETER,
        ).also { it.receivedAtMonotonicNanos = evidence.receivedAtMonotonicNanos }
    }
}
