package com.filamentvision.core.config

import com.filamentvision.core.model.FusionConfiguration
import com.filamentvision.core.repository.RepositoryException
import com.filamentvision.core.repository.RepositoryFailure

/** Transitional v1 data for consumers scheduled for migration in Tasks 14–17. */
data class LegacyMonitoringConfiguration(
    val cameraB: VisionConfiguration,
    val fusion: FusionConfiguration,
)

/** One Vision stream. Transport identity/protocol and snapshot orchestration belong to adapters. */
data class MonitoringConfiguration(
    val vision: VisionConfiguration = VisionConfiguration(),
    val targetDiameter: Double = 1.75,
    val writeBatchSize: Int = 8,
    val flushIntervalMillis: Long = 1000,
    val realtimeCapacity: Int = 300,
    val previewFramesPerSecond: Int = 8,
    val publicationIntervalMillis: Long = 125,
    val legacy: LegacyMonitoringConfiguration? = null,
) {
    /** Transitional constructor for the unchanged v1 codec/API/composition. */
    constructor(
        cameraA: VisionConfiguration,
        cameraB: VisionConfiguration = VisionConfiguration(),
        fusion: FusionConfiguration = FusionConfiguration(0.5, 0.5, 0.7, 100),
        targetDiameter: Double = 1.75,
        writeBatchSize: Int = 8,
        flushIntervalMillis: Long = 1000,
        realtimeCapacity: Int = 300,
        previewFramesPerSecond: Int = 8,
        publicationIntervalMillis: Long = 125,
    ) : this(cameraA, targetDiameter, writeBatchSize, flushIntervalMillis, realtimeCapacity,
        previewFramesPerSecond, publicationIntervalMillis, LegacyMonitoringConfiguration(cameraB, fusion))

    // Only v1 adapters read these aliases; the single-stream runtime consumes vision alone.
    val cameraA: VisionConfiguration get() = vision
    val cameraB: VisionConfiguration get() = legacy?.cameraB ?: VisionConfiguration()
    val fusion: FusionConfiguration get() = legacy?.fusion ?: FusionConfiguration(0.5, 0.5, 0.7, 100)

    /** Transitional named copy used by the unmigrated Backend facade. */
    fun copy(
        cameraA: VisionConfiguration,
        cameraB: VisionConfiguration = this.cameraB,
        fusion: FusionConfiguration = this.fusion,
        targetDiameter: Double = this.targetDiameter,
        writeBatchSize: Int = this.writeBatchSize,
        flushIntervalMillis: Long = this.flushIntervalMillis,
        realtimeCapacity: Int = this.realtimeCapacity,
        previewFramesPerSecond: Int = this.previewFramesPerSecond,
        publicationIntervalMillis: Long = this.publicationIntervalMillis,
    ) = MonitoringConfiguration(cameraA, cameraB, fusion, targetDiameter, writeBatchSize,
        flushIntervalMillis, realtimeCapacity, previewFramesPerSecond, publicationIntervalMillis)

    fun validate() {
        if (!targetDiameter.isFinite() || targetDiameter <= 0 ||
            writeBatchSize <= 0 || flushIntervalMillis <= 0 || realtimeCapacity <= 0 ||
            previewFramesPerSecond <= 0 || publicationIntervalMillis <= 0)
            throw RepositoryException(RepositoryFailure.INVALID, "Invalid monitoring configuration")
        legacy?.fusion?.let { fusion ->
            if (!fusion.cameraAWeight.isFinite() || !fusion.cameraBWeight.isFinite() ||
                fusion.cameraAWeight < 0 || fusion.cameraBWeight < 0 ||
                fusion.minimumConfidence !in 0.0..1.0 || fusion.maximumTimestampDifferenceMillis < 0)
                throw RepositoryException(RepositoryFailure.INVALID, "Invalid monitoring configuration")
        }
        listOfNotNull(vision, legacy?.cameraB).forEach {
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
