package com.filamentvision.core.vision

import com.filamentvision.core.config.GrayscaleMethod
import com.filamentvision.core.config.ImageGeometry
import com.filamentvision.core.config.ThresholdMode
import com.filamentvision.core.config.VisionConfiguration
import com.filamentvision.core.frame.CameraFrame
import com.filamentvision.core.frame.PixelBuffer
import kotlin.math.abs

/**
 * Owns four ROI-sized stage buffers. Stage coordinates are local to [VisionResult.overlay]'s ROI;
 * processed dimensions, overlay and measurement coordinates remain in transformed-image space.
 * The input and its lease are borrowed only for the duration of process.
 * [allocationObserver] reports element counts for the four retained stage arrays, not heap usage.
 */
class RoiVisionProcessor(
    private val clock: NanoClock = NanoClock.SYSTEM,
    private val allocationObserver: (Int) -> Unit = {},
) : VisionAnalyzer {
    override fun process(frame: CameraFrame, configuration: VisionConfiguration, decodeNanos: Long): VisionResult {
        val totalStart = clock.nanoTime()
        val geometryStart = clock.nanoTime()
        val mapping = RoiMapping(frame, configuration.geometry)
        val calibration = configuration.calibration
        // Widen subtraction before validating: hostile endpoints must not wrap to a small valid ROI.
        val widthLong = calibration.roiRight.toLong() - calibration.roiLeft
        val heightLong = calibration.roiBottom.toLong() - calibration.roiTop
        if (calibration.roiLeft < 0 || calibration.roiTop < 0 ||
            calibration.roiRight > mapping.width || calibration.roiBottom > mapping.height ||
            widthLong <= 0 || heightLong <= 0 || widthLong * heightLong > Int.MAX_VALUE
        ) {
            val error = if (calibration.mmPerPixel <= 0.0 || calibration.minimumPixelWidth <= 0 ||
                calibration.minimumPixelWidth > calibration.maximumPixelWidth
            ) DetectionError.INVALID_CALIBRATION else DetectionError.INVALID_ROI
            // Invalid ROIs have no diagnostic stages. Automatic threshold is unavailable (0).
            return VisionResult(frame.cameraId, frame.capturedAtEpochMillis, frame.sequence, mapping.width, mapping.height,
                ArgbStage(0, 0, EMPTY), IntensityFrame(0, 0, EMPTY), IntensityFrame(0, 0, EMPTY), IntensityFrame(0, 0, EMPTY),
                DetectionOverlay(calibration.roiLeft, calibration.roiTop, calibration.roiRight, calibration.roiBottom, null, null),
                null, if (calibration.thresholdMode == ThresholdMode.MANUAL) calibration.manualThreshold.coerceIn(0, 255) else 0,
                VisionStageTimings(decodeNanos, clock.nanoTime() - geometryStart, 0, 0, 0, 0, 0,
                    decodeNanos + clock.nanoTime() - totalStart), error)
        }
        val width = widthLong.toInt()
        val height = heightLong.toInt()
        val count = Math.multiplyExact(width, height)
        val local = calibration.copy(roiLeft = 0, roiTop = 0, roiRight = width, roiBottom = height)
        allocationObserver(count)
        val raw = IntArray(count) { index -> mapping.pixel(index % width + calibration.roiLeft, index / width + calibration.roiTop) }
        val transformed = ArgbStage(width, height, PixelBuffer.ints(raw))
        val geometryNanos = clock.nanoTime() - geometryStart
        val grayscaleStart = clock.nanoTime()
        allocationObserver(count)
        val grayscale = GrayscaleProcessor().process(transformed, calibration.grayscaleMethod)
        val grayscaleNanos = clock.nanoTime() - grayscaleStart
        val thresholdStart = clock.nanoTime()
        allocationObserver(count)
        val threshold = ThresholdProcessor().process(grayscale, local)
        val thresholdNanos = clock.nanoTime() - thresholdStart
        val edgeStart = clock.nanoTime()
        allocationObserver(count)
        val sensitivity = calibration.edgeSensitivity.coerceIn(0, 255)
        val edgePixels = IntArray(count)
        // At the two ROI sides, read the original image neighbor directly. Its transition can
        // affect boundary-edge acceptance even when the filament itself lies strictly inside ROI.
        fun intensity(x: Int, y: Int): Int {
            if (x in 0 until width) return grayscale.intensities[y * width + x]
            val pixel = mapping.pixel(x + calibration.roiLeft, y + calibration.roiTop)
            val red = pixel ushr 16 and 255
            val green = pixel ushr 8 and 255
            val blue = pixel and 255
            return when (calibration.grayscaleMethod) {
                GrayscaleMethod.LUMA_BT601 -> (299 * red + 587 * green + 114 * blue + 500) / 1000
                GrayscaleMethod.AVERAGE -> (red + green + blue) / 3
            }
        }
        for (y in 0 until height) for (x in 0 until width) {
            val value = grayscale.intensities[y * width + x]
            val dark = value < threshold.effectiveThreshold
            fun transition(neighbor: Int) = (neighbor < threshold.effectiveThreshold) != dark && abs(value - neighbor) >= sensitivity
            val left = x + calibration.roiLeft > 0 && transition(intensity(x - 1, y))
            val right = x + calibration.roiLeft + 1 < mapping.width && transition(intensity(x + 1, y))
            edgePixels[y * width + x] = if (left || right) 255 else 0
        }
        val edges = IntensityFrame(width, height, PixelBuffer.ints(edgePixels))
        val edgeNanos = clock.nanoTime() - edgeStart
        val detectionStart = clock.nanoTime()
        val localDetection = DetectionPipeline().detect(grayscale, threshold.frame, edges, frame, local)
        val detection = localDetection.copy(
            roiLeft = calibration.roiLeft, roiTop = calibration.roiTop,
            roiRight = calibration.roiRight, roiBottom = calibration.roiBottom,
            leftEdge = localDetection.leftEdge?.plus(calibration.roiLeft),
            rightEdge = localDetection.rightEdge?.plus(calibration.roiLeft),
        ).also { it.receivedAtMonotonicNanos = frame.receivedAtMonotonicNanos }
        val detectionNanos = clock.nanoTime() - detectionStart
        val previewStart = clock.nanoTime()
        val overlay = DetectionOverlay(detection.roiLeft, detection.roiTop, detection.roiRight, detection.roiBottom, detection.leftEdge, detection.rightEdge)
        val previewNanos = clock.nanoTime() - previewStart
        val totalNanos = decodeNanos + (clock.nanoTime() - totalStart)
        return VisionResult(frame.cameraId, frame.capturedAtEpochMillis, frame.sequence, mapping.width, mapping.height,
            transformed, grayscale, threshold.frame, edges, overlay, detection.takeIf { it.valid }, threshold.effectiveThreshold,
            VisionStageTimings(decodeNanos, geometryNanos, grayscaleNanos, thresholdNanos, edgeNanos, detectionNanos, previewNanos, totalNanos), detection.error)
    }

    /** Reverse configured geometry first, then source normalization; neither step creates pixels. */
    private class RoiMapping(private val frame: CameraFrame, private val geometry: ImageGeometry) {
        private val sourceGeometry = ImageGeometry(frame.orientationDegrees, frame.mirrored)
        private val normalizedWidth = if (frame.orientationDegrees % 180 == 0) frame.width else frame.height
        private val normalizedHeight = if (frame.orientationDegrees % 180 == 0) frame.height else frame.width
        val width = if (geometry.rotationDegrees % 180 == 0) normalizedWidth else normalizedHeight
        val height = if (geometry.rotationDegrees % 180 == 0) normalizedHeight else normalizedWidth

        init { require(geometry.rotationDegrees in setOf(0, 90, 180, 270)) { "Rotation must be 0, 90, 180, or 270 degrees" } }

        fun pixel(x: Int, y: Int): Int {
            val normalized = inverseIndex(x, y, normalizedWidth, normalizedHeight, geometry)
            return frame.pixels[inverseIndex(normalized % normalizedWidth, normalized / normalizedWidth, frame.width, frame.height, sourceGeometry)]
        }

        private fun inverseIndex(x: Int, y: Int, inputWidth: Int, inputHeight: Int, transform: ImageGeometry): Int {
            val rotatedX = when (transform.rotationDegrees) { 0 -> x; 90 -> y; 180 -> inputWidth - 1 - x; else -> inputWidth - 1 - y }
            val rotatedY = when (transform.rotationDegrees) { 0 -> y; 90 -> inputHeight - 1 - x; 180 -> inputHeight - 1 - y; else -> x }
            val sourceX = if (transform.mirrorHorizontally) inputWidth - 1 - rotatedX else rotatedX
            val sourceY = if (transform.mirrorVertically) inputHeight - 1 - rotatedY else rotatedY
            return (sourceY.toLong() * inputWidth + sourceX).toInt()
        }
    }

    private companion object { val EMPTY = PixelBuffer.ints(IntArray(0)) }
}
