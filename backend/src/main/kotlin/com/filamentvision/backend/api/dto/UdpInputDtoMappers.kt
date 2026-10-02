package com.filamentvision.backend.api.dto

import com.filamentvision.backend.application.UdpInputConfigurationView
import com.filamentvision.backend.input.profile.*

class UdpInputValidationException(val errors: List<UdpInputValidationError>) : IllegalArgumentException()

fun UdpInputProfileSetDto.toDomain(): UdpInputProfileSet {
    val result = UdpInputProfileSet(cameraA.toDomain(UdpCameraId.A), cameraB.toDomain(UdpCameraId.B))
    val validation = UdpInputProfileValidator().validate(result)
    if (!validation.isValid) throw UdpInputValidationException(validation.errors)
    return result
}

private fun UdpCameraProfileDto.toDomain(camera: UdpCameraId): UdpInputProfile = UdpInputProfile(
    camera, enabled, sourceIp, listenPort, enum<TransportMode>(transportMode), payload.toDomain(),
    ApplicationFramingProfile(applicationFraming.headerEnabled, enum(applicationFraming.headerFormat), applicationFraming.headerBytes,
        enum(applicationFraming.headerScope), applicationFraming.stripHeader, applicationFraming.footerEnabled,
        enum(applicationFraming.footerFormat), applicationFraming.footerBytes, enum(applicationFraming.footerScope), applicationFraming.stripFooter),
    TransportLimits(transportLimits.maxInFlightFrames, transportLimits.maxFrameBytes, transportLimits.frameTimeoutMs,
        transportLimits.maxChunkCount, transportLimits.maxChunkPayloadBytes),
)

private fun UdpPayloadDto.toDomain(): PayloadProfile = when (format) {
    "GRAY8" -> PayloadProfile.Gray8(required(width), required(height))
    "BINARY" -> PayloadProfile.Binary(required(width), required(height), enum(required(binaryPacking)))
    "JPEG" -> PayloadProfile.Jpeg(required(maxFrameBytes), expectedWidth, expectedHeight)
    "RGB888" -> PayloadProfile.Rgb888(required(width), required(height), enum(required(channelOrder)))
    "DIAMETER" -> PayloadProfile.Diameter(enum(required(valueEncoding)), enum(required(byteOrder)), scale ?: 1.0,
        offset ?: 0.0, unit?.let { enum<DiameterUnit>(it) } ?: DiameterUnit.MM, directDiameterFusionConfidence ?: 1.0)
    else -> throw UdpInputValidationException(listOf(UdpInputValidationError("payload.format", "UNSUPPORTED", "Unsupported payload format")))
}

private fun <T> required(value: T?): T = value ?: throw UdpInputValidationException(
    listOf(UdpInputValidationError("payload", "REQUIRED", "Required payload field is missing")))
private inline fun <reified T : Enum<T>> enum(value: String): T = enumValues<T>().find { it.name == value }
    ?: throw UdpInputValidationException(listOf(UdpInputValidationError("enum", "UNSUPPORTED", "Unsupported value")))

fun UdpInputProfileSet.toDto() = UdpInputProfileSetDto(cameraA.toDto(), cameraB.toDto())
private fun UdpInputProfile.toDto() = UdpCameraProfileDto(enabled, sourceIp, listenPort, transportMode.name, payload.toDto(),
    applicationFraming.let { ApplicationFramingDto(it.headerEnabled, it.headerFormat.name, it.headerBytes, it.headerScope.name,
        it.stripHeader, it.footerEnabled, it.footerFormat.name, it.footerBytes, it.footerScope.name, it.stripFooter) },
    transportLimits.let { TransportLimitsDto(it.maxInFlightFrames, it.maxFrameBytes, it.frameTimeoutMs, it.maxChunkCount, it.maxChunkPayloadBytes) })
private fun PayloadProfile.toDto() = when (this) {
    is PayloadProfile.Gray8 -> UdpPayloadDto("GRAY8", width, height)
    is PayloadProfile.Binary -> UdpPayloadDto("BINARY", width, height, binaryPacking = binaryPacking.name)
    is PayloadProfile.Jpeg -> UdpPayloadDto("JPEG", maxFrameBytes = maxFrameBytes, expectedWidth = expectedWidth, expectedHeight = expectedHeight)
    is PayloadProfile.Rgb888 -> UdpPayloadDto("RGB888", width, height, channelOrder = channelOrder.name)
    is PayloadProfile.Diameter -> UdpPayloadDto("DIAMETER", valueEncoding = valueEncoding.name, byteOrder = byteOrder.name,
        scale = scale, offset = offset, unit = unit.name, directDiameterFusionConfidence = directDiameterFusionConfidence)
}
fun UdpInputConfigurationView.toDto() = UdpInputConfigurationStateDto(savedRevision, appliedRevision, configuration.toDto(), outcome?.name)
