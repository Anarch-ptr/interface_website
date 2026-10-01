package com.filamentvision.core.fusion

import com.filamentvision.core.vision.CameraMeasurement

data class MeasurementPair(val cameraA: CameraMeasurement?, val cameraB: CameraMeasurement?)

/** Pair selection only. The caller serializes offers and resets; Fusion decides validity and tolerance. */
interface FramePairingStrategy {
    fun offer(measurement: CameraMeasurement): List<MeasurementPair>
    fun reset()
}
