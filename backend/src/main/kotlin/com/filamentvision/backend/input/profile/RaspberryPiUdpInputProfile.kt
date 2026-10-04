package com.filamentvision.backend.input.profile

/** Backend expected-input settings; applying these values does not configure the Raspberry Pi. */
data class RaspberryPiUdpInputProfile(
    val enabled: Boolean,
    val network: UdpNetworkProfile,
    val payload: ImagePayloadProfile,
    val envelope: DatagramEnvelopeProfile,
    val transportLimits: TransportLimits,
) {
    /** Compatibility constructor for persisted v1 data and pre-delta fixtures. */
    constructor(
        enabled: Boolean,
        networkInterface: NetworkInterfaceSelection,
        listenPort: Int,
        raspberryPiIpv4: String?,
        payload: ImagePayloadProfile,
        envelope: DatagramEnvelopeProfile,
        transportLimits: TransportLimits,
    ) : this(
        enabled,
        UdpNetworkProfile(
            bind = networkInterface.toBindProfile(),
            destination = AdvertisedDestinationProfile.Auto,
            sourceFilter = raspberryPiIpv4?.let(SourceFilterProfile::IpOrHost) ?: SourceFilterProfile.Any,
            listenPort = listenPort,
        ),
        payload,
        envelope,
        transportLimits,
    )

    val networkInterface: NetworkInterfaceSelection
        get() = network.bind.toLegacySelection()
    val listenPort: Int get() = network.listenPort
    val raspberryPiIpv4: String?
        get() = (network.sourceFilter as? SourceFilterProfile.IpOrHost)?.hostOrIpv4

    fun withNetwork(value: UdpNetworkProfile) = copy(network = value)

    companion object {
        /** Disabled until the operator verifies the sender and applies the profile. */
        fun defaults() = RaspberryPiUdpInputProfile(
            enabled = false,
            network = UdpNetworkProfile(
                bind = BindProfile.Auto,
                destination = AdvertisedDestinationProfile.Auto,
                sourceFilter = SourceFilterProfile.Any,
                listenPort = 5_000,
            ),
            payload = ImagePayloadProfile.Jpeg(),
            envelope = DatagramEnvelopeProfile("AA 01", "0D 0A"),
            transportLimits = TransportLimits(
                maxInFlightFrames = 2,
                maxFrameBytes = 32 * 1024 * 1024,
                frameTimeoutMs = 1_000,
                maxChunkCount = 32_768,
                maxChunkPayloadBytes = 1_400,
            ),
        )
    }
}

/** Temporary source-compatibility bridge while runtime call sites migrate to [RaspberryPiUdpInputProfile.network]. */
@Suppress("EXTENSION_SHADOWED_BY_MEMBER")
fun RaspberryPiUdpInputProfile.copy(
    enabled: Boolean = this.enabled,
    networkInterface: NetworkInterfaceSelection = this.networkInterface,
    listenPort: Int = this.listenPort,
    raspberryPiIpv4: String? = this.raspberryPiIpv4,
    payload: ImagePayloadProfile = this.payload,
    envelope: DatagramEnvelopeProfile = this.envelope,
    transportLimits: TransportLimits = this.transportLimits,
): RaspberryPiUdpInputProfile = RaspberryPiUdpInputProfile(
    enabled = enabled,
    network = network.copy(
        bind = networkInterface.toBindProfile(),
        sourceFilter = raspberryPiIpv4?.let(SourceFilterProfile::IpOrHost) ?: SourceFilterProfile.Any,
        listenPort = listenPort,
    ),
    payload = payload,
    envelope = envelope,
    transportLimits = transportLimits,
)

sealed interface NetworkInterfaceSelection {
    data object Auto : NetworkInterfaceSelection
    data class Specific(val interfaceId: String, val ipv4: String) : NetworkInterfaceSelection
}

private fun NetworkInterfaceSelection.toBindProfile(): BindProfile = when (this) {
    NetworkInterfaceSelection.Auto -> BindProfile.Auto
    is NetworkInterfaceSelection.Specific -> BindProfile.SpecificInterface(interfaceId, ipv4)
}

private fun BindProfile.toLegacySelection(): NetworkInterfaceSelection = when (this) {
    BindProfile.Auto, BindProfile.AllInterfaces, is BindProfile.ManualIp -> NetworkInterfaceSelection.Auto
    is BindProfile.SpecificInterface -> NetworkInterfaceSelection.Specific(interfaceId, ipv4)
}

sealed interface ImagePayloadProfile {
    data class Jpeg(
        val maxFrameBytes: Int = 16 * 1024 * 1024,
        val expectedWidth: Int? = null,
        val expectedHeight: Int? = null,
    ) : ImagePayloadProfile

    data class Gray8(val width: Int, val height: Int) : ImagePayloadProfile
    data class Binary(val width: Int, val height: Int, val packing: BinaryPacking) : ImagePayloadProfile
    data class Rgb888(val width: Int, val height: Int, val channelOrder: RgbChannelOrder) : ImagePayloadProfile
}

data class DatagramEnvelopeProfile(val headerHex: String, val footerHex: String)
