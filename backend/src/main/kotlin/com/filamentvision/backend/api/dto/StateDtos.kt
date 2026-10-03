package com.filamentvision.backend.api.dto

data class ApiResponse<T>(val correlationId: String, val data: T)
data class StateDto(val deviceId: String, val connection: String, val monitoring: String,
    val cameras: List<CameraDto>, val session: SessionDto?, val latestMeasurement: MeasurementDto?,
    val alarm: AlarmDto, val statistics: StatisticsDto, val savedRevision: Long?, val appliedRevision: Long?,
    val activeProducerCount: Int, val eventSequence: Long)
data class CameraDto(val cameraId: String, val state: String, val previewSupported: Boolean, val calibrationSupported: Boolean)
data class StartRequest(val commandId: String, val targetDiameter: Double? = null, val appliedRevision: Long? = null)
data class CommandRequest(val commandId: String)
data class MonitoringCommandDto(val outcome: String, val session: SessionDto?)
data class DeletedDto(val deleted: Boolean = true)
data class RateDto(val count: Long, val perSecond: Double?)
data class CameraMetricsDto(val deviceId: String, val cameraId: String, val received: RateDto, val processed: RateDto)
data class CounterDto(val name: String, val rate: RateDto)
data class LatencyDto(val name: String, val count: Long, val totalMillis: Double?, val maximumMillis: Double?)
data class DiagnosticsDto(val elapsedMillis: Long, val cameras: List<CameraMetricsDto>, val counters: List<CounterDto>,
    val latencies: List<LatencyDto>, val queuedMeasurements: Int, val retainedSnapshots: Int, val retainedSnapshotBytes: Long, val backendVersion: String)
data class UdpDiagnosticsDto(val status: String, val statusDetail: String?,
    val datagramsReceived: Long, val bytesReceived: Long, val framesStarted: Long?, val framesCompleted: Long,
    val framesDropped: Long, val duplicateChunks: Long?, val outOfOrderChunks: Long?, val missingChunks: Long?,
    val lateChunks: Long?, val reassemblyTimeouts: Long?, val invalidTransportHeaders: Long, val oversizedFrames: Long,
    val invalidHeaderCount: Long, val invalidFooterCount: Long, val invalidPayloadSizeCount: Long, val decodeFailures: Long,
    val sourceFilteredDatagrams: Long, val oversizedOrTruncatedDatagrams: Long,
    val lastPacketMonotonicNanos: Long?, val lastValidFrameMonotonicNanos: Long?,
    val lastPacketSourceIpv4: String?, val lastPacketSourcePort: Int?, val lastPacketEpochMillis: Long?,
    val preAdmissionDroppedInputs: Long, val processingBackpressureDrops: Long)
