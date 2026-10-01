package com.filamentvision.backend.api.dto

import com.filamentvision.backend.application.*
import com.filamentvision.core.alarm.AlarmState
import com.filamentvision.core.config.*
import com.filamentvision.core.metrics.CounterRate
import com.filamentvision.core.model.*
import com.filamentvision.core.repository.*
import com.filamentvision.core.runtime.RuntimeSession
import com.filamentvision.core.session.SessionStatistics
import com.filamentvision.core.vision.CameraMeasurement
import io.ktor.http.encodeURLPathPart

private fun Double.safe(): Double? = takeIf { isFinite() }
fun CameraMeasurement.toDto() = CameraMeasurementDto(cameraId.value, timestamp, sequence, pixelWidth.safe(), diameterMm.safe(), confidence.safe(), valid)
fun OfficialMeasurement.toDto() = MeasurementDto(cameraA?.toDto(), cameraB?.toDto(), cameraA?.diameterMm?.safe(), cameraB?.diameterMm?.safe(),
    fusedDiameter.safe(), shapeDifference.safe(), confidence.safe(), status.name, fusionReason.name, timestamp)
fun MeasurementRecord.toDto() = MeasurementRecordDto(id, sessionId, appliedRevision, measurement.toDto())
fun RuntimeSession.toDto() = SessionDto(id, startedAtEpochMillis, null, null, null)
fun SessionRecord.toDto() = SessionDto(session.id, session.startedAtEpochMillis, finalization?.endedAtEpochMillis,
    finalization?.reason?.name, finalization?.statistics?.toDto())
fun SessionStatistics.toDto() = StatisticsDto(sampleCount, averageDiameter.safe(), minimumDiameter.safe(), maximumDiameter.safe(),
    standardDeviation.safe(), averageConfidence.safe(), abnormalEventCount)
fun AlarmState.toDto() = AlarmDto(level.name, direction.name, deviationPercent.safe())
fun AlarmEventRecord.toDto() = AlarmEventDto(id, sessionId, transition.timestamp, transition.fusedDiameter.safe(), transition.from.toDto(), transition.to.toDto())
fun CameraView.toDto() = CameraDto(cameraId, state.name, previewSupported, calibrationSupported)
fun DeviceView.toDto() = StateDto(deviceId, connection.name, monitoring.lifecycle.name, cameras.map { it.toDto() },
    monitoring.activeSession?.toDto(), monitoring.latestMeasurement?.toDto(), monitoring.latestAlarm.toDto(), monitoring.statistics.toDto(),
    revisions.savedRevision, revisions.appliedRevision, monitoring.activeProducerCount, eventSequence)
private fun CounterRate.toDto() = RateDto(count, perSecond.safe())
fun DiagnosticsView.toDto() = DiagnosticsDto(metrics.elapsedMillis,
    metrics.cameras.map { (key, value) -> CameraMetricsDto(key.deviceId, key.cameraId.value, value.received.toDto(), value.processed.toDto()) },
    metrics.counters.map { (key, value) -> CounterDto(key.name, value.toDto()) },
    metrics.latencies.map { (key, value) -> LatencyDto(key.name, value.count, value.totalMillis.safe(), value.maximumMillis.safe()) },
    queuedMeasurements, retainedSnapshots, retainedSnapshotBytes, backendVersion)
// Repository error messages may contain platform details. Only a public machine code crosses HTTP.
fun ErrorRecord.toDto() = ErrorRecordDto(id, sessionId, timestamp,
    code.takeIf { it.matches(Regex("[A-Z][A-Z0-9_]{0,63}")) } ?: "DEVICE_ERROR", "Device error")
fun SnapshotRecord.toDto() = SnapshotDto(id, sessionId, timestamp, cameraId, sourceSequence, "/api/v1/snapshots/${id.encodeURLPathPart()}")

fun ConfigurationDto.toDomain() = MonitoringConfiguration(cameraA.toDomain(), cameraB.toDomain(),
    FusionConfiguration(fusion.cameraAWeight, fusion.cameraBWeight, fusion.minimumConfidence, fusion.maximumTimestampDifferenceMillis),
    targetDiameter, writeBatchSize, flushIntervalMillis, realtimeCapacity, previewFramesPerSecond, publicationIntervalMillis).also { it.validate() }
fun VisionConfigurationDto.toDomain(): VisionConfiguration {
    val c = calibration
    val gray = GrayscaleMethod.entries.find { it.name == c.grayscaleMethod } ?: throw ApplicationFailure(ApplicationFailureCode.INVALID)
    val threshold = ThresholdMode.entries.find { it.name == c.thresholdMode } ?: throw ApplicationFailure(ApplicationFailureCode.INVALID)
    return VisionConfiguration(CalibrationProfile(c.roiLeft, c.roiTop, c.roiRight, c.roiBottom, gray, threshold, c.manualThreshold,
        c.edgeSensitivity, c.minimumPixelWidth, c.maximumPixelWidth, c.mmPerPixel, c.minimumConfidence),
        ImageGeometry(geometry.rotationDegrees, geometry.mirrorHorizontally, geometry.mirrorVertically)).also {
        MonitoringConfiguration(cameraA = it, cameraB = it).validate()
    }
}
fun VisionConfiguration.toDto(): VisionConfigurationDto {
    val c = calibration
    return VisionConfigurationDto(CalibrationDto(c.roiLeft, c.roiTop, c.roiRight, c.roiBottom, c.grayscaleMethod.name, c.thresholdMode.name,
        c.manualThreshold, c.edgeSensitivity, c.minimumPixelWidth, c.maximumPixelWidth, c.mmPerPixel, c.minimumConfidence),
        GeometryDto(geometry.rotationDegrees, geometry.mirrorHorizontally, geometry.mirrorVertically))
}
fun MonitoringConfiguration.toDto() = ConfigurationDto(cameraA.toDto(), cameraB.toDto(),
    FusionConfigurationDto(fusion.cameraAWeight, fusion.cameraBWeight, fusion.minimumConfidence, fusion.maximumTimestampDifferenceMillis),
    targetDiameter, writeBatchSize, flushIntervalMillis, realtimeCapacity, previewFramesPerSecond, publicationIntervalMillis)
fun ConfigurationView.toDto() = ConfigurationStateDto(revisions.savedRevision, revisions.appliedRevision, saved?.configuration?.toDto(), outcome?.name)
fun DraftView.toDto() = DraftDto(id, cameraId, revision, configuration.toDto())
