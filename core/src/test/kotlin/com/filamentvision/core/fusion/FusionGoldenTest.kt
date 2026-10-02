package com.filamentvision.core.fusion

import com.filamentvision.core.frame.CameraId
import com.filamentvision.core.model.FusionConfiguration
import com.filamentvision.core.model.MeasurementStatus
import com.filamentvision.core.vision.CameraMeasurement
import com.filamentvision.core.vision.CameraMeasurementSource
import org.junit.Assert.*
import org.junit.Test

class FusionGoldenTest {
    private val profile = FusionConfiguration(0.25, 0.75, 0.7, 100)
    private val engine = FusionEngine()

    @Test fun weightedDualRetainsAcceptedEvidenceAndDiagnostics() {
        // Catches weighting, confidence averaging, timestamp selection and lost source metadata.
        val a = m("A", 1.7, .9, 1000, 31)
        val b = m("B", 1.8, .8, 1050, 42)
        val result = engine.fuseDetailed(a, b, profile)!!
        assertEquals(a, result.measurement.cameraA)
        assertEquals(b, result.measurement.cameraB)
        assertEquals(31L, result.measurement.cameraA!!.sequence)
        assertEquals(42L, result.measurement.cameraB!!.sequence)
        assertEquals(1.775, result.measurement.fusedDiameter, 1e-12)
        assertEquals(.85, result.measurement.confidence, 1e-12)
        assertEquals(.1, result.measurement.shapeDifference, 1e-12)
        assertEquals(1050L, result.measurement.timestamp)
        assertEquals(MeasurementStatus.NORMAL, result.measurement.status)
        assertEquals(setOf(CameraId("A"), CameraId("B")), result.activeCameraIds)
        assertEquals(.1, result.cameraDifferenceMm!!, 1e-12)
        assertTrue(result.timestampsCompatible)
        assertEquals(FusionReason.BOTH_CAMERAS, result.reason)
        assertEquals(result.reason, result.measurement.fusionReason)
        assertEquals(CameraMeasurementSource.VISION, result.measurement.cameraA!!.source)
        assertEquals(170.0, result.measurement.cameraA!!.pixelWidth!!, 0.0)
    }

    @Test fun missingSidesProjectToTheFrozenLegacyAbsenceValues() {
        // Catches swapped failure status, fabricated missing evidence and wrong fallback confidence.
        assertSingle(engine.fuseDetailed(m("A", 1.7, .9, 1000), null, profile)!!,
            true, 1.7, .9, 1000, FusionReason.CAMERA_B_MISSING, null, true)
        assertSingle(engine.fuseDetailed(null, m("B", 1.8, .8, 1050), profile)!!,
            false, 1.8, .8, 1050, FusionReason.CAMERA_A_MISSING, null, true)
        assertNull(engine.fuseDetailed(null, null, profile))
    }

    @Test fun lowConfidenceSidesAreAbsentButDiagnosticDifferenceSurvives() {
        // Catches accepting a rejected camera, wrong reason precedence and discarded raw difference.
        assertSingle(engine.fuseDetailed(m("A", 1.7, .2, 1000), m("B", 1.8, .8, 1000), profile)!!,
            false, 1.8, .8, 1000, FusionReason.CAMERA_A_LOW_CONFIDENCE, .1, true)
        assertSingle(engine.fuseDetailed(m("A", 1.7, .9, 1000), m("B", 1.8, .2, 1000), profile)!!,
            true, 1.7, .9, 1000, FusionReason.CAMERA_B_LOW_CONFIDENCE, .1, true)
        assertNull(engine.fuseDetailed(m("A", 1.7, .2, 1000), m("B", 1.8, .2, 1000), profile))
    }

    @Test fun mismatchedTimestampsSelectTheNewerValidSide() {
        // Catches stale fusion and either reversed timestamp branch.
        assertSingle(engine.fuseDetailed(m("A", 1.7, .9, 1000), m("B", 1.8, .9, 1500), profile)!!,
            false, 1.8, .9, 1500, FusionReason.TIMESTAMP_MISMATCH, .1, false)
        assertSingle(engine.fuseDetailed(m("A", 1.7, .9, 1500), m("B", 1.8, .9, 1000), profile)!!,
            true, 1.7, .9, 1500, FusionReason.TIMESTAMP_MISMATCH, .1, false)
    }

    @Test fun zeroAndNegativeWeightsPreserveArithmeticFallbackAndClamping() {
        // Catches zero division and removal of the baseline non-negative weight clamp.
        val a = m("A", 1.7, .9, 1000)
        val b = m("B", 1.8, .8, 1050)
        val zero = engine.fuseDetailed(a, b, profile.copy(cameraAWeight = 0.0, cameraBWeight = 0.0))!!
        assertEquals(1.75, zero.measurement.fusedDiameter, 1e-12)
        assertEquals(.85, zero.measurement.confidence, 1e-12)
        assertEquals(.1, zero.measurement.shapeDifference, 1e-12)
        assertEquals(FusionReason.BOTH_CAMERAS, zero.reason)
        assertEquals(1.8, engine.fuseDetailed(a, b, profile.copy(cameraAWeight = -1.0))!!.measurement.fusedDiameter, 1e-12)
    }

    @Test fun confidenceAndTimestampBoundariesAreInclusive() {
        // Catches > in place of >= confidence, or < in place of <= tolerance.
        val result = engine.fuseDetailed(m("A", 1.7, .7, 1000), m("B", 1.8, .7, 1100), profile)!!
        assertEquals(FusionReason.BOTH_CAMERAS, result.reason)
        assertTrue(result.timestampsCompatible)
        assertEquals(1.775, result.measurement.fusedDiameter, 1e-12)
    }

    @Test fun invalidFlagAndReasonPrecedenceMatchReference() {
        // Catches confidence-only validation and mismatch taking precedence over rejected input.
        val invalid = m("A", 1.7, .9, 1000).copy(valid = false)
        assertSingle(engine.fuseDetailed(invalid, m("B", 1.8, .8, 1500), profile)!!,
            false, 1.8, .8, 1500, FusionReason.CAMERA_A_LOW_CONFIDENCE, .1, false)
        assertNull(engine.fuseDetailed(invalid, null, profile))
    }

    private fun assertSingle(result: FusionResult, isA: Boolean, diameter: Double, confidence: Double,
        timestamp: Long, reason: FusionReason, difference: Double?, compatible: Boolean) {
        val official = result.measurement
        val accepted = if (isA) official.cameraA else official.cameraB
        val missing = if (isA) official.cameraB else official.cameraA
        assertNull(missing)
        assertEquals(diameter, accepted!!.diameterMm, 0.0)
        assertEquals(confidence, accepted.confidence, 0.0)
        assertEquals(7L, accepted.sequence)
        assertEquals(diameter, official.fusedDiameter, 0.0)
        assertEquals(confidence, official.confidence, 0.0)
        assertEquals(0.0, official.shapeDifference, 0.0)
        assertEquals(timestamp, official.timestamp)
        assertEquals(if (isA) MeasurementStatus.CAMERA_B_FAILURE else MeasurementStatus.CAMERA_A_FAILURE, official.status)
        assertEquals(setOf(CameraId(if (isA) "A" else "B")), result.activeCameraIds)
        assertEquals(reason, result.reason)
        assertEquals(reason, official.fusionReason)
        assertEquals(compatible, result.timestampsCompatible)
        if (difference == null) assertNull(result.cameraDifferenceMm)
        else assertEquals(difference, result.cameraDifferenceMm!!, 1e-12)
        // Compatibility boundary only: Core represents absence with null; Android used NaN/zero.
        val legacyMissingDiameter = missing?.diameterMm ?: Double.NaN
        val legacyMissingConfidence = missing?.confidence ?: 0.0
        val legacyDifference = result.cameraDifferenceMm ?: Double.NaN
        assertTrue(legacyMissingDiameter.isNaN())
        assertEquals(0.0, legacyMissingConfidence, 0.0)
        if (difference == null) assertTrue(legacyDifference.isNaN())
        else assertEquals(difference, legacyDifference, 1e-12)
    }

    private fun m(id: String, diameter: Double, confidence: Double, timestamp: Long, sequence: Long = 7) =
        CameraMeasurement(CameraId(id), diameter * 100, diameter, confidence, timestamp, sequence, true)
}
