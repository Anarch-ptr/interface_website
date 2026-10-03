package com.filamentvision.core.measurement

import com.filamentvision.core.frame.CameraId
import com.filamentvision.core.fusion.FusionReason
import com.filamentvision.core.model.MeasurementStatus
import com.filamentvision.core.model.OfficialMeasurement
import com.filamentvision.core.vision.CameraMeasurement
import com.filamentvision.core.vision.CameraMeasurementSource
import com.filamentvision.core.vision.DetectionError
import org.junit.Assert.*
import org.junit.Test

class SingleCameraMeasurementAssemblerTest {
    private val assembler: MeasurementAssembler = SingleCameraMeasurementAssembler()

    @Test fun validVisionEvidencePreservesExactMeasurementAndProvenance() {
        // Catches altered diameter/confidence, timestamp substitution and dropped evidence fields.
        val input = evidence().also { it.receivedAtMonotonicNanos = 987654321L }
        val official = assembler.accept(input).single()
        assertEquals(1.2345, official.diameterMm, 0.0)
        assertEquals(.63, official.confidence, 0.0)
        assertEquals(123456L, official.timestamp)
        assertEquals(MeasurementStatus.NORMAL, official.status)
        assertEquals(1, official.evidence.size)
        assertSame(input, official.evidence.single())
        with(official.evidence.single()) {
            assertEquals(CameraId("camera"), cameraId)
            assertEquals(37.5, pixelWidth!!, 0.0)
            assertEquals(42L, sequence)
            assertEquals(987654321L, receivedAtMonotonicNanos)
            assertEquals(listOf(10, 20, 110, 220, 30, 68), listOf(roiLeft, roiTop, roiRight, roiBottom, leftEdge, rightEdge))
            assertEquals(CameraMeasurementSource.VISION, source)
        }
    }

    @Test fun productionOutputHasNoCameraSlotsOrFusionMetadata() {
        // Catches routing the source into legacy A, or inventing a missing B/difference/reason.
        val official = assembler.accept(evidence()).single()
        assertNull(official.legacyFusion)
        assertNull(official.cameraA)
        assertNull(official.cameraB)
        assertThrows(IllegalStateException::class.java) { official.shapeDifference }
        assertThrows(IllegalStateException::class.java) { official.fusionReason }
        assertEquals(1.2345, official.fusedDiameter, 0.0)
    }

    @Test fun absentPixelWidthIsNotInvented() {
        val official = assembler.accept(evidence().copy(pixelWidth = null)).single()
        assertNull(official.evidence.single().pixelWidth)
        assertEquals(.63, official.confidence, 0.0)
    }

    @Test fun invalidAndNoEdgeEvidenceEmitNoOfficialSample() {
        // Catches confidence-only acceptance and accidental acceptance of errored Vision evidence.
        assertTrue(assembler.accept(evidence().copy(valid = false)).isEmpty())
        assertTrue(assembler.accept(evidence().copy(valid = false, error = DetectionError.NO_EDGES)).isEmpty())
        assertTrue(assembler.accept(evidence().copy(error = DetectionError.NO_EDGES)).isEmpty())
        assertEquals(1, assembler.accept(evidence()).size)
    }

    @Test fun wrongSourceIdentityIsRejectedDeterministically() {
        // Catches allowing obsolete A/B identities or changing state after rejected input.
        listOf("A", "B", "other").forEach { id ->
            repeat(2) {
                assertThrows(IllegalArgumentException::class.java) {
                    assembler.accept(evidence().copy(cameraId = CameraId(id)))
                }
            }
        }
        assertEquals(1, assembler.accept(evidence()).size)
    }

    @Test fun directDiameterEvidenceCannotEnterTheVisionBoundary() {
        assertTrue(assembler.accept(evidence().copy(source = CameraMeasurementSource.DIRECT_DIAMETER)).isEmpty())
    }

    @Test fun repeatedAcceptAndResetAreStatelessAndDeterministic() {
        // Catches retained pairing state, duplicate suppression or reset-dependent outputs.
        val input = evidence()
        val first = assembler.accept(input)
        assertEquals(first, assembler.accept(input))
        assembler.reset()
        assembler.reset()
        assertEquals(first, assembler.accept(input))
        assertEquals(listOf(42L, 43L), (first + assembler.accept(input.copy(sequence = 43))).map { it.evidence.single().sequence })
    }

    @Test fun legacyConstructorAndCopyRetainOnlyActualAcceptedEvidence() {
        // Catches stale neutral evidence when legacy persistence canonicalizes/copies camera slots.
        val a = evidence().copy(cameraId = CameraId("A"))
        val b = evidence().copy(cameraId = CameraId("B"), diameterMm = 1.5)
        val legacy = OfficialMeasurement(a, b, 1.4, .2655, .63, MeasurementStatus.NORMAL, 123456, FusionReason.BOTH_CAMERAS)
        assertEquals(listOf(a, b), legacy.evidence)
        assertEquals(1.4, legacy.diameterMm, 0.0)
        assertEquals(.2655, legacy.shapeDifference, 0.0)
        assertEquals(FusionReason.BOTH_CAMERAS, legacy.fusionReason)
        val copied = legacy.copy(cameraB = null, status = MeasurementStatus.CAMERA_B_FAILURE, fusionReason = FusionReason.CAMERA_B_MISSING)
        assertEquals(listOf(a), copied.evidence)
        assertNull(copied.cameraB)
        assertEquals(FusionReason.CAMERA_B_MISSING, copied.fusionReason)
        assertEquals(.2, legacy.copy(confidence = .2).confidence, 0.0)
    }

    private fun evidence() = CameraMeasurement(
        CameraId("camera"), 37.5, 1.2345, .63, 123456, 42, true,
        roiLeft = 10, roiTop = 20, roiRight = 110, roiBottom = 220, leftEdge = 30, rightEdge = 68,
    )
}
