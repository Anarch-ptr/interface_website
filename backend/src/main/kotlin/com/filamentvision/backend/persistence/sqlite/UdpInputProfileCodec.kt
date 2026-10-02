package com.filamentvision.backend.persistence.sqlite

import com.filamentvision.backend.input.profile.*
import java.io.*

/** Explicit, versioned persistence codec. It is deliberately independent of REST JSON. */
object UdpInputProfileCodec {
    private const val VERSION = 1

    fun encode(profiles: UdpInputProfileSet): ByteArray {
        require(UdpInputProfileValidator().validate(profiles).isValid)
        return ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { out ->
                out.writeInt(VERSION)
                out.writeProfile(profiles.cameraA)
                out.writeProfile(profiles.cameraB)
            }
        }.toByteArray()
    }

    fun decode(bytes: ByteArray): UdpInputProfileSet = try {
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == VERSION) { "Unsupported UDP profile codec version" }
            val profiles = UdpInputProfileSet(input.readProfile(), input.readProfile())
            require(input.available() == 0) { "Trailing UDP profile bytes" }
            val validation = UdpInputProfileValidator().validate(profiles)
            require(validation.isValid) { "Invalid stored UDP profile: ${validation.errors}" }
            profiles
        }
    } catch (error: IllegalArgumentException) {
        throw error
    } catch (error: Throwable) {
        throw IllegalArgumentException("Malformed stored UDP profile", error)
    }

    private fun DataOutputStream.writeProfile(profile: UdpInputProfile) {
        writeEnum(profile.cameraId); writeBoolean(profile.enabled)
        writeBoolean(profile.sourceIp != null); profile.sourceIp?.let(::writeUTF)
        writeInt(profile.listenPort); writeEnum(profile.transportMode)
        when (val payload = profile.payload) {
            is PayloadProfile.Gray8 -> { writeUTF("GRAY8"); writeInt(payload.width); writeInt(payload.height) }
            is PayloadProfile.Binary -> { writeUTF("BINARY"); writeInt(payload.width); writeInt(payload.height); writeEnum(payload.binaryPacking) }
            is PayloadProfile.Jpeg -> { writeUTF("JPEG"); writeInt(payload.maxFrameBytes); writeNullableInt(payload.expectedWidth); writeNullableInt(payload.expectedHeight) }
            is PayloadProfile.Rgb888 -> { writeUTF("RGB888"); writeInt(payload.width); writeInt(payload.height); writeEnum(payload.channelOrder) }
            is PayloadProfile.Diameter -> { writeUTF("DIAMETER"); writeEnum(payload.valueEncoding); writeEnum(payload.byteOrder)
                writeDouble(payload.scale); writeDouble(payload.offset); writeEnum(payload.unit); writeDouble(payload.directDiameterFusionConfidence) }
        }
        with(profile.applicationFraming) {
            writeBoolean(headerEnabled); writeEnum(headerFormat); writeUTF(headerBytes); writeEnum(headerScope); writeBoolean(stripHeader)
            writeBoolean(footerEnabled); writeEnum(footerFormat); writeUTF(footerBytes); writeEnum(footerScope); writeBoolean(stripFooter)
        }
        with(profile.transportLimits) {
            writeInt(maxInFlightFrames); writeInt(maxFrameBytes); writeLong(frameTimeoutMs); writeInt(maxChunkCount); writeInt(maxChunkPayloadBytes)
        }
    }

    private fun DataInputStream.readProfile(): UdpInputProfile {
        val camera = readEnum<UdpCameraId>()
        val enabled = readBoolean()
        val sourceIp = if (readBoolean()) readUTF() else null
        val port = readInt()
        val transport = readEnum<TransportMode>()
        val payload = when (val type = readUTF()) {
            "GRAY8" -> PayloadProfile.Gray8(readInt(), readInt())
            "BINARY" -> PayloadProfile.Binary(readInt(), readInt(), readEnum())
            "JPEG" -> PayloadProfile.Jpeg(readInt(), readNullableInt(), readNullableInt())
            "RGB888" -> PayloadProfile.Rgb888(readInt(), readInt(), readEnum())
            "DIAMETER" -> PayloadProfile.Diameter(readEnum(), readEnum(), readDouble(), readDouble(), readEnum(), readDouble())
            else -> throw IllegalArgumentException("Unknown payload type $type")
        }
        val framing = ApplicationFramingProfile(readBoolean(), readEnum(), readUTF(), readEnum(), readBoolean(),
            readBoolean(), readEnum(), readUTF(), readEnum(), readBoolean())
        val limits = TransportLimits(readInt(), readInt(), readLong(), readInt(), readInt())
        return UdpInputProfile(camera, enabled, sourceIp, port, transport, payload, framing, limits)
    }

    private fun DataOutputStream.writeNullableInt(value: Int?) { writeBoolean(value != null); value?.let(::writeInt) }
    private fun DataInputStream.readNullableInt() = if (readBoolean()) readInt() else null
    private fun DataOutputStream.writeEnum(value: Enum<*>) = writeUTF(value.name)
    private inline fun <reified T : Enum<T>> DataInputStream.readEnum(): T = enumValueOf(readUTF())
}
