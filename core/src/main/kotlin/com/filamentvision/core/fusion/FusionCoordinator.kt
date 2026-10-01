package com.filamentvision.core.fusion

import com.filamentvision.core.model.FusionConfiguration
import com.filamentvision.core.vision.CameraMeasurement

class FusionCoordinator(
    private val pairingStrategy: FramePairingStrategy,
    private val fusionEngine: FusionEngine,
    private val configuration: FusionConfiguration,
) {
    fun accept(measurement: CameraMeasurement): List<FusionResult> =
        pairingStrategy.offer(measurement).mapNotNull { pair ->
            fusionEngine.fuseDetailed(pair.cameraA, pair.cameraB, configuration)
        }
}
