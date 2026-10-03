package com.filamentvision.core.vision

import com.filamentvision.core.config.*
import com.filamentvision.core.frame.*
import java.util.Properties
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class VisionGoldenTest(private val compact: Boolean) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "compact={0}")
        fun processors() = listOf(arrayOf(false), arrayOf(true))
    }
    @Test
    fun darkStripCatchesPolarityWidthCalibrationMetadataAndTimingDrift() {
        val fixture = Properties().apply {
            requireNotNull(VisionGoldenTest::class.java.classLoader.getResourceAsStream("characterization/vision-dark-strip-v1.properties")).use(::load)
        }
        fun int(key: String) = fixture.getProperty(key).toInt()
        fun long(key: String) = fixture.getProperty(key).toLong()
        fun double(key: String) = fixture.getProperty(key).toDouble()
        val frame = frame(int("width"), int("height"), IntArray(32) {
            argb(if (it % 8 in int("stripLeft") until int("stripRightExclusive")) int("filament") else int("background"))
        })
        val configuration = configuration().copy(calibration = calibration().copy(
            manualThreshold = int("manualThreshold"), edgeSensitivity = int("edgeSensitivity"),
            mmPerPixel = double("mmPerPixel"),
        ))
        val result = processor().process(frame, configuration, decodeNanos = 7)
        assertEquals(CameraId("A"), result.cameraId)
        assertEquals(long("expectedTimestamp"), result.timestampMillis)
        assertEquals(long("expectedSequence"), result.sequence)
        assertEquals(8, result.processedWidth)
        assertEquals(4, result.processedHeight)
        assertEquals(int("expectedEffectiveThreshold"), result.effectiveThreshold)
        assertArrayEquals(rows(100, 100, 20, 20, 20, 20, 100, 100).map(::argb).toIntArray(), values(result.rawStage.pixels))
        assertArrayEquals(rows(100, 100, 20, 20, 20, 20, 100, 100), values(result.grayscaleStage.intensities))
        assertArrayEquals(rows(255, 255, 0, 0, 0, 0, 255, 255), values(result.thresholdStage.intensities))
        assertArrayEquals(rows(0, 255, 255, 0, 0, 255, 255, 0), values(result.edgeStage.intensities))
        val measurement = requireNotNull(result.measurement)
        assertEquals(CameraId("A"), measurement.cameraId)
        assertEquals(99L, measurement.timestamp)
        assertEquals(3L, measurement.sequence)
        assertEquals(double("expectedPixelWidth"), requireNotNull(measurement.pixelWidth), 0.0)
        assertEquals(double("expectedDiameterMm"), measurement.diameterMm, 0.0)
        assertEquals(double("expectedConfidence"), measurement.confidence, 0.0)
        assertTrue(measurement.valid)
        assertEquals(DetectionOverlay(0, 0, 8, 4, 2, 6), result.overlay)
        assertEquals(2, measurement.leftEdge)
        assertEquals(6, measurement.rightEdge)
        assertNull(measurement.error)
        assertNull(result.error)
        assertEquals(VisionStageTimings(7, 10, 10, 10, 10, 10, 10, 137), result.timings)
    }

    @Test
    fun automaticThresholdCatchesManualFallbackAndOtsuOffByOne() {
        val result = processor().process(
            frame(pixels = rows(200, 200, 20, 20, 20, 20, 200, 200).map(::argb).toIntArray()),
            configuration().copy(calibration = calibration().copy(thresholdMode = ThresholdMode.AUTOMATIC)),
        )
        assertEquals(21, result.effectiveThreshold)
        assertEquals(4.0, requireNotNull(requireNotNull(result.measurement).pixelWidth), 0.0)
        assertNull(result.error)
    }

    @Test
    fun uniformFrameCatchesEmptyRowCrashAndStaleMeasurementOrEdges() {
        val processor = processor()
        processor.process(frame(), configuration())
        val result = processor.process(frame(pixels = IntArray(32) { argb(100) }), configuration())
        assertEquals(DetectionError.NO_EDGES, result.error)
        assertNull(result.measurement)
        assertEquals(DetectionOverlay(0, 0, 8, 4, null, null), result.overlay)
    }

    @Test
    fun geometryCatchesIgnoredRotationOrMirrorAndProcessingBeforeGeometry() {
        val config = configuration().copy(
            geometry = ImageGeometry(rotationDegrees = 90, mirrorVertically = true),
            calibration = calibration().copy(roiRight = 4, roiBottom = 8, minimumPixelWidth = 1, maximumPixelWidth = 4),
        )
        val result = processor().process(frame(), config)
        assertEquals(4, result.processedWidth)
        assertEquals(8, result.processedHeight)
        assertEquals(4, result.rawStage.width)
        assertEquals(8, result.rawStage.height)
        listOf(result.grayscaleStage, result.thresholdStage, result.edgeStage).forEach {
            assertEquals(4, it.width)
            assertEquals(8, it.height)
        }
        assertArrayEquals(intArrayOf(
            100,100,100,100, 100,100,100,100, 20,20,20,20, 20,20,20,20,
            20,20,20,20, 20,20,20,20, 100,100,100,100, 100,100,100,100,
        ), values(result.grayscaleStage.intensities))
        // Asymmetry catches mirror-after-rotation, which a uniform strip cannot expose.
        val transformed = ImageGeometryTransformer.transform(frame(2, 3, intArrayOf(1,2,3,4,5,6)), config.geometry)
        assertEquals(3, transformed.width)
        assertEquals(2, transformed.height)
        assertArrayEquals(intArrayOf(1,3,5,2,4,6), values(transformed.pixels))
    }

    @Test
    fun invalidCalibrationCatchesPublishingUncalibratedOrInvalidWidthMeasurements() {
        listOf(
            calibration().copy(mmPerPixel = 0.0), calibration().copy(mmPerPixel = -0.1),
            calibration().copy(minimumPixelWidth = 0), calibration().copy(minimumPixelWidth = 7),
        ).forEach { calibration ->
            val result = processor().process(frame(), VisionConfiguration(calibration))
            assertEquals(DetectionError.INVALID_CALIBRATION, result.error)
            assertNull(result.measurement)
            assertEquals(DetectionOverlay(0, 0, 8, 4, null, null), result.overlay)
        }
    }

    @Test
    fun invalidRoiCatchesBoundsCrashOrSilentlyAcceptedEmptyRegion() {
        listOf(
            calibration().copy(roiLeft = -1), calibration().copy(roiTop = -1),
            calibration().copy(roiRight = 9), calibration().copy(roiBottom = 5),
            calibration().copy(roiRight = 0), calibration().copy(roiBottom = 0),
        ).forEach { calibration ->
            val result = processor().process(frame(), VisionConfiguration(calibration))
            assertEquals(DetectionError.INVALID_ROI, result.error)
            assertNull(result.measurement)
            assertEquals(DetectionOverlay(calibration.roiLeft, calibration.roiTop, calibration.roiRight, calibration.roiBottom, null, null), result.overlay)
        }
    }

    @Test
    fun darkRejectedSegmentsCatchNoEdgesMisclassification() {
        listOf(
            calibration().copy(minimumPixelWidth = 5),
            calibration().copy(maximumPixelWidth = 3),
            calibration().copy(edgeSensitivity = 81),
            calibration().copy(roiLeft = 2),
        ).forEach { calibration ->
            val result = processor().process(frame(), VisionConfiguration(calibration))
            assertEquals(DetectionError.WIDTH_OUT_OF_RANGE, result.error)
            assertNull(result.measurement)
            assertNull(result.overlay.leftEdge)
            assertNull(result.overlay.rightEdge)
        }
    }

    @Test
    fun lowConfidenceCatchesPublishingRejectedRowsOrDroppingTheirOverlay() {
        val pixels = intArrayOf(
            100,100,20,20,20,20,100,100, 100,100,20,20,20,20,100,100,
            100,100,100,100,100,100,100,100, 100,100,100,100,100,100,100,100,
        ).map(::argb).toIntArray()
        val result = processor().process(frame(pixels = pixels), configuration().copy(calibration = calibration().copy(minimumConfidence = 0.51)))
        assertEquals(DetectionError.LOW_CONFIDENCE, result.error)
        assertNull(result.measurement)
        assertEquals(DetectionOverlay(0, 0, 8, 4, 2, 6), result.overlay)
        val accepted = processor().process(frame(pixels = pixels), configuration())
        assertEquals(0.5, requireNotNull(accepted.measurement).confidence, 0.0)
        assertNull(accepted.error)
    }

    @Test
    fun sourceNormalizationBeforeConfiguredGeometryCatchesReversedCompositionOrLatePreprocessing() {
        val input = CameraFrame(
            CameraId("A"), 99, 100, 2, 3, PixelFormat.ARGB32,
            PixelBuffer.ints(intArrayOf(10,100,20,200,30,250).map(::argb).toIntArray()),
            90, true, 3,
        )
        val config = configuration().copy(
            geometry = ImageGeometry(mirrorHorizontally = true),
            calibration = calibration().copy(roiRight = 3, roiBottom = 2),
        )
        val result = processor().process(input, config)
        // Source horizontal mirror, then 90 degrees: [250,200,100; 30,20,10].
        // Configured horizontal mirror in that normalized coordinate space follows.
        assertArrayEquals(intArrayOf(100,200,250,10,20,30).map(::argb).toIntArray(), values(result.rawStage.pixels))
        assertArrayEquals(intArrayOf(100,200,250,10,20,30), values(result.grayscaleStage.intensities))
        assertArrayEquals(intArrayOf(255,255,255,0,0,0), values(result.thresholdStage.intensities))
        assertArrayEquals(intArrayOf(0,0,0,0,0,0), values(result.edgeStage.intensities))
        assertEquals(3, result.processedWidth)
        assertEquals(2, result.processedHeight)
        assertEquals(3, result.rawStage.width)
        assertEquals(2, result.rawStage.height)
        listOf(result.grayscaleStage, result.thresholdStage, result.edgeStage).forEach {
            assertEquals(3, it.width)
            assertEquals(2, it.height)
        }
        assertEquals(CameraId("A"), result.cameraId)
        assertEquals(99L, result.timestampMillis)
        assertEquals(3L, result.sequence)
    }

    private fun processor(): VisionAnalyzer {
        var now = 0L
        val clock = NanoClock { now.also { now += 10 } }
        return if (compact) RoiVisionProcessor(clock = clock) else VisionProcessor(clock = clock)
    }
    private fun configuration() = VisionConfiguration(calibration = calibration())
    private fun calibration() = CalibrationProfile(
        roiRight = 8, roiBottom = 4, grayscaleMethod = GrayscaleMethod.AVERAGE,
        manualThreshold = 40, edgeSensitivity = 30, minimumPixelWidth = 3,
        maximumPixelWidth = 6, mmPerPixel = 0.1, minimumConfidence = 0.5,
    )
    private fun frame(width: Int = 8, height: Int = 4, pixels: IntArray = rows(100, 100, 20, 20, 20, 20, 100, 100).map(::argb).toIntArray()) = CameraFrame(
        CameraId("A"), 99, 100, width, height, PixelFormat.ARGB32, PixelBuffer.ints(pixels), 0, false, 3,
    )
    private fun rows(vararg row: Int) = IntArray(row.size * 4) { row[it % row.size] }
    private fun values(buffer: PixelBuffer) = IntArray(buffer.count) { buffer[it] }
    private fun argb(value: Int) = 0xFF000000.toInt() or (value shl 16) or (value shl 8) or value
}
