package com.filamentvision.characterization

import com.filamentvision.model.FusionProfile
import com.filamentvision.model.MeasurementStatus
import com.filamentvision.vision.detection.CameraMeasurement
import com.filamentvision.vision.fusion.FusionEngine
import com.filamentvision.vision.fusion.FusionReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FusionGoldenCharacterizationTest {
    private val profile = FusionProfile(0.25, 0.75, 0.7, 100)
    private val engine = FusionEngine()

    @Test
    fun weightedDualCameraRetainsRawValuesConfidencesAndShapeDifference() {
        // Catches changed weight normalization or discarded camera evidence.
        val result = engine.fuseDetailed(m("A", 1.7, 0.9, 1_000), m("B", 1.8, 0.8, 1_050), profile)!!

        assertEquals(1.7, result.measurement.diameterA, 0.0)
        assertEquals(1.8, result.measurement.diameterB, 0.0)
        assertEquals(1.775, result.measurement.fusedDiameter, 0.000_000_000_001)
        assertEquals(0.1, result.measurement.shapeDifference, 0.000_000_000_001)
        assertEquals(0.9, result.measurement.cameraAConfidence, 0.0)
        assertEquals(0.8, result.measurement.cameraBConfidence, 0.0)
        assertEquals(0.85, result.measurement.confidence, 0.000_000_000_001)
        assertEquals(MeasurementStatus.NORMAL, result.measurement.status)
        assertEquals(1_050L, result.measurement.timestamp)
        assertEquals(setOf("A", "B"), result.activeCameraIds)
        assertEquals(0.1, result.cameraDifferenceMm, 0.000_000_000_001)
        assertTrue(result.timestampsCompatible)
        assertEquals(FusionReason.BOTH_CAMERAS, result.reason)
    }

    @Test
    fun missingCameraFallsBackWithExplicitReasonAndNaNRawDiameter() {
        // Catches swapping A/B failure status or silently inventing a missing value.
        val onlyA = engine.fuseDetailed(m("A", 1.7, 0.9, 1_000), null, profile)!!
        assertEquals(1.7, onlyA.measurement.fusedDiameter, 0.0)
        assertEquals(1.7, onlyA.measurement.diameterA, 0.0)
        assertTrue(onlyA.measurement.diameterB.isNaN())
        assertEquals(0.9, onlyA.measurement.confidence, 0.0)
        assertEquals(0.9, onlyA.measurement.cameraAConfidence, 0.0)
        assertEquals(0.0, onlyA.measurement.cameraBConfidence, 0.0)
        assertEquals(0.0, onlyA.measurement.shapeDifference, 0.0)
        assertEquals(MeasurementStatus.CAMERA_B_FAILURE, onlyA.measurement.status)
        assertEquals(1_000L, onlyA.measurement.timestamp)
        assertEquals(setOf("A"), onlyA.activeCameraIds)
        assertTrue(onlyA.cameraDifferenceMm.isNaN())
        assertTrue(onlyA.timestampsCompatible)
        assertEquals(FusionReason.CAMERA_B_MISSING, onlyA.reason)

        val onlyB = engine.fuseDetailed(null, m("B", 1.8, 0.8, 1_050), profile)!!
        assertEquals(1.8, onlyB.measurement.fusedDiameter, 0.0)
        assertTrue(onlyB.measurement.diameterA.isNaN())
        assertEquals(1.8, onlyB.measurement.diameterB, 0.0)
        assertEquals(0.8, onlyB.measurement.confidence, 0.0)
        assertEquals(0.0, onlyB.measurement.cameraAConfidence, 0.0)
        assertEquals(0.8, onlyB.measurement.cameraBConfidence, 0.0)
        assertEquals(MeasurementStatus.CAMERA_A_FAILURE, onlyB.measurement.status)
        assertEquals(1_050L, onlyB.measurement.timestamp)
        assertEquals(setOf("B"), onlyB.activeCameraIds)
        assertTrue(onlyB.cameraDifferenceMm.isNaN())
        assertTrue(onlyB.timestampsCompatible)
        assertEquals(FusionReason.CAMERA_A_MISSING, onlyB.reason)
        assertNull(engine.fuseDetailed(null, null, profile))
    }

    @Test
    fun lowConfidenceCameraIsExcludedButRawDifferenceIsRetained() {
        // Catches threshold comparison, reason precedence, and loss of diagnostic difference.
        val lowA = engine.fuseDetailed(m("A", 1.7, 0.2, 1_000), m("B", 1.8, 0.8, 1_000), profile)!!
        assertEquals(1.8, lowA.measurement.fusedDiameter, 0.0)
        assertTrue(lowA.measurement.diameterA.isNaN())
        assertEquals(0.0, lowA.measurement.cameraAConfidence, 0.0)
        assertEquals(0.8, lowA.measurement.confidence, 0.0)
        assertEquals(MeasurementStatus.CAMERA_A_FAILURE, lowA.measurement.status)
        assertEquals(setOf("B"), lowA.activeCameraIds)
        assertEquals(0.1, lowA.cameraDifferenceMm, 0.000_000_000_001)
        assertEquals(FusionReason.CAMERA_A_LOW_CONFIDENCE, lowA.reason)

        val lowB = engine.fuseDetailed(m("A", 1.7, 0.9, 1_000), m("B", 1.8, 0.2, 1_000), profile)!!
        assertEquals(1.7, lowB.measurement.fusedDiameter, 0.0)
        assertTrue(lowB.measurement.diameterB.isNaN())
        assertEquals(0.0, lowB.measurement.cameraBConfidence, 0.0)
        assertEquals(0.9, lowB.measurement.confidence, 0.0)
        assertEquals(MeasurementStatus.CAMERA_B_FAILURE, lowB.measurement.status)
        assertEquals(setOf("A"), lowB.activeCameraIds)
        assertEquals(0.1, lowB.cameraDifferenceMm, 0.000_000_000_001)
        assertEquals(FusionReason.CAMERA_B_LOW_CONFIDENCE, lowB.reason)
        assertNull(engine.fuseDetailed(m("A", 1.7, 0.2, 1_000), m("B", 1.8, 0.2, 1_000), profile))
    }

    @Test
    fun timestampMismatchSelectsTheNewerCameraWithoutHidingMismatch() {
        // Catches stale pairing and the opposite newer-camera branch.
        val newerB = engine.fuseDetailed(m("A", 1.7, 0.9, 1_000), m("B", 1.8, 0.9, 1_500), profile)!!
        assertEquals(1.8, newerB.measurement.fusedDiameter, 0.0)
        assertTrue(newerB.measurement.diameterA.isNaN())
        assertEquals(0.9, newerB.measurement.confidence, 0.0)
        assertEquals(MeasurementStatus.CAMERA_A_FAILURE, newerB.measurement.status)
        assertEquals(1_500L, newerB.measurement.timestamp)
        assertEquals(setOf("B"), newerB.activeCameraIds)
        assertEquals(0.1, newerB.cameraDifferenceMm, 0.000_000_000_001)
        assertEquals(false, newerB.timestampsCompatible)
        assertEquals(FusionReason.TIMESTAMP_MISMATCH, newerB.reason)

        val newerA = engine.fuseDetailed(m("A", 1.7, 0.9, 1_500), m("B", 1.8, 0.9, 1_000), profile)!!
        assertEquals(1.7, newerA.measurement.fusedDiameter, 0.0)
        assertTrue(newerA.measurement.diameterB.isNaN())
        assertEquals(MeasurementStatus.CAMERA_B_FAILURE, newerA.measurement.status)
        assertEquals(1_500L, newerA.measurement.timestamp)
        assertEquals(setOf("A"), newerA.activeCameraIds)
        assertEquals(false, newerA.timestampsCompatible)
        assertEquals(FusionReason.TIMESTAMP_MISMATCH, newerA.reason)
    }

    @Test
    fun zeroWeightsUseArithmeticAverage() {
        // Catches division by zero or unintended preference for one camera.
        val result = engine.fuseDetailed(
            m("A", 1.7, 0.9, 1_000),
            m("B", 1.8, 0.8, 1_050),
            profile.copy(cameraAWeight = 0.0, cameraBWeight = 0.0),
        )!!
        assertEquals(1.75, result.measurement.fusedDiameter, 0.000_000_000_001)
        assertEquals(0.1, result.measurement.shapeDifference, 0.000_000_000_001)
        assertEquals(0.85, result.measurement.confidence, 0.000_000_000_001)
        assertEquals(FusionReason.BOTH_CAMERAS, result.reason)
    }

    private fun m(id: String, diameter: Double, confidence: Double, timestamp: Long) = CameraMeasurement(
        cameraId = id,
        pixelWidth = diameter * 100.0,
        diameterMm = diameter,
        confidence = confidence,
        timestamp = timestamp,
        valid = true,
    )
}
