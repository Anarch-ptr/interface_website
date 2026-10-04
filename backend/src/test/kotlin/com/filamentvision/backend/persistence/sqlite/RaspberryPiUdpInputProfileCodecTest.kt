package com.filamentvision.backend.persistence.sqlite

import com.filamentvision.backend.input.profile.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.nio.ByteBuffer

class RaspberryPiUdpInputProfileCodecTest {
    @Test fun literalVersionOneDefaultsDecodeIntoEquivalentFlexibleIntent() {
        val decoded = decode(hex(
            "00000001 00 0004 4155544F 00001388 00 0004 4A504547 01000000 00 00 " +
                "0005 4141203031 0005 3044203041 00000002 02000000 00000000000003E8 00008000 00000578",
        ))
        assertFalse(decoded.enabled)
        assertEquals(BindProfile.Auto, decoded.network.bind)
        assertEquals(AdvertisedDestinationProfile.Auto, decoded.network.destination)
        assertEquals(SourceFilterProfile.Any, decoded.network.sourceFilter)
        assertEquals(5_000, decoded.network.listenPort)
        assertEquals(ImagePayloadProfile.Jpeg(), decoded.payload)
    }

    @Test fun versionTwoRoundTripsEveryFlexibleNetworkModeAndNormalizedHostname() {
        val defaults = RaspberryPiUdpInputProfile.defaults()
        val networks = listOf(
            UdpNetworkProfile(BindProfile.Auto, AdvertisedDestinationProfile.Auto, SourceFilterProfile.Any, 5_000),
            UdpNetworkProfile(BindProfile.AllInterfaces, AdvertisedDestinationProfile.Manual("Backend.LOCAL."), SourceFilterProfile.IpOrHost("PI.LOCAL."), 51_001),
            UdpNetworkProfile(BindProfile.SpecificInterface("wifi-stable", "10.0.0.2"), AdvertisedDestinationProfile.Manual("10.0.0.2"), SourceFilterProfile.IpOrHost("10.0.0.20"), 51_002),
            UdpNetworkProfile(BindProfile.ManualIp("192.168.1.8"), AdvertisedDestinationProfile.Auto, SourceFilterProfile.Any, 51_003),
        )
        networks.forEach { network ->
            val encoded = encode(defaults.withNetwork(network))
            assertEquals(2, ByteBuffer.wrap(encoded).int)
            val decoded = decode(encoded)
            assertEquals(normalized(network), decoded.network)
            assertEquals(defaults.payload, decoded.payload)
        }
    }

    @Test fun approvedInterfaceIdentifiersRoundTripAcrossUnsignedShortByteBoundary() {
        val identifiers = listOf("a".repeat(65_536), "a".repeat(65_535),
            "界".repeat(21_845), "界".repeat(21_846), "😀".repeat(16_384))
        for (identifier in identifiers) {
            val expected = specific(identifier)
            assertTrue(RaspberryPiUdpInputProfileValidator().validate(expected).isValid)
            val encoded = encode(expected)
            assertEquals(expected, decode(encoded))
        }
    }

    @Test fun interfaceIdentifierUsesExactStandardUtf8ForUnicodeAndNullCharacter() {
        val identifier = "Wi-Fi接口😀\u0000"
        val utf8 = byteArrayOf(0x57, 0x69, 0x2d, 0x46, 0x69, 0xe6.toByte(), 0x8e.toByte(), 0xa5.toByte(),
            0xe5.toByte(), 0x8f.toByte(), 0xa3.toByte(), 0xf0.toByte(), 0x9f.toByte(), 0x98.toByte(), 0x80.toByte(), 0)
        val expected = specific(identifier)
        assertTrue(RaspberryPiUdpInputProfileValidator().validate(expected).isValid)
        val encoded = encode(expected)
        assertEquals(16, ByteBuffer.wrap(encoded, 15, 4).int)
        assertArrayEquals(utf8, encoded.copyOfRange(19, 35))
        assertEquals(expected, decode(encoded))
    }

    @Test fun declaredIdentifierLengthRejectsNegativeAndBeyondRemainingBytesBeforeReading() {
        val valid = encode(specific("wifi-1"))
        // Prefix: 4-byte version, enabled byte, 2-byte UTF size + 8-byte SPECIFIC tag.
        val remainingAfterLength = valid.size - 19
        for (length in listOf(-1, Int.MIN_VALUE, remainingAfterLength + 1, Int.MAX_VALUE)) {
            val corrupt = valid.copyOf().also { ByteBuffer.wrap(it, 15, 4).putInt(length) }
            val before = corrupt.copyOf()
            val error = assertThrows(IllegalArgumentException::class.java) { decode(corrupt) }
            // Length validation itself rejects the declaration, before allocation or subsequent field reads.
            assertNull(error.cause)
            assertArrayEquals(before, corrupt)
        }
        assertThrows(IllegalArgumentException::class.java) { decode(valid.copyOf(17)) }
    }

    @Test fun malformedUtf8IdentifierIsRejectedInsteadOfReplaced() {
        val valid = encode(specific("wifi-1"))
        val corrupt = valid.copyOf().also { it[19] = 0xff.toByte() }
        assertThrows(IllegalArgumentException::class.java) { decode(corrupt) }
    }

    @Test fun unpairedHighSurrogateIdentifierIsRejectedBeforeEncodingReturns() {
        rejectsMalformedIdentifier("wifi-\uD800")
    }

    @Test fun unpairedLowSurrogateIdentifierIsRejectedBeforeEncodingReturns() {
        rejectsMalformedIdentifier("wifi-\uDC00")
    }

    @Test fun versionTwoRoundTripsDisabledDefaults() {
        val expected = RaspberryPiUdpInputProfile.defaults()
        val encoded = encode(expected)
        assertEquals(2, ByteBuffer.wrap(encoded).int)
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
            valid + byteArrayOf(0), valid.copyOf().also { ByteBuffer.wrap(it).putInt(99) })) {
            assertThrows(IllegalArgumentException::class.java) { decode(bytes) }
        }
        assertArrayEquals(before, valid)
    }

    @Test fun rejectsInvalidProfilesBeforeEncoding() {
        val defaults = RaspberryPiUdpInputProfile.defaults()
        val invalid = listOf(defaults.copy(listenPort = 0), defaults.copy(raspberryPiIpv4 = "pi local"),
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
        // v2 AUTO/AUTO/ANY/default JPEG offsets are independently checked by the layout test below.
        val invalid = listOf(
            valid.copyOf().also { ByteBuffer.wrap(it, 22, 4).putInt(0) },
            valid.copyOf().also { it[7] = 'X'.code.toByte() }, // unknown interface tag
            valid.copyOf().also { it[13] = 'X'.code.toByte() }, // unknown destination tag
            valid.copyOf().also { it[28] = 'X'.code.toByte() }, // unknown payload tag
        )
        invalid.forEach { assertThrows(IllegalArgumentException::class.java) { decode(it) } }
    }

    @Test fun storedLayoutContainsOnlyOneImageProfileAndFixedEnvelope() {
        val bytes = encode(RaspberryPiUdpInputProfile.defaults())
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            assertEquals(2, input.readInt())
            assertFalse(input.readBoolean())
            assertEquals("AUTO", input.readUTF())
            assertEquals("AUTO", input.readUTF())
            assertEquals("ANY", input.readUTF())
            assertEquals(5000, input.readInt())
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
    private fun specific(identifier: String) = RaspberryPiUdpInputProfile.defaults().copy(
        networkInterface = NetworkInterfaceSelection.Specific(identifier, "192.168.1.10"))
    private fun rejectsMalformedIdentifier(identifier: String) {
        val candidate = specific(identifier)
        assertTrue(RaspberryPiUdpInputProfileValidator().validate(candidate).isValid)
        assertThrows(IllegalArgumentException::class.java) { encode(candidate) }
        assertEquals(identifier, (candidate.networkInterface as NetworkInterfaceSelection.Specific).interfaceId)
    }

    private fun normalized(network: UdpNetworkProfile) = network.copy(
        destination = (network.destination as? AdvertisedDestinationProfile.Manual)?.let {
            AdvertisedDestinationProfile.Manual(normalizeHostOrIpv4(it.hostOrIpv4))
        } ?: network.destination,
        sourceFilter = (network.sourceFilter as? SourceFilterProfile.IpOrHost)?.let {
            SourceFilterProfile.IpOrHost(normalizeHostOrIpv4(it.hostOrIpv4))
        } ?: network.sourceFilter,
    )

    private fun hex(value: String): ByteArray = value.filterNot(Char::isWhitespace).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
