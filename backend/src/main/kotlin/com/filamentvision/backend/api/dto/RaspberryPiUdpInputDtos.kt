package com.filamentvision.backend.api.dto

import com.fasterxml.jackson.annotation.JsonInclude

/** Expected input at Backend; Save/Apply never configures the sender. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ImagePayloadDto(val format: String, val width: Int? = null, val height: Int? = null,
    val packing: String? = null, val channelOrder: String? = null, val maxFrameBytes: Int? = null,
    val expectedWidth: Int? = null, val expectedHeight: Int? = null)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class NetworkInterfaceSelectionDto(val mode: String, val interfaceId: String? = null, val ipv4: String? = null)
data class DatagramEnvelopeDto(val headerHex: String, val footerHex: String)
data class TransportLimitsDto(val maxInFlightFrames: Int = 2, val maxFrameBytes: Int = 32 * 1024 * 1024,
    val frameTimeoutMs: Long = 1000, val maxChunkCount: Int = 32768, val maxChunkPayloadBytes: Int = 1400)
data class RaspberryPiUdpInputProfileDto(val enabled: Boolean, val networkInterface: NetworkInterfaceSelectionDto,
    val listenPort: Int, val raspberryPiIpv4: String? = null, val payload: ImagePayloadDto,
    val envelope: DatagramEnvelopeDto, val transportLimits: TransportLimitsDto = TransportLimitsDto())
data class SaveUdpInputConfigurationRequest(val commandId: String, val expectedSavedRevision: Long,
    val configuration: RaspberryPiUdpInputProfileDto)
data class ApplyUdpInputConfigurationRequest(val commandId: String, val revision: Long, val expectedSavedRevision: Long)
data class UdpInputConfigurationStateDto(val savedRevision: Long, val appliedRevision: Long,
    val configuration: RaspberryPiUdpInputProfileDto, val appliedConfiguration: RaspberryPiUdpInputProfileDto,
    val outcome: String? = null, val protocolVersion: Int = 1, val transportMode: String = "STRUCTURED_CHUNKED")
data class FieldValidationErrorDto(val path: String, val message: String)
data class UdpValidationDetailsDto(val fieldErrors: List<FieldValidationErrorDto>)
data class Ipv4NetworkInterfaceDto(val id: String, val displayName: String, val ipv4: String, val isUp: Boolean,
    val isLoopback: Boolean, val isVirtual: Boolean, val supportsMulticast: Boolean)
data class NetworkInterfacesDto(val interfaces: List<Ipv4NetworkInterfaceDto>, val selection: NetworkInterfaceSelectionDto,
    val advertisedBackendIpv4: String?, val listenPort: Int, val hasMultipleCandidates: Boolean, val statusDetail: String?)
