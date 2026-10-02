package com.filamentvision.core.fusion

import com.filamentvision.core.frame.CameraId
import com.filamentvision.core.input.DirectDiameterEncoding
import com.filamentvision.core.input.DirectDiameterEvidence
import com.filamentvision.core.input.DirectDiameterUnit
import com.filamentvision.core.vision.CameraMeasurementSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DirectDiameterMeasurementAdapterTest {
    @Test
    fun preservesEvidenceAndDoesNotFabricateImageMetadataOrDeviceConfidence() {
        val evidence = DirectDiameterEvidence(
            cameraId = CameraId("A"),
            rawValue = 1750.0,
            encoding = DirectDiameterEncoding.FLOAT32,
            unit = DirectDiameterUnit.MICROMETER,
            normalizedDiameterMm = 1.75,
            deviceConfidence = null,
            receivedAtEpochMillis = 100,
            receivedAtMonotonicNanos = 200,
            sourceTimestampMicros = 300,
            sequence = 4,
        )
        val measurement = DirectDiameterMeasurementAdapter.toMeasurement(evidence, defaultFusionConfidence = 0.8)

        assertEquals(1.75, measurement.diameterMm, 0.0)
        assertNull(measurement.pixelWidth)
        assertEquals(0.8, measurement.confidence, 0.0)
        assertEquals(CameraMeasurementSource.DIRECT_DIAMETER, measurement.source)
        assertEquals(4, measurement.sequence)
        assertNull(evidence.deviceConfidence)
        assertEquals(200, evidence.receivedAtMonotonicNanos)
        assertEquals(300L, evidence.sourceTimestampMicros)
    }

    @Test
    fun deviceConfidenceOverridesConfiguredFusionDefaultWhenPresent() {
        val evidence = DirectDiameterEvidence(
            CameraId("B"), 1.8, DirectDiameterEncoding.ASCII, DirectDiameterUnit.MM,
            1.8, 0.65, 10, 20, null, 7,
        )
        assertEquals(0.65, DirectDiameterMeasurementAdapter.toMeasurement(evidence, 0.9).confidence, 0.0)
    }
}
