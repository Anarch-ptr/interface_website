package com.filamentvision.core.vision

import com.filamentvision.core.config.*
import com.filamentvision.core.frame.*
import org.junit.Assert.*
import org.junit.Test

class RoiVisionProcessorTest {
    @Test
    fun eightMegapixelInputRetainsOnlyFourRoiBuffersWithEquivalentEvidence() {
        val frame = frame(3840, 2160) { x, y -> argb(if (x in 1150..1269 && y % 7 != 0) 20 else 200) }
        val config = VisionConfiguration(calibration(1000, 700, 1640, 1180))
        val allocations = mutableListOf<Int>()
        val actual = RoiVisionProcessor(allocationObserver = allocations::add).process(frame, config, 7)
        val expected = VisionProcessor().process(frame, config, 7)
        assertEquivalent(expected, actual, config.calibration)
        assertEquals(listOf(307200, 307200, 307200, 307200), allocations)
        assertEquals(4L * 3840 * 2160 * 4, retainedBytes(expected))
        assertEquals(4L * 640 * 480 * 4, retainedBytes(actual))
        assertEquals(12.0, requireNotNull(actual.measurement).diameterMm, 0.0)
        assertEquals(1150, actual.overlay.leftEdge)
        assertEquals(1270, actual.overlay.rightEdge)
    }

    @Test
    fun directExtractionMatchesEveryRotationMirrorAndSourceNormalizationComposition() {
        for (orientation in listOf(0, 90, 180, 270)) for (sourceMirror in listOf(false, true)) {
            val frame = frame(11, 9, orientation, sourceMirror) { x, y ->
                0xFF000000.toInt() or (((x * 71 + y * 19) % 256) shl 16) or
                    (((x * 37 + y * 53) % 256) shl 8) or ((x * 11 + y * 97) % 256)
            }
            for (rotation in listOf(0, 90, 180, 270)) for (horizontal in listOf(false, true)) {
                for (vertical in listOf(false, true)) for (method in GrayscaleMethod.entries) {
                    for (thresholdMode in ThresholdMode.entries) {
                        val config = VisionConfiguration(calibration(1, 2, 8, 7).copy(
                            grayscaleMethod = method, thresholdMode = thresholdMode,
                            minimumPixelWidth = 1, maximumPixelWidth = 5, minimumConfidence = 0.0,
                        ), ImageGeometry(rotation, horizontal, vertical))
                        assertEquivalent(VisionProcessor().process(frame, config), RoiVisionProcessor().process(frame, config), config.calibration)
                    }
                }
            }
        }
    }

    @Test
    fun boundaryNeighborEdgesRemainEquivalentWithoutHaloAllocation() {
        // The outside transition creates the only qualifying left edge at the ROI boundary.
        val frame = frame(6, 2) { x, _ -> argb(listOf(0, 41, 39, 20, 100, 200)[x]) }
        val config = VisionConfiguration(calibration(1, 0, 5, 2).copy(manualThreshold = 40, edgeSensitivity = 30))
        val actual = RoiVisionProcessor().process(frame, config)
        assertEquivalent(VisionProcessor().process(frame, config), actual, config.calibration)
        assertEquals(2, actual.overlay.leftEdge)
        assertEquals(4, actual.overlay.rightEdge)
    }

    @Test
    fun invalidAndOverflowingTransformedRoisRejectBeforeWorkingAllocation() {
        val frame = frame(8, 4) { _, _ -> argb(100) }
        val allocations = mutableListOf<Int>()
        val processor = RoiVisionProcessor(allocationObserver = allocations::add)
        for (roi in listOf(
            calibration(-1, 0, 4, 8), calibration(0, -1, 4, 8),
            calibration(0, 0, 5, 8), calibration(0, 0, 4, 9),
            calibration(2, 0, 2, 8), calibration(0, 3, 4, 2),
            calibration(Int.MIN_VALUE, 0, Int.MAX_VALUE, 8),
            calibration(0, 0, Int.MAX_VALUE, Int.MAX_VALUE),
        )) {
            val config = VisionConfiguration(roi, ImageGeometry(rotationDegrees = 90))
            val actual = processor.process(frame, config)
            assertEquals(DetectionError.INVALID_ROI, actual.error)
            assertEquals(VisionProcessor().process(frame, config).overlay, actual.overlay)
            assertNull(actual.measurement)
            assertEquals(0L, retainedBytes(actual))
            assertEquals(4, actual.processedWidth)
            assertEquals(8, actual.processedHeight)
        }
        assertTrue(allocations.isEmpty())
    }

    @Test
    fun invalidCalibrationNoEdgesLowConfidenceAndRejectedWidthsMatchReference() {
        val frame = frame(10, 6) { x, y -> argb(if (x in 3..5 && y in 1..2) 20 else 100) }
        val base = calibration(1, 1, 9, 5)
        for (roi in listOf(base, base.copy(minimumConfidence = 0.51), base.copy(mmPerPixel = 0.0),
            base.copy(minimumPixelWidth = 0), base.copy(minimumPixelWidth = 4),
            base.copy(maximumPixelWidth = 2), base.copy(edgeSensitivity = 81),
            base.copy(roiTop = 3), base.copy(roiRight = 2), base.copy(roiBottom = 2),
        )) {
            val config = VisionConfiguration(roi)
            assertEquivalent(VisionProcessor().process(frame, config), RoiVisionProcessor().process(frame, config), roi)
        }
    }

    @Test
    fun resultOwnsItsPixelsAndPreservesSourceClockAfterLeaseCloses() {
        val pixels = IntArray(32) { argb(if (it % 8 in 2..5) 20 else 100) }
        val frame = CameraFrame(CameraId("camera"), 99, 123456789, 8, 4, PixelFormat.ARGB32,
            PixelBuffer.ints(pixels), 0, false, 3)
        val analyzer: VisionAnalyzer = RoiVisionProcessor()
        val result = analyzer.process(frame, VisionConfiguration(calibration(0, 0, 8, 4)))
        frame.close()
        pixels.fill(0)
        assertEquals(argb(20), result.rawStage.pixels[2])
        assertEquals(123456789, requireNotNull(result.measurement).receivedAtMonotonicNanos)
    }

    private fun assertEquivalent(expected: VisionResult, actual: VisionResult, roi: CalibrationProfile) {
        assertEquals(expected.cameraId, actual.cameraId)
        assertEquals(expected.timestampMillis, actual.timestampMillis)
        assertEquals(expected.sequence, actual.sequence)
        assertEquals(expected.processedWidth, actual.processedWidth)
        assertEquals(expected.processedHeight, actual.processedHeight)
        assertEquals(expected.measurement, actual.measurement)
        assertEquals(expected.measurement?.receivedAtMonotonicNanos, actual.measurement?.receivedAtMonotonicNanos)
        assertEquals(expected.overlay, actual.overlay)
        assertEquals(expected.error, actual.error)
        assertEquals(expected.effectiveThreshold, actual.effectiveThreshold)
        assertEquals(expected.timings.decodeNanos, actual.timings.decodeNanos)
        val width = roi.roiRight - roi.roiLeft
        val height = roi.roiBottom - roi.roiTop
        val full = listOf(expected.rawStage.pixels, expected.grayscaleStage.intensities, expected.thresholdStage.intensities, expected.edgeStage.intensities)
        val compact = listOf(actual.rawStage.pixels, actual.grayscaleStage.intensities, actual.thresholdStage.intensities, actual.edgeStage.intensities)
        assertEquals(width, actual.rawStage.width)
        assertEquals(height, actual.rawStage.height)
        listOf(actual.grayscaleStage, actual.thresholdStage, actual.edgeStage).forEach {
            assertEquals(width, it.width)
            assertEquals(height, it.height)
        }
        for (stage in full.indices) {
            assertEquals(width * height, compact[stage].count)
            for (y in 0 until height) for (x in 0 until width) {
                assertEquals("stage=$stage x=$x y=$y", full[stage][(y + roi.roiTop) * expected.processedWidth + x + roi.roiLeft], compact[stage][y * width + x])
            }
        }
    }

    private fun retainedBytes(result: VisionResult) = 4L * (result.rawStage.pixels.count.toLong() +
        result.grayscaleStage.intensities.count + result.thresholdStage.intensities.count + result.edgeStage.intensities.count)
    private fun calibration(left: Int, top: Int, right: Int, bottom: Int) = CalibrationProfile(
        roiLeft = left, roiTop = top, roiRight = right, roiBottom = bottom,
        manualThreshold = 80, edgeSensitivity = 30, minimumPixelWidth = 1,
        maximumPixelWidth = 640, mmPerPixel = 0.1, minimumConfidence = 0.5,
    )
    private fun frame(width: Int, height: Int, orientation: Int = 0, mirror: Boolean = false, pixel: (Int, Int) -> Int) = CameraFrame(
        CameraId("camera"), 99, 123456789, width, height, PixelFormat.ARGB32,
        PixelBuffer.ints(IntArray(width * height) { pixel(it % width, it / width) }), orientation, mirror, 3,
    )
    private fun argb(value: Int) = 0xFF000000.toInt() or (value shl 16) or (value shl 8) or value
}
