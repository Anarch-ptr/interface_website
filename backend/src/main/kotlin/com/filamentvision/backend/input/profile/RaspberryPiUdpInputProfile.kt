package com.filamentvision.backend.input.profile

/** Backend expected-input settings; applying these values does not configure the Raspberry Pi. */
data class RaspberryPiUdpInputProfile(
    val enabled: Boolean,
    val networkInterface: NetworkInterfaceSelection,
    val listenPort: Int,
    val raspberryPiIpv4: String?,
    val payload: ImagePayloadProfile,
    val envelope: DatagramEnvelopeProfile,
    val transportLimits: TransportLimits,
) {
    companion object {
        /** Disabled until the operator verifies the sender and applies the profile. */
        fun defaults() = RaspberryPiUdpInputProfile(
            enabled = false,
            networkInterface = NetworkInterfaceSelection.Auto,
            listenPort = 5_000,
            raspberryPiIpv4 = null,
            payload = ImagePayloadProfile.Jpeg(),
            envelope = DatagramEnvelopeProfile("AA 01", "0D 0A"),
            // Explicit values preserve the existing legacy TransportLimits constructor defaults.
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

sealed interface NetworkInterfaceSelection {
    data object Auto : NetworkInterfaceSelection
    data class Specific(val interfaceId: String, val ipv4: String) : NetworkInterfaceSelection
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
