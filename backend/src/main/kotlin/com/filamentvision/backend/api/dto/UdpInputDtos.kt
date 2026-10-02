package com.filamentvision.backend.api.dto

data class UdpPayloadDto(
    val format: String,
    val width: Int? = null, val height: Int? = null,
    val binaryPacking: String? = null, val channelOrder: String? = null,
    val maxFrameBytes: Int? = null, val expectedWidth: Int? = null, val expectedHeight: Int? = null,
    val valueEncoding: String? = null, val byteOrder: String? = null,
    val scale: Double? = null, val offset: Double? = null, val unit: String? = null,
    val directDiameterFusionConfidence: Double? = null,
)
data class ApplicationFramingDto(
    val headerEnabled: Boolean = false, val headerFormat: String = "HEX", val headerBytes: String = "",
    val headerScope: String = "DATAGRAM", val stripHeader: Boolean = true,
    val footerEnabled: Boolean = false, val footerFormat: String = "HEX", val footerBytes: String = "",
    val footerScope: String = "DATAGRAM", val stripFooter: Boolean = true,
)
data class TransportLimitsDto(val maxInFlightFrames: Int = 4, val maxFrameBytes: Int = 8 * 1024 * 1024,
    val frameTimeoutMs: Long = 1000, val maxChunkCount: Int = 4096, val maxChunkPayloadBytes: Int = 1400)
data class UdpCameraProfileDto(val enabled: Boolean, val sourceIp: String? = null, val listenPort: Int,
    val transportMode: String, val payload: UdpPayloadDto,
    val applicationFraming: ApplicationFramingDto = ApplicationFramingDto(),
    val transportLimits: TransportLimitsDto = TransportLimitsDto())
data class UdpInputProfileSetDto(val cameraA: UdpCameraProfileDto, val cameraB: UdpCameraProfileDto)
data class SaveUdpInputConfigurationRequest(val commandId: String, val configuration: UdpInputProfileSetDto)
data class ApplyUdpInputConfigurationRequest(val commandId: String, val revision: Long, val expectedSavedRevision: Long)
data class UdpInputConfigurationStateDto(val savedRevision: Long, val appliedRevision: Long,
    val configuration: UdpInputProfileSetDto, val outcome: String? = null)
data class FieldValidationErrorDto(val path: String, val message: String)
data class UdpValidationDetailsDto(val fieldErrors: List<FieldValidationErrorDto>)
