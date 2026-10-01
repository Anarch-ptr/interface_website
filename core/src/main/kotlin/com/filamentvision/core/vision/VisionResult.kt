package com.filamentvision.core.vision

import com.filamentvision.core.frame.CameraId

data class DetectionOverlay(
    val roiLeft: Int, val roiTop: Int, val roiRight: Int, val roiBottom: Int,
    val leftEdge: Int?, val rightEdge: Int?,
)

data class VisionStageTimings(
    val decodeNanos: Long, val geometryNanos: Long, val grayscaleNanos: Long,
    val thresholdNanos: Long, val edgeNanos: Long, val detectionNanos: Long,
    val previewNanos: Long, val totalNanos: Long,
)

/** Stages own their output buffers, so the input lease may be released after processing. */
data class VisionResult(
    val cameraId: CameraId,
    val timestampMillis: Long,
    val sequence: Long,
    val processedWidth: Int,
    val processedHeight: Int,
    val rawStage: ArgbStage,
    val grayscaleStage: IntensityFrame,
    val thresholdStage: IntensityFrame,
    val edgeStage: IntensityFrame,
    val overlay: DetectionOverlay,
    val measurement: CameraMeasurement?,
    val effectiveThreshold: Int,
    val timings: VisionStageTimings,
    val error: DetectionError?,
)
