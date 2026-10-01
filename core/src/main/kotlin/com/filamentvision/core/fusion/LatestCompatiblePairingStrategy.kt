package com.filamentvision.core.fusion

import com.filamentvision.core.frame.CameraId
import com.filamentvision.core.vision.CameraMeasurement

/** Phase 2 latest-counterpart policy; a counterpart may participate in multiple outputs. */
class LatestCompatiblePairingStrategy(private val cameraAId: CameraId, private val cameraBId: CameraId) : FramePairingStrategy {
    private var latestA: CameraMeasurement? = null
    private var latestB: CameraMeasurement? = null

    init {
        require(cameraAId != cameraBId) { "Camera A and B identities must differ" }
    }

    override fun offer(measurement: CameraMeasurement): List<MeasurementPair> {
        when (measurement.cameraId) {
            cameraAId -> latestA = measurement
            cameraBId -> latestB = measurement
            else -> throw IllegalArgumentException("Unknown camera identity: ${measurement.cameraId}")
        }
        return listOf(MeasurementPair(latestA, latestB))
    }

    override fun reset() {
        latestA = null
        latestB = null
    }
}
