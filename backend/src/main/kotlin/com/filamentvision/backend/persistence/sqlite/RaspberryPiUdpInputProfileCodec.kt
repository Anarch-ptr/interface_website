package com.filamentvision.backend.persistence.sqlite

import com.filamentvision.backend.input.profile.*
import java.io.*
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** Single image stream persistence, independent of REST and the legacy two-camera codec. */
object RaspberryPiUdpInputProfileCodec {
    private const val VERSION = 2

    fun encode(profile: RaspberryPiUdpInputProfile): ByteArray {
        val canonical = canonicalize(profile)
        return ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { out ->
                out.writeInt(VERSION)
                out.writeBoolean(canonical.enabled)
                when (val bind = canonical.network.bind) {
                    BindProfile.Auto -> out.writeUTF("AUTO")
                    BindProfile.AllInterfaces -> out.writeUTF("ALL_INTERFACES")
                    is BindProfile.SpecificInterface -> {
                        out.writeUTF("SPECIFIC"); out.writeInterfaceId(bind.interfaceId); out.writeUTF(bind.ipv4)
                    }
                    is BindProfile.ManualIp -> { out.writeUTF("MANUAL_IP"); out.writeUTF(bind.ipv4) }
                }
                when (val destination = canonical.network.destination) {
                    AdvertisedDestinationProfile.Auto -> out.writeUTF("AUTO")
                    is AdvertisedDestinationProfile.Manual -> { out.writeUTF("MANUAL"); out.writeUTF(destination.hostOrIpv4) }
                }
                when (val source = canonical.network.sourceFilter) {
                    SourceFilterProfile.Any -> out.writeUTF("ANY")
                    is SourceFilterProfile.IpOrHost -> { out.writeUTF("IP_OR_HOST"); out.writeUTF(source.hostOrIpv4) }
                }
                out.writeInt(canonical.network.listenPort)
                when (val payload = canonical.payload) {
                    is ImagePayloadProfile.Jpeg -> {
                        out.writeUTF("JPEG"); out.writeInt(payload.maxFrameBytes)
                        out.writeNullableInt(payload.expectedWidth); out.writeNullableInt(payload.expectedHeight)
                    }
                    is ImagePayloadProfile.Gray8 -> { out.writeUTF("GRAY8"); out.writeInt(payload.width); out.writeInt(payload.height) }
                    is ImagePayloadProfile.Binary -> {
                        out.writeUTF("BINARY"); out.writeInt(payload.width); out.writeInt(payload.height); out.writeUTF(payload.packing.name)
                    }
                    is ImagePayloadProfile.Rgb888 -> {
                        out.writeUTF("RGB888"); out.writeInt(payload.width); out.writeInt(payload.height); out.writeUTF(payload.channelOrder.name)
                    }
                }
                out.writeUTF(canonical.envelope.headerHex); out.writeUTF(canonical.envelope.footerHex)
                with(canonical.transportLimits) {
                    out.writeInt(maxInFlightFrames); out.writeInt(maxFrameBytes); out.writeLong(frameTimeoutMs)
                    out.writeInt(maxChunkCount); out.writeInt(maxChunkPayloadBytes)
                }
            }
        }.toByteArray()
    }

    fun decode(bytes: ByteArray): RaspberryPiUdpInputProfile = try {
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            val version = input.readInt()
            require(version == 1 || version == VERSION) { "Unsupported Raspberry Pi UDP profile codec version" }
            val enabled = input.readBoolean()
            val network = if (version == 1) input.readV1Network() else input.readV2Network()
            val payload = when (val type = input.readUTF()) {
                "JPEG" -> ImagePayloadProfile.Jpeg(input.readInt(), input.readNullableInt(), input.readNullableInt())
                "GRAY8" -> ImagePayloadProfile.Gray8(input.readInt(), input.readInt())
                "BINARY" -> ImagePayloadProfile.Binary(input.readInt(), input.readInt(), enumValueOf<BinaryPacking>(input.readUTF()))
                "RGB888" -> ImagePayloadProfile.Rgb888(input.readInt(), input.readInt(), enumValueOf<RgbChannelOrder>(input.readUTF()))
                else -> throw IllegalArgumentException("Unknown image payload type $type")
            }
            val envelope = DatagramEnvelopeProfile(input.readUTF(), input.readUTF())
            val limits = TransportLimits(input.readInt(), input.readInt(), input.readLong(), input.readInt(), input.readInt())
            require(input.available() == 0) { "Trailing Raspberry Pi UDP profile bytes" }
            canonicalize(RaspberryPiUdpInputProfile(enabled, network, payload, envelope, limits))
        }
    } catch (error: IllegalArgumentException) {
        throw error
    } catch (error: Exception) {
        throw IllegalArgumentException("Malformed stored Raspberry Pi UDP profile", error)
    }

    private fun canonicalize(profile: RaspberryPiUdpInputProfile): RaspberryPiUdpInputProfile {
        val validation = RaspberryPiUdpInputProfileValidator().validate(profile)
        require(validation.isValid) { "Invalid Raspberry Pi UDP profile: ${validation.errors}" }
        val network = profile.network.copy(
            destination = when (val destination = profile.network.destination) {
                AdvertisedDestinationProfile.Auto -> destination
                is AdvertisedDestinationProfile.Manual -> AdvertisedDestinationProfile.Manual(normalizeHostOrIpv4(destination.hostOrIpv4))
            },
            sourceFilter = when (val source = profile.network.sourceFilter) {
                SourceFilterProfile.Any -> source
                is SourceFilterProfile.IpOrHost -> SourceFilterProfile.IpOrHost(normalizeHostOrIpv4(source.hostOrIpv4))
            },
        )
        return profile.copy(
            network = network,
            envelope = DatagramEnvelopeProfile(
                normalizeTwoByteHex(profile.envelope.headerHex),
                normalizeTwoByteHex(profile.envelope.footerHex),
            ),
        )
    }

    private fun DataInputStream.readV1Network(): UdpNetworkProfile {
        val bind = when (val type = readUTF()) {
            "AUTO" -> BindProfile.Auto
            "SPECIFIC" -> BindProfile.SpecificInterface(readInterfaceId(), readUTF())
            else -> throw IllegalArgumentException("Unknown network interface selection $type")
        }
        val port = readInt()
        val source = if (readBoolean()) SourceFilterProfile.IpOrHost(readUTF()) else SourceFilterProfile.Any
        return UdpNetworkProfile(bind, AdvertisedDestinationProfile.Auto, source, port)
    }

    private fun DataInputStream.readV2Network(): UdpNetworkProfile {
        val bind = when (val type = readUTF()) {
            "AUTO" -> BindProfile.Auto
            "ALL_INTERFACES" -> BindProfile.AllInterfaces
            "SPECIFIC" -> BindProfile.SpecificInterface(readInterfaceId(), readUTF())
            "MANUAL_IP" -> BindProfile.ManualIp(readUTF())
            else -> throw IllegalArgumentException("Unknown bind mode $type")
        }
        val destination = when (val type = readUTF()) {
            "AUTO" -> AdvertisedDestinationProfile.Auto
            "MANUAL" -> AdvertisedDestinationProfile.Manual(readUTF())
            else -> throw IllegalArgumentException("Unknown destination mode $type")
        }
        val source = when (val type = readUTF()) {
            "ANY" -> SourceFilterProfile.Any
            "IP_OR_HOST" -> SourceFilterProfile.IpOrHost(readUTF())
            else -> throw IllegalArgumentException("Unknown source filter mode $type")
        }
        return UdpNetworkProfile(bind, destination, source, readInt())
    }

    private fun DataOutputStream.writeInterfaceId(value: String) {
        val encoded = try {
            StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(value))
        } catch (error: CharacterCodingException) {
            throw IllegalArgumentException("Malformed interface identifier", error)
        }
        val bytes = ByteArray(encoded.remaining()).also { encoded.get(it) }
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readInterfaceId(): String {
        val length = readInt()
        require(length >= 0 && length <= available()) { "Invalid interface identifier byte length" }
        return ByteArray(length).also(::readFully).decodeToString(throwOnInvalidSequence = true)
    }

    private fun DataOutputStream.writeNullableInt(value: Int?) { writeBoolean(value != null); value?.let(::writeInt) }
    private fun DataInputStream.readNullableInt() = if (readBoolean()) readInt() else null
}
