package com.filamentvision.backend.api.dto

data class CameraMeasurementDto(val cameraId: String, val timestamp: Long, val sequence: Long,
    val pixelWidth: Double?, val diameterMm: Double?, val confidence: Double?, val valid: Boolean, val source: String)
data class MeasurementDto(val evidence: List<CameraMeasurementDto>, val diameterMm: Double?,
    val confidence: Double?, val status: String, val timestamp: Long)
data class MeasurementRecordDto(val id: String, val sessionId: String, val appliedRevision: Long?, val measurement: MeasurementDto)
data class PageDto<T>(val items: List<T>, val nextCursor: String?)
data class SessionDto(val id: String, val startedAt: Long, val endedAt: Long?, val finalizationReason: String?, val statistics: StatisticsDto?)
data class StatisticsDto(val sampleCount: Long, val averageDiameter: Double?, val minimumDiameter: Double?,
    val maximumDiameter: Double?, val standardDeviation: Double?, val averageConfidence: Double?, val abnormalEventCount: Int)
data class AlarmDto(val level: String, val direction: String, val deviationPercent: Double?)
data class AlarmEventDto(val id: String, val sessionId: String, val timestamp: Long, val diameterMm: Double?, val from: AlarmDto, val to: AlarmDto)
