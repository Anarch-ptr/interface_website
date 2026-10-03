package com.filamentvision.core.measurement

import com.filamentvision.core.frame.CameraId
import com.filamentvision.core.model.MeasurementStatus
import com.filamentvision.core.model.OfficialMeasurement
import com.filamentvision.core.vision.CameraMeasurement
import com.filamentvision.core.vision.CameraMeasurementSource

/** Stateless assembly: every accepted Vision sample independently yields one official sample. */
class SingleCameraMeasurementAssembler(private val cameraId: CameraId = CameraId("camera")) : MeasurementAssembler {
    override fun reset() = Unit

    override fun accept(evidence: CameraMeasurement): List<OfficialMeasurement> {
        require(evidence.cameraId == cameraId) { "Unknown camera identity: ${evidence.cameraId}" }
        if (!evidence.valid || evidence.error != null || evidence.source != CameraMeasurementSource.VISION) return emptyList()
        return listOf(OfficialMeasurement(
            evidence = listOf(evidence),
            diameterMm = evidence.diameterMm,
            confidence = evidence.confidence,
            status = MeasurementStatus.NORMAL,
            timestamp = evidence.timestamp,
        ))
    }
}
