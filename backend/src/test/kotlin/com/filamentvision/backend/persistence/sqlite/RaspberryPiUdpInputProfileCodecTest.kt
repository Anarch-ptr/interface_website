package com.filamentvision.backend.persistence.sqlite

import com.filamentvision.backend.input.profile.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.nio.ByteBuffer

class RaspberryPiUdpInputProfileCodecTest {
    @Test fun versionOneRoundTripsDisabledDefaults() {
        val expected = RaspberryPiUdpInputProfile.defaults()
        val encoded = encode(expected)
        assertEquals(1, ByteBuffer.wrap(encoded).int)
        assertEquals(expected, decode(encoded))
    }

    @Test fun everyImagePayloadInterfaceAndOptionalFieldRoundTrips() {
        val payloads = buildList<ImagePayloadProfile> {
            add(ImagePayloadProfile.Gray8(31, 17))
            BinaryPacking.entries.forEach { add(ImagePayloadProfile.Binary(31, 17, it)) }
            RgbChannelOrder.entries.forEach { add(ImagePayloadProfile.Rgb888(31, 17, it)) }
            add(ImagePayloadProfile.Jpeg(100_000))
            add(ImagePayloadProfile.Jpeg(100_000, 31, null))
            add(ImagePayloadProfile.Jpeg(100_000, null, 17))
            add(ImagePayloadProfile.Jpeg(100_000, 31, 17))
        }
        for (payload in payloads) for (network in listOf(NetworkInterfaceSelection.Auto,
            NetworkInterfaceSelection.Specific("wifi-1", "192.168.1.10")))
            for (source in listOf(null, "192.168.1.20")) for (enabled in listOf(false, true)) {
                val expected = RaspberryPiUdpInputProfile(enabled, network, 51_234, source, payload,
                    DatagramEnvelopeProfile("12 AB", "EF 34"), TransportLimits(3, 1_000_000, 750, 65_535, 1300))
                assertEquals(expected, decode(encode(expected)))
            }
    }

    @Test fun canonicalizesBothEnvelopeValuesWithoutMutatingInput() {
        for (header in listOf("AA01", "aa01", "AA 01")) for (footer in listOf("0d0a", "0D0A", "0D 0A")) {
            val candidate = RaspberryPiUdpInputProfile.defaults().copy(envelope = DatagramEnvelopeProfile(header, footer))
            val encoded = encode(candidate)
            assertEquals(RaspberryPiUdpInputProfile.defaults(), decode(encoded))
            assertArrayEquals(encode(RaspberryPiUdpInputProfile.defaults()), encoded)
            assertEquals(DatagramEnvelopeProfile(header, footer), candidate.envelope)
        }
    }

    @Test fun rejectsUnknownVersionsTruncationAndTrailingBytesWithoutMutation() {
        val valid = encode(RaspberryPiUdpInputProfile.defaults())
        val before = valid.copyOf()
        for (bytes in listOf(byteArrayOf(), byteArrayOf(1, 2, 3), valid.copyOf(valid.size - 1),
            valid + byteArrayOf(0), valid.copyOf().also { ByteBuffer.wrap(it).putInt(2) })) {
            assertThrows(IllegalArgumentException::class.java) { decode(bytes) }
        }
        assertArrayEquals(before, valid)
    }

    @Test fun rejectsInvalidProfilesBeforeEncoding() {
        val defaults = RaspberryPiUdpInputProfile.defaults()
        val invalid = listOf(defaults.copy(listenPort = 0), defaults.copy(raspberryPiIpv4 = "pi.local"),
            defaults.copy(networkInterface = NetworkInterfaceSelection.Specific("", "192.168.1.10")),
            defaults.copy(payload = ImagePayloadProfile.Gray8(0, 17)),
            defaults.copy(transportLimits = defaults.transportLimits.copy(maxChunkCount = 65_536))) +
            listOf("", "AA", "AA 01 02", "GG 01", "AA0", "HEAD").flatMap {
                listOf(defaults.copy(envelope = DatagramEnvelopeProfile(it, "0D 0A")),
                    defaults.copy(envelope = DatagramEnvelopeProfile("AA 01", it)))
            }
        invalid.forEach { assertThrows(IllegalArgumentException::class.java) { encode(it) } }
    }

    @Test fun rejectsInvalidStoredFieldsAndUnknownTypeTags() {
        val valid = encode(RaspberryPiUdpInputProfile.defaults())
        // v1 AUTO/default JPEG field offsets are independently checked by the layout test below.
        val invalid = listOf(
            valid.copyOf().also { ByteBuffer.wrap(it, 11, 4).putInt(0) },
            valid.copyOf().also { it[7] = 'X'.code.toByte() }, // unknown interface tag
            valid.copyOf().also { it[18] = 'X'.code.toByte() }, // unknown payload tag
            valid.copyOf().also { it[30] = 'G'.code.toByte() }, // invalid HEX envelope
            valid.copyOf().also { ByteBuffer.wrap(it, 58, 4).putInt(65_536) },
        )
        invalid.forEach { assertThrows(IllegalArgumentException::class.java) { decode(it) } }
    }

    @Test fun storedLayoutContainsOnlyOneImageProfileAndFixedEnvelope() {
        val bytes = encode(RaspberryPiUdpInputProfile.defaults())
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            assertEquals(1, input.readInt())
            assertFalse(input.readBoolean())
            assertEquals("AUTO", input.readUTF())
            assertEquals(5000, input.readInt())
            assertFalse(input.readBoolean())
            assertEquals("JPEG", input.readUTF())
            assertEquals(16 * 1024 * 1024, input.readInt())
            assertFalse(input.readBoolean())
            assertFalse(input.readBoolean())
            assertEquals("AA 01", input.readUTF())
            assertEquals("0D 0A", input.readUTF())
            assertEquals(2, input.readInt())
            assertEquals(32 * 1024 * 1024, input.readInt())
            assertEquals(1000L, input.readLong())
            assertEquals(32768, input.readInt())
            assertEquals(1400, input.readInt())
            assertEquals(0, input.available())
        }
    }

    private fun encode(profile: RaspberryPiUdpInputProfile) = RaspberryPiUdpInputProfileCodec.encode(profile)
    private fun decode(bytes: ByteArray) = RaspberryPiUdpInputProfileCodec.decode(bytes)
}
