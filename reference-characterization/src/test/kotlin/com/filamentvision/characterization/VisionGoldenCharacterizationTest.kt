package com.filamentvision.characterization

import com.filamentvision.camera.model.ArgbFrame
import com.filamentvision.model.CalibrationProfile
import com.filamentvision.model.CameraProfile
import com.filamentvision.model.GrayscaleMethod
import com.filamentvision.vision.processing.VisionProcessor
import java.util.Properties
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VisionGoldenCharacterizationTest {
    @Test
    fun darkStripPreservesEveryPreviewStageAndMeasurementMetadata() {
        // Catches threshold/edge polarity, width, calibration, and metadata drift.
        val fixture = Properties().apply {
            requireNotNull(VisionGoldenCharacterizationTest::class.java.classLoader.getResourceAsStream("characterization/vision-dark-strip-v1.properties"))
                .use(::load)
        }
        val width = fixture.int("width")
        val height = fixture.int("height")
        val background = fixture.int("background")
        val filament = fixture.int("filament")
        val stripLeft = fixture.int("stripLeft")
        val stripRightExclusive = fixture.int("stripRightExclusive")
        val frame = ArgbFrame(
            sourceId = "A",
            timestampMillis = fixture.long("expectedTimestamp"),
            width = width,
            height = height,
            pixels = IntArray(width * height) { index ->
                argb(if (index % width in stripLeft until stripRightExclusive) filament else background)
            },
            sequence = fixture.long("expectedSequence"),
        )
        val camera = CameraProfile(
            cameraId = "A",
            endpointId = "device",
            calibration = CalibrationProfile(
                roiLeft = 0,
                roiTop = 0,
                roiRight = width,
                roiBottom = height,
                grayscaleMethod = GrayscaleMethod.AVERAGE,
                manualThreshold = fixture.int("manualThreshold"),
                edgeSensitivity = fixture.int("edgeSensitivity"),
                minimumPixelWidth = 3,
                maximumPixelWidth = 6,
                mmPerPixel = fixture.double("mmPerPixel"),
                minimumConfidence = 0.5,
            ),
        )

        val result = VisionProcessor().process(frame, camera)
        assertNotNull(result.measurement)
        val measurement = result.measurement!!

        assertEquals("A", result.cameraId)
        assertEquals(fixture.long("expectedTimestamp"), result.timestampMillis)
        assertEquals(fixture.long("expectedSequence"), result.sequence)
        assertEquals(width, result.processedWidth)
        assertEquals(height, result.processedHeight)
        assertEquals(fixture.int("expectedEffectiveThreshold"), result.effectiveThreshold)
        assertArrayEquals(rows(intArrayOf(100, 100, 20, 20, 20, 20, 100, 100), height), result.grayscalePreview.pixels)
        assertArrayEquals(rows(intArrayOf(255, 255, 0, 0, 0, 0, 255, 255), height), result.thresholdPreview.pixels)
        assertArrayEquals(rows(intArrayOf(0, 255, 255, 0, 0, 255, 255, 0), height), result.edgePreview.pixels)
        assertEquals("A", measurement.cameraId)
        assertEquals(fixture.long("expectedTimestamp"), measurement.timestamp)
        assertEquals(fixture.double("expectedPixelWidth"), measurement.pixelWidth, 0.0)
        assertEquals(fixture.double("expectedDiameterMm"), measurement.diameterMm, 0.0)
        assertEquals(fixture.double("expectedConfidence"), measurement.confidence, 0.0)
        assertTrue(measurement.valid)
        assertEquals(0, result.overlay.roiLeft)
        assertEquals(0, result.overlay.roiTop)
        assertEquals(8, result.overlay.roiRight)
        assertEquals(4, result.overlay.roiBottom)
        assertEquals(2, result.overlay.leftEdge)
        assertEquals(6, result.overlay.rightEdge)
        assertEquals(2, measurement.leftEdge)
        assertEquals(6, measurement.rightEdge)
        assertNull(measurement.error)
        assertNull(result.error)
    }

    private fun rows(row: IntArray, height: Int) = IntArray(row.size * height) { argb(row[it % row.size]) }
    private fun argb(value: Int) = 0xFF000000.toInt() or (value shl 16) or (value shl 8) or value
    private fun Properties.int(name: String) = getProperty(name).toInt()
    private fun Properties.long(name: String) = getProperty(name).toLong()
    private fun Properties.double(name: String) = getProperty(name).toDouble()
}
