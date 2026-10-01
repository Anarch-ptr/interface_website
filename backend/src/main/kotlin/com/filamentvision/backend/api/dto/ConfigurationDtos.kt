package com.filamentvision.backend.api.dto

data class CalibrationDto(val roiLeft: Int = 0, val roiTop: Int = 0, val roiRight: Int = 0, val roiBottom: Int = 0,
    val grayscaleMethod: String = "LUMA_BT601", val thresholdMode: String = "MANUAL", val manualThreshold: Int = 128,
    val edgeSensitivity: Int = 24, val minimumPixelWidth: Int = 1, val maximumPixelWidth: Int = 1,
    val mmPerPixel: Double = 0.0, val minimumConfidence: Double = 0.7)
data class GeometryDto(val rotationDegrees: Int = 0, val mirrorHorizontally: Boolean = false, val mirrorVertically: Boolean = false)
data class VisionConfigurationDto(val calibration: CalibrationDto = CalibrationDto(), val geometry: GeometryDto = GeometryDto())
data class FusionConfigurationDto(val cameraAWeight: Double = 0.5, val cameraBWeight: Double = 0.5,
    val minimumConfidence: Double = 0.7, val maximumTimestampDifferenceMillis: Long = 100)
data class ConfigurationDto(val cameraA: VisionConfigurationDto = VisionConfigurationDto(), val cameraB: VisionConfigurationDto = VisionConfigurationDto(),
    val fusion: FusionConfigurationDto = FusionConfigurationDto(), val targetDiameter: Double = 1.75,
    val writeBatchSize: Int = 8, val flushIntervalMillis: Long = 1000, val realtimeCapacity: Int = 300,
    val previewFramesPerSecond: Int = 8, val publicationIntervalMillis: Long = 125)
data class SaveConfigurationRequest(val commandId: String, val configuration: ConfigurationDto)
data class ApplyConfigurationRequest(val commandId: String, val revision: Long, val expectedSavedRevision: Long)
data class ConfigurationStateDto(val savedRevision: Long?, val appliedRevision: Long?, val configuration: ConfigurationDto?, val outcome: String?)
data class CreateDraftRequest(val commandId: String, val cameraId: String, val configuration: VisionConfigurationDto)
/** PATCH replaces the complete draft settings, with defaults for omitted settings; revision is a CAS precondition. */
data class UpdateDraftRequest(val commandId: String, val expectedRevision: Long, val configuration: VisionConfigurationDto)
data class DraftDto(val id: String, val cameraId: String, val revision: Long, val configuration: VisionConfigurationDto)
