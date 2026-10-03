package com.filamentvision.backend.api.dto

import com.filamentvision.backend.application.*
import com.filamentvision.backend.input.profile.*

class UdpInputValidationException(val errors: List<UdpInputValidationError>) : IllegalArgumentException()

private fun invalid(path: String, message: String): Nothing =
    throw UdpInputValidationException(listOf(UdpInputValidationError(path, "INVALID_VALUE", message)))
private fun <T> required(value: T?, path: String): T = value ?: invalid(path, "Required field is missing")
private inline fun <reified T : Enum<T>> enum(value: String?, path: String): T =
    enumValues<T>().find { it.name == value } ?: invalid(path, "Unsupported value")

fun RaspberryPiUdpInputProfileDto.toDomain(): RaspberryPiUdpInputProfile {
    val selection = when (networkInterface.mode) {
        "AUTO" -> {
            if (networkInterface.interfaceId != null || networkInterface.ipv4 != null) invalid("networkInterface", "AUTO does not accept a specific interface")
            NetworkInterfaceSelection.Auto
        }
        "SPECIFIC" -> NetworkInterfaceSelection.Specific(required(networkInterface.interfaceId, "networkInterface.interfaceId"),
            required(networkInterface.ipv4, "networkInterface.ipv4"))
        else -> invalid("networkInterface.mode", "Unsupported interface selection")
    }
    val profile = RaspberryPiUdpInputProfile(enabled, selection, listenPort, raspberryPiIpv4, payload.toDomain(),
        DatagramEnvelopeProfile(envelope.headerHex, envelope.footerHex),
        TransportLimits(transportLimits.maxInFlightFrames, transportLimits.maxFrameBytes, transportLimits.frameTimeoutMs,
            transportLimits.maxChunkCount, transportLimits.maxChunkPayloadBytes))
    val validation = RaspberryPiUdpInputProfileValidator().validate(profile)
    if (!validation.isValid) throw UdpInputValidationException(validation.errors)
    return profile.copy(envelope = DatagramEnvelopeProfile(normalizeTwoByteHex(envelope.headerHex), normalizeTwoByteHex(envelope.footerHex)))
}

private fun ImagePayloadDto.toDomain(): ImagePayloadProfile {
    val fields = mapOf("width" to width, "height" to height, "packing" to packing, "channelOrder" to channelOrder,
        "maxFrameBytes" to maxFrameBytes, "expectedWidth" to expectedWidth, "expectedHeight" to expectedHeight)
    val allowed = when (format) {
        "JPEG" -> setOf("maxFrameBytes", "expectedWidth", "expectedHeight")
        "GRAY8" -> setOf("width", "height")
        "BINARY" -> setOf("width", "height", "packing")
        "RGB888" -> setOf("width", "height", "channelOrder")
        else -> invalid("payload.format", "Unsupported payload format")
    }
    fields.entries.firstOrNull { it.value != null && it.key !in allowed }?.let { invalid("payload.${it.key}", "Field does not apply to this format") }
    return when (format) {
        "JPEG" -> ImagePayloadProfile.Jpeg(required(maxFrameBytes, "payload.maxFrameBytes"), expectedWidth, expectedHeight)
        "GRAY8" -> ImagePayloadProfile.Gray8(required(width, "payload.width"), required(height, "payload.height"))
        "BINARY" -> ImagePayloadProfile.Binary(required(width, "payload.width"), required(height, "payload.height"), enum(packing, "payload.packing"))
        else -> ImagePayloadProfile.Rgb888(required(width, "payload.width"), required(height, "payload.height"), enum(channelOrder, "payload.channelOrder"))
    }
}
fun NetworkInterfaceSelection.toDto() = when (this) {
    NetworkInterfaceSelection.Auto -> NetworkInterfaceSelectionDto("AUTO")
    is NetworkInterfaceSelection.Specific -> NetworkInterfaceSelectionDto("SPECIFIC", interfaceId, ipv4)
}
fun RaspberryPiUdpInputProfile.toDto() = RaspberryPiUdpInputProfileDto(enabled, networkInterface.toDto(), listenPort,
    raspberryPiIpv4, payload.toDto(), DatagramEnvelopeDto(envelope.headerHex, envelope.footerHex),
    transportLimits.let { TransportLimitsDto(it.maxInFlightFrames, it.maxFrameBytes, it.frameTimeoutMs, it.maxChunkCount, it.maxChunkPayloadBytes) })
private fun ImagePayloadProfile.toDto() = when (this) {
    is ImagePayloadProfile.Jpeg -> ImagePayloadDto("JPEG", maxFrameBytes = maxFrameBytes, expectedWidth = expectedWidth, expectedHeight = expectedHeight)
    is ImagePayloadProfile.Gray8 -> ImagePayloadDto("GRAY8", width, height)
    is ImagePayloadProfile.Binary -> ImagePayloadDto("BINARY", width, height, packing = packing.name)
    is ImagePayloadProfile.Rgb888 -> ImagePayloadDto("RGB888", width, height, channelOrder = channelOrder.name)
}
fun UdpInputConfigurationView.toDto() = UdpInputConfigurationStateDto(savedRevision, appliedRevision,
    configuration.toDto(), appliedConfiguration.toDto(), outcome?.name)
fun NetworkInterfacesView.toDto() = NetworkInterfacesDto(interfaces.map {
    Ipv4NetworkInterfaceDto(it.id, it.displayName, it.ipv4, it.isUp, it.isLoopback, it.isVirtual, it.supportsMulticast)
}, selection.toDto(), resolution.advertisedBackendIpv4, listenPort, resolution.hasMultipleCandidates, resolution.statusDetail)
fun UdpDiagnosticsView.toDto() = counters.let { d -> UdpDiagnosticsDto(operational.status.name, operational.detail,
    d.datagramsReceived, d.bytesReceived, d.framesStarted, d.framesCompleted, d.framesDropped, d.duplicateChunks,
    d.outOfOrderChunks, d.missingChunks, d.lateChunks, d.reassemblyTimeouts, d.invalidTransportHeaders, d.oversizedFrames,
    d.invalidHeaderCount, d.invalidFooterCount, d.invalidPayloadSizeCount, d.decodeFailures, d.sourceFilteredDatagrams,
    d.oversizedOrTruncatedDatagrams, d.lastPacketMonotonicNanos, d.lastValidFrameMonotonicNanos,
    d.lastPacketSourceIpv4, d.lastPacketSourcePort, d.lastPacketEpochMillis, d.preAdmissionDroppedInputs, d.processingBackpressureDrops) }
