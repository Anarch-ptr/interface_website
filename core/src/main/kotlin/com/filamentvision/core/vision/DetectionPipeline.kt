package com.filamentvision.core.vision

import com.filamentvision.core.config.CalibrationProfile
import com.filamentvision.core.frame.CameraFrame
import com.filamentvision.core.frame.CameraId
import kotlin.math.abs
import kotlin.math.sqrt

enum class DetectionError { INVALID_CALIBRATION, INVALID_ROI, NO_EDGES, WIDTH_OUT_OF_RANGE, LOW_CONFIDENCE }
enum class CameraMeasurementSource { VISION, DIRECT_DIAMETER }

data class CameraMeasurement(
    val cameraId: CameraId,
    val pixelWidth: Double?,
    val diameterMm: Double,
    val confidence: Double,
    val timestamp: Long,
    val sequence: Long,
    val valid: Boolean,
    val roiLeft: Int = 0,
    val roiTop: Int = 0,
    val roiRight: Int = 0,
    val roiBottom: Int = 0,
    val leftEdge: Int? = null,
    val rightEdge: Int? = null,
    val error: DetectionError? = null,
    val source: CameraMeasurementSource = CameraMeasurementSource.VISION,
    val receivedAtMonotonicNanos: Long = timestamp * 1_000_000,
)

class DetectionPipeline {
    fun detect(
        grayscale: IntensityFrame, threshold: IntensityFrame, edges: IntensityFrame,
        frame: CameraFrame, calibration: CalibrationProfile,
    ): CameraMeasurement {
        listOf(threshold, edges).forEach {
            require(it.width == grayscale.width && it.height == grayscale.height)
            require(it.intensities.count >= grayscale.width * grayscale.height)
        }
        if (calibration.mmPerPixel <= 0.0 || calibration.minimumPixelWidth <= 0 ||
            calibration.minimumPixelWidth > calibration.maximumPixelWidth
        ) return invalid(frame, calibration, DetectionError.INVALID_CALIBRATION)
        if (calibration.roiLeft < 0 || calibration.roiTop < 0 ||
            calibration.roiRight > grayscale.width || calibration.roiBottom > grayscale.height ||
            calibration.roiRight <= calibration.roiLeft || calibration.roiBottom <= calibration.roiTop
        ) return invalid(frame, calibration, DetectionError.INVALID_ROI)
        val rows = mutableListOf<RowDetection>()
        var sawOutOfRangeSegment = false
        for (y in calibration.roiTop until calibration.roiBottom) {
            val row = detectRow(threshold, edges, y, calibration)
            if (row != null) rows += row
            else if ((calibration.roiLeft until calibration.roiRight).any { threshold.intensities[y * threshold.width + it] == 0 }) {
                sawOutOfRangeSegment = true
            }
        }
        if (rows.isEmpty()) {
            val error = if (sawOutOfRangeSegment) DetectionError.WIDTH_OUT_OF_RANGE else DetectionError.NO_EDGES
            return invalid(frame, calibration, error)
        }
        val widths = rows.map { it.width }.sorted()
        val width = median(widths)
        val mean = widths.average()
        val deviation = sqrt(widths.sumOf { (it - mean) * (it - mean) } / widths.size)
        val coverage = rows.size.toDouble() / (calibration.roiBottom - calibration.roiTop)
        val consistency = (1.0 - deviation / mean.coerceAtLeast(1.0)).coerceIn(0.0, 1.0)
        val confidence = (coverage * consistency).coerceIn(0.0, 1.0)
        val left = median(rows.map { it.left.toDouble() }.sorted()).toInt()
        val right = median(rows.map { it.right.toDouble() }.sorted()).toInt()
        val valid = confidence >= calibration.minimumConfidence
        return CameraMeasurement(
            frame.cameraId, width, width * calibration.mmPerPixel, confidence,
            frame.capturedAtEpochMillis, frame.sequence, valid,
            calibration.roiLeft, calibration.roiTop, calibration.roiRight, calibration.roiBottom,
            left, right, if (valid) null else DetectionError.LOW_CONFIDENCE,
            receivedAtMonotonicNanos = frame.receivedAtMonotonicNanos,
        )
    }

    private fun detectRow(threshold: IntensityFrame, edges: IntensityFrame, y: Int, calibration: CalibrationProfile): RowDetection? {
        val candidates = mutableListOf<RowDetection>()
        var x = calibration.roiLeft
        while (x < calibration.roiRight) {
            while (x < calibration.roiRight && threshold.intensities[y * threshold.width + x] != 0) x++
            val left = x
            while (x < calibration.roiRight && threshold.intensities[y * threshold.width + x] == 0) x++
            val right = x
            val runWidth = right - left
            if (runWidth in calibration.minimumPixelWidth..calibration.maximumPixelWidth &&
                hasBoundaryEdges(edges, y, left, right, calibration)
            ) candidates += RowDetection(left, right, runWidth.toDouble())
        }
        val roiCenter = (calibration.roiLeft + calibration.roiRight) / 2.0
        return candidates.minByOrNull { abs((it.left + it.right) / 2.0 - roiCenter) }
    }

    private fun hasBoundaryEdges(edges: IntensityFrame, y: Int, left: Int, right: Int, calibration: CalibrationProfile): Boolean {
        if (left <= calibration.roiLeft || right >= calibration.roiRight) return false
        val row = y * edges.width
        val hasLeft = edges.intensities[row + left] > 0 || edges.intensities[row + left - 1] > 0
        val hasRight = edges.intensities[row + right] > 0 || edges.intensities[row + right - 1] > 0
        return hasLeft && hasRight
    }

    private fun invalid(frame: CameraFrame, calibration: CalibrationProfile, error: DetectionError) = CameraMeasurement(
        frame.cameraId, 0.0, 0.0, 0.0, frame.capturedAtEpochMillis, frame.sequence, false,
        calibration.roiLeft, calibration.roiTop, calibration.roiRight, calibration.roiBottom,
        error = error,
        receivedAtMonotonicNanos = frame.receivedAtMonotonicNanos,
    )

    private fun median(values: List<Double>): Double = if (values.size % 2 == 1) values[values.size / 2]
    else (values[values.size / 2 - 1] + values[values.size / 2]) / 2.0

    private data class RowDetection(val left: Int, val right: Int, val width: Double)
}
