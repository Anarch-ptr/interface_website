package com.filamentvision.backend.api.dto

import com.fasterxml.jackson.annotation.JsonInclude

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ImagePayloadDto(val format: String, val width: Int? = null, val height: Int? = null,
    val packing: String? = null, val channelOrder: String? = null, val maxFrameBytes: Int? = null,
    val expectedWidth: Int? = null, val expectedHeight: Int? = null)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class BindProfileDto(val mode: String, val interfaceId: String? = null, val ipv4: String? = null)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class DestinationProfileDto(val mode: String, val hostOrIpv4: String? = null)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class SourceFilterProfileDto(val mode: String, val hostOrIpv4: String? = null)
data class UdpNetworkProfileDto(val bind: BindProfileDto, val destination: DestinationProfileDto,
    val sourceFilter: SourceFilterProfileDto, val listenPort: Int)
data class DatagramEnvelopeDto(val headerHex: String, val footerHex: String)
data class TransportLimitsDto(val maxInFlightFrames: Int = 2, val maxFrameBytes: Int = 32 * 1024 * 1024,
    val frameTimeoutMs: Long = 1000, val maxChunkCount: Int = 32768, val maxChunkPayloadBytes: Int = 1400)
data class RaspberryPiUdpInputProfileDto(val enabled: Boolean, val network: UdpNetworkProfileDto,
    val payload: ImagePayloadDto, val envelope: DatagramEnvelopeDto,
    val transportLimits: TransportLimitsDto = TransportLimitsDto())
data class SaveUdpInputConfigurationRequest(val commandId: String, val expectedSavedRevision: Long,
    val configuration: RaspberryPiUdpInputProfileDto)
data class ApplyUdpInputConfigurationRequest(val commandId: String, val revision: Long,
    val expectedSavedRevision: Long, val expectedAppliedRevision: Long,
    val expectedResolutionGeneration: Long, val forceReapply: Boolean = false)
data class UdpNetworkResolutionPreviewRequest(val network: UdpNetworkProfileDto)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class EffectiveUdpNetworkPlanDto(val actualBindIpv4: String, val actualBindInterfaceId: String?,
    val configuredDestination: String?, val resolvedDestinationIpv4: String?, val configuredSource: String?,
    val resolvedSourceIpv4s: Set<String>, val destinationStatus: String, val sourceStatus: String,
    val planCreatedAtEpochMillis: Long, val appliedAtEpochMillis: Long?, val resolutionGeneration: Long)
data class AppliedUdpNetworkProvenanceDto(val actualBindIpv4: String, val actualBindInterfaceId: String?,
    val configuredDestination: String?, val resolvedDestinationIpv4: String?, val configuredSource: String?,
    val resolvedSourceIpv4s: Set<String>, val resolutionGeneration: Long,
    val planCreatedAtEpochMillis: Long, val appliedAtEpochMillis: Long?)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class UdpInputConfigurationStateDto(val savedRevision: Long, val appliedRevision: Long,
    val resolutionGeneration: Long, val configuration: RaspberryPiUdpInputProfileDto,
    val appliedConfiguration: RaspberryPiUdpInputProfileDto,
    val appliedEffectiveNetworkPlan: EffectiveUdpNetworkPlanDto?, val networkConsistencyStatus: String,
    val reapplyRequired: Boolean, val lastApplyError: String? = null, val outcome: String? = null,
    val protocolVersion: Int = 1, val transportMode: String = "STRUCTURED_CHUNKED")

@JsonInclude(JsonInclude.Include.NON_NULL)
data class UdpNetworkResolutionPreviewDto(val configuredDestination: String?, val configuredSource: String?,
    val candidatePlan: EffectiveUdpNetworkPlanDto?, val destinationStatus: String, val sourceStatus: String,
    val networkConsistencyStatus: String, val reapplyRequired: Boolean, val differences: Set<String>,
    val warnings: Set<String>, val errorCode: String?, val previewedAtEpochMillis: Long,
    val appliedResolutionGeneration: Long?, val superseded: Boolean)

data class FieldValidationErrorDto(val path: String, val message: String)
data class UdpValidationDetailsDto(val fieldErrors: List<FieldValidationErrorDto>)
data class Ipv4NetworkInterfaceDto(val id: String, val displayName: String, val ipv4: String, val isUp: Boolean,
    val isLoopback: Boolean, val isVirtual: Boolean, val supportsMulticast: Boolean)
data class NetworkInterfacesDto(val interfaces: List<Ipv4NetworkInterfaceDto>)
