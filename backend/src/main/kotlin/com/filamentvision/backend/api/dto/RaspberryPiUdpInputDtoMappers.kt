package com.filamentvision.backend.api.dto

import com.filamentvision.backend.application.*
import com.filamentvision.backend.input.network.*
import com.filamentvision.backend.input.profile.*

class UdpInputValidationException(val errors: List<UdpInputValidationError>) : IllegalArgumentException()
private fun invalid(path: String, message: String): Nothing =
    throw UdpInputValidationException(listOf(UdpInputValidationError(path, "INVALID_VALUE", message)))
private fun <T> required(value: T?, path: String): T = value ?: invalid(path, "Required field is missing")
private inline fun <reified T : Enum<T>> enum(value: String?, path: String): T =
    enumValues<T>().find { it.name == value } ?: invalid(path, "Unsupported value")

fun UdpNetworkProfileDto.toDomain(): UdpNetworkProfile {
    val bindProfile = when (bind.mode) {
        "AUTO" -> BindProfile.Auto
        "ALL_INTERFACES" -> BindProfile.AllInterfaces
        "SPECIFIC_INTERFACE" -> BindProfile.SpecificInterface(required(bind.interfaceId, "network.bind.interfaceId"), required(bind.ipv4, "network.bind.ipv4"))
        "MANUAL_IP" -> BindProfile.ManualIp(required(bind.ipv4, "network.bind.ipv4"))
        else -> invalid("network.bind.mode", "Unsupported bind mode")
    }
    val destinationProfile = when (destination.mode) {
        "AUTO" -> AdvertisedDestinationProfile.Auto
        "MANUAL" -> AdvertisedDestinationProfile.Manual(required(destination.hostOrIpv4, "network.destination.hostOrIpv4"))
        else -> invalid("network.destination.mode", "Unsupported destination mode")
    }
    val sourceProfile = when (sourceFilter.mode) {
        "ANY" -> SourceFilterProfile.Any
        "IP_OR_HOST" -> SourceFilterProfile.IpOrHost(required(sourceFilter.hostOrIpv4, "network.sourceFilter.hostOrIpv4"))
        else -> invalid("network.sourceFilter.mode", "Unsupported source filter mode")
    }
    return UdpNetworkProfile(bindProfile, destinationProfile, sourceProfile, listenPort)
}

fun RaspberryPiUdpInputProfileDto.toDomain(): RaspberryPiUdpInputProfile {
    val profile = RaspberryPiUdpInputProfile(enabled, network.toDomain(), payload.toDomain(),
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

fun UdpNetworkProfile.toDto() = UdpNetworkProfileDto(
    when (val v = bind) {
        BindProfile.Auto -> BindProfileDto("AUTO")
        BindProfile.AllInterfaces -> BindProfileDto("ALL_INTERFACES")
        is BindProfile.SpecificInterface -> BindProfileDto("SPECIFIC_INTERFACE", v.interfaceId, v.ipv4)
        is BindProfile.ManualIp -> BindProfileDto("MANUAL_IP", ipv4 = v.ipv4)
    },
    when (val v = destination) {
        AdvertisedDestinationProfile.Auto -> DestinationProfileDto("AUTO")
        is AdvertisedDestinationProfile.Manual -> DestinationProfileDto("MANUAL", v.hostOrIpv4)
    },
    when (val v = sourceFilter) {
        SourceFilterProfile.Any -> SourceFilterProfileDto("ANY")
        is SourceFilterProfile.IpOrHost -> SourceFilterProfileDto("IP_OR_HOST", v.hostOrIpv4)
    }, listenPort)

fun RaspberryPiUdpInputProfile.toDto() = RaspberryPiUdpInputProfileDto(enabled, network.toDto(), payload.toDto(),
    DatagramEnvelopeDto(envelope.headerHex, envelope.footerHex),
    transportLimits.let { TransportLimitsDto(it.maxInFlightFrames, it.maxFrameBytes, it.frameTimeoutMs, it.maxChunkCount, it.maxChunkPayloadBytes) })
private fun ImagePayloadProfile.toDto() = when (this) {
    is ImagePayloadProfile.Jpeg -> ImagePayloadDto("JPEG", maxFrameBytes = maxFrameBytes, expectedWidth = expectedWidth, expectedHeight = expectedHeight)
    is ImagePayloadProfile.Gray8 -> ImagePayloadDto("GRAY8", width, height)
    is ImagePayloadProfile.Binary -> ImagePayloadDto("BINARY", width, height, packing = packing.name)
    is ImagePayloadProfile.Rgb888 -> ImagePayloadDto("RGB888", width, height, channelOrder = channelOrder.name)
}
fun EffectiveUdpNetworkPlan.toDto() = EffectiveUdpNetworkPlanDto(actualBindIpv4, actualBindInterfaceId,
    configuredDestination, resolvedDestinationIpv4, configuredSource, resolvedSourceIpv4s,
    destinationStatus.name, sourceStatus.name, planCreatedAtEpochMillis, appliedAtEpochMillis, resolutionGeneration)
fun UdpInputConfigurationView.toDto() = UdpInputConfigurationStateDto(savedRevision, appliedRevision,
    resolutionGeneration, configuration.toDto(), appliedConfiguration.toDto(), appliedEffectiveNetworkPlan?.toDto(),
    networkConsistencyStatus.name, reapplyRequired, lastApplyError, outcome?.name)
fun NetworkResolutionPreview.toDto() = UdpNetworkResolutionPreviewDto(configuredDestination, configuredSource,
    candidatePlan?.toDto(), destinationStatus.name, sourceStatus.name, consistencyStatus.name,
    reapplyRequired, differences, warnings, errorCode, previewedAtEpochMillis, appliedResolutionGeneration, superseded)
fun NetworkInterfacesView.toDto() = NetworkInterfacesDto(interfaces.map {
    Ipv4NetworkInterfaceDto(it.id, it.displayName, it.ipv4, it.isUp, it.isLoopback, it.isVirtual, it.supportsMulticast)
})
fun UdpDiagnosticsView.toDto() = counters.let { d -> UdpDiagnosticsDto(operational.status.name, operational.detail,
    d.datagramsReceived, d.bytesReceived, d.framesStarted, d.framesCompleted, d.framesDropped, d.duplicateChunks,
    d.outOfOrderChunks, d.missingChunks, d.lateChunks, d.reassemblyTimeouts, d.invalidTransportHeaders, d.oversizedFrames,
    d.invalidHeaderCount, d.invalidFooterCount, d.invalidPayloadSizeCount, d.decodeFailures, d.sourceFilteredDatagrams,
    d.oversizedOrTruncatedDatagrams, d.lastPacketMonotonicNanos, d.lastValidFrameMonotonicNanos,
    d.lastPacketSourceIpv4, d.lastPacketSourcePort, d.lastPacketEpochMillis, d.preAdmissionDroppedInputs,
    d.processingBackpressureDrops, d.appliedNetwork?.toDto()) }
