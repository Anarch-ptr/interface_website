package com.filamentvision.core.vision

import com.filamentvision.core.config.VisionConfiguration
import com.filamentvision.core.frame.CameraFrame

class VisionProcessor(
    private val clock: NanoClock = NanoClock.SYSTEM,
    private val grayscaleProcessor: GrayscaleProcessor = GrayscaleProcessor(),
    private val thresholdProcessor: ThresholdProcessor = ThresholdProcessor(),
    private val edgeMapProcessor: EdgeMapProcessor = EdgeMapProcessor(),
    private val detectionPipeline: DetectionPipeline = DetectionPipeline(),
) : VisionAnalyzer {
    override fun process(frame: CameraFrame, configuration: VisionConfiguration, decodeNanos: Long): VisionResult {
        val totalStart = clock.nanoTime()
        val geometryStart = clock.nanoTime()
        val transformed = ImageGeometryTransformer.transform(frame, configuration.geometry)
        val geometryNanos = clock.nanoTime() - geometryStart
        val grayscaleStart = clock.nanoTime()
        val grayscale = grayscaleProcessor.process(transformed, configuration.calibration.grayscaleMethod)
        val grayscaleNanos = clock.nanoTime() - grayscaleStart
        val thresholdStart = clock.nanoTime()
        val threshold = thresholdProcessor.process(grayscale, configuration.calibration)
        val thresholdNanos = clock.nanoTime() - thresholdStart
        val edgeStart = clock.nanoTime()
        val edges = edgeMapProcessor.process(grayscale, threshold.frame, configuration.calibration.edgeSensitivity)
        val edgeNanos = clock.nanoTime() - edgeStart
        val detectionStart = clock.nanoTime()
        val detection = detectionPipeline.detect(grayscale, threshold.frame, edges, frame, configuration.calibration)
        val detectionNanos = clock.nanoTime() - detectionStart
        val previewStart = clock.nanoTime()
        val overlay = DetectionOverlay(detection.roiLeft, detection.roiTop, detection.roiRight, detection.roiBottom, detection.leftEdge, detection.rightEdge)
        val previewNanos = clock.nanoTime() - previewStart
        val totalNanos = decodeNanos + (clock.nanoTime() - totalStart)
        return VisionResult(
            frame.cameraId, frame.capturedAtEpochMillis, frame.sequence, transformed.width, transformed.height,
            transformed, grayscale, threshold.frame, edges, overlay, detection.takeIf { it.valid },
            threshold.effectiveThreshold,
            VisionStageTimings(decodeNanos, geometryNanos, grayscaleNanos, thresholdNanos, edgeNanos, detectionNanos, previewNanos, totalNanos),
            detection.error,
        )
    }
}
