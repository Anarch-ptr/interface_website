package com.filamentvision.core.config

import com.filamentvision.core.model.FusionConfiguration
import com.filamentvision.core.repository.RepositoryException
import com.filamentvision.core.repository.RepositoryFailure

/** Transport identity/protocol and snapshot orchestration are intentionally not defined here. */
data class MonitoringConfiguration(
    val cameraA: VisionConfiguration = VisionConfiguration(),
    val cameraB: VisionConfiguration = VisionConfiguration(),
    val fusion: FusionConfiguration = FusionConfiguration(0.5, 0.5, 0.7, 100),
    val targetDiameter: Double = 1.75,
    val writeBatchSize: Int = 8,
    val flushIntervalMillis: Long = 1000,
    val realtimeCapacity: Int = 300,
    val previewFramesPerSecond: Int = 8,
    val publicationIntervalMillis: Long = 125,
) {
    fun validate() {
        if (!targetDiameter.isFinite() || targetDiameter <= 0 ||
            writeBatchSize <= 0 || flushIntervalMillis <= 0 || realtimeCapacity <= 0 ||
            previewFramesPerSecond <= 0 || publicationIntervalMillis <= 0 ||
            !fusion.cameraAWeight.isFinite() || !fusion.cameraBWeight.isFinite() ||
            fusion.cameraAWeight < 0 || fusion.cameraBWeight < 0 ||
            fusion.minimumConfidence !in 0.0..1.0 || fusion.maximumTimestampDifferenceMillis < 0)
            throw RepositoryException(RepositoryFailure.INVALID, "Invalid monitoring configuration")
        listOf(cameraA, cameraB).forEach {
            val c = it.calibration
            if (it.geometry.rotationDegrees !in listOf(0, 90, 180, 270) ||
                !c.mmPerPixel.isFinite() || c.mmPerPixel < 0 || c.minimumConfidence !in 0.0..1.0 ||
                c.minimumPixelWidth <= 0 || c.maximumPixelWidth < c.minimumPixelWidth ||
                c.manualThreshold !in 0..255 || c.edgeSensitivity !in 0..255 ||
                c.roiLeft < 0 || c.roiTop < 0 || c.roiRight < c.roiLeft || c.roiBottom < c.roiTop)
                throw RepositoryException(RepositoryFailure.INVALID, "Invalid camera configuration")
        }
    }
    /** Conservative: all fields affecting official processing require a stopped runtime. */
    fun requiresRestartFrom(previous: MonitoringConfiguration): Boolean =
        copy(previewFramesPerSecond = previous.previewFramesPerSecond,
            publicationIntervalMillis = previous.publicationIntervalMillis) != previous
}
