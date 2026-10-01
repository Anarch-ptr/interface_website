package com.filamentvision.core.fusion

import com.filamentvision.core.frame.CameraId
import com.filamentvision.core.model.FusionConfiguration
import com.filamentvision.core.vision.CameraMeasurement
import org.junit.Assert.*
import org.junit.Test

class FusionCoordinatorTest {
    @Test fun injectedStrategyControlsEveryPairAndFusionFiltersUnusablePairs() {
        // Catches bypassing the strategy, fusing the offer itself, ignoring returned pairs or retaining null outputs.
        val offered = m("offer", 9.0, 70)
        val a = m("left", 1.7, 31)
        val b = m("right", 1.8, 42)
        val offers = mutableListOf<CameraMeasurement>()
        val strategy = object : FramePairingStrategy {
            override fun offer(measurement: CameraMeasurement): List<MeasurementPair> {
                offers += measurement
                return listOf(MeasurementPair(a, b), MeasurementPair(null, null), MeasurementPair(a, null))
            }
            override fun reset() = Unit
        }
        val coordinator = FusionCoordinator(strategy, FusionEngine(), FusionConfiguration(.25, .75, .7, 100))
        val results = coordinator.accept(offered)
        assertEquals(listOf(offered), offers)
        assertEquals(2, results.size)
        assertEquals(1.775, results[0].measurement.fusedDiameter, 1e-12)
        assertEquals(31L, results[0].measurement.cameraA!!.sequence)
        assertEquals(42L, results[0].measurement.cameraB!!.sequence)
        assertEquals(1.7, results[1].measurement.fusedDiameter, 0.0)
        assertEquals(FusionReason.CAMERA_B_MISSING, results[1].reason)
    }

    @Test fun strategyMayHoldAnOfferWithoutProducingAnOfficialMeasurement() {
        // Catches coordinator manufacturing results when a replacement strategy defers pairing.
        val strategy = object : FramePairingStrategy {
            override fun offer(measurement: CameraMeasurement) = emptyList<MeasurementPair>()
            override fun reset() = Unit
        }
        val coordinator = FusionCoordinator(strategy, FusionEngine(), FusionConfiguration(.25, .75, .7, 100))
        assertTrue(coordinator.accept(m("left", 1.7, 31)).isEmpty())
    }

    private fun m(id: String, diameter: Double, sequence: Long) =
        CameraMeasurement(CameraId(id), diameter * 100, diameter, .9, 1000, sequence, true)
}
