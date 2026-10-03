package com.filamentvision.backend.input.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RaspberryPiUdpInputProfileValidatorTest {
    private val validator = RaspberryPiUdpInputProfileValidator()
    private val defaults = RaspberryPiUdpInputProfile.defaults()

    @Test
    fun freshProfileIsDisabledAndValidWithSingleStreamJpegDefaults() {
        assertFalse(defaults.enabled)
        assertEquals(NetworkInterfaceSelection.Auto, defaults.networkInterface)
        assertEquals(5000, defaults.listenPort)
        assertEquals(null, defaults.raspberryPiIpv4)
        assertEquals(ImagePayloadProfile.Jpeg(16 * MIB), defaults.payload)
        assertEquals(DatagramEnvelopeProfile("AA 01", "0D 0A"), defaults.envelope)
        assertEquals(TransportLimits(2, 32 * MIB, 1000, 32768, 1400), defaults.transportLimits)
        assertValid(defaults)
        assertValid(defaults.copy(enabled = true))
    }

    @Test
    fun acceptsAutoAndSpecificInterfaceWithoutConsultingHostAdapters() {
        assertValid(defaults.copy(networkInterface = NetworkInterfaceSelection.Auto))
        assertValid(defaults.copy(networkInterface = NetworkInterfaceSelection.Specific("wifi-1", "192.168.1.10")))
        assertValid(defaults.copy(networkInterface = NetworkInterfaceSelection.Specific("vpn-1", "10.0.0.2")))
    }

    @Test
    fun specificInterfaceRequiresIdAndCanonicalIpv4() {
        listOf("", " ").forEach { id ->
            assertPaths(defaults.copy(networkInterface = NetworkInterfaceSelection.Specific(id, "192.168.1.10")), "networkInterface.interfaceId")
        }
        invalidIpv4.forEach { ip ->
            assertPaths(defaults.copy(networkInterface = NetworkInterfaceSelection.Specific("wifi-1", ip)), "networkInterface.ipv4")
        }
    }

    @Test
    fun senderFilterIsOptionalAndAcceptsOnlyCanonicalIpv4Literals() {
        assertValid(defaults.copy(raspberryPiIpv4 = null))
        listOf("10.0.0.1", "192.168.20.200", "255.255.255.255").forEach { ip ->
            assertValid(defaults.copy(raspberryPiIpv4 = ip))
        }
        invalidIpv4.forEach { ip -> assertPaths(defaults.copy(raspberryPiIpv4 = ip), "raspberryPiIpv4") }
    }

    @Test
    fun listenPortAcceptsBothBoundariesAndRejectsValuesOutsideThemEvenWhenDisabled() {
        listOf(1, 65535).forEach { assertValid(defaults.copy(listenPort = it)) }
        listOf(Int.MIN_VALUE, 0, 65536, Int.MAX_VALUE).forEach {
            assertPaths(defaults.copy(listenPort = it), "listenPort")
        }
    }

    @Test
    fun transportLimitsAcceptApprovedBoundariesIncludingUnsignedChunkMaximum() {
        assertValid(defaults.copy(payload = ImagePayloadProfile.Jpeg(1), transportLimits = TransportLimits(1, 1, 100, 1, 1)))
        assertValid(defaults.copy(transportLimits = TransportLimits(32, 32 * MIB, 10000, 65535, 60000)))
        assertValid(defaults.copy(transportLimits = defaults.transportLimits.copy(maxChunkCount = 32768)))
    }

    @Test
    fun rejectsEveryTransportLimitOutsideApprovedRanges() {
        val cases = listOf(
            defaults.transportLimits.copy(maxInFlightFrames = 0) to "maxInFlightFrames",
            defaults.transportLimits.copy(maxInFlightFrames = 33) to "maxInFlightFrames",
            defaults.transportLimits.copy(maxFrameBytes = 0) to "maxFrameBytes",
            defaults.transportLimits.copy(maxFrameBytes = 32 * MIB + 1) to "maxFrameBytes",
            defaults.transportLimits.copy(frameTimeoutMs = 99) to "frameTimeoutMs",
            defaults.transportLimits.copy(frameTimeoutMs = 10001) to "frameTimeoutMs",
            defaults.transportLimits.copy(maxChunkCount = 0) to "maxChunkCount",
            defaults.transportLimits.copy(maxChunkCount = 65536) to "maxChunkCount",
            defaults.transportLimits.copy(maxChunkPayloadBytes = 0) to "maxChunkPayloadBytes",
            defaults.transportLimits.copy(maxChunkPayloadBytes = 60001) to "maxChunkPayloadBytes",
        )
        cases.forEach { (limits, field) ->
            val result = validator.validate(defaults.copy(payload = ImagePayloadProfile.Jpeg(1), transportLimits = limits))
            assertTrue(result.errors.toString(), "transportLimits.$field" in result.errors.map { it.path })
        }
    }

    @Test
    fun acceptsAllFourFormatsAndTheirConditionalOptions() {
        val payloads = listOf(
            ImagePayloadProfile.Jpeg(),
            ImagePayloadProfile.Jpeg(32 * MIB, 3840, 2160),
            ImagePayloadProfile.Jpeg(expectedWidth = 1920),
            ImagePayloadProfile.Jpeg(expectedHeight = 1080),
            ImagePayloadProfile.Gray8(3840, 2160),
            ImagePayloadProfile.Binary(640, 480, BinaryPacking.BYTE_PER_PIXEL),
            ImagePayloadProfile.Binary(641, 479, BinaryPacking.BIT_PACKED),
            ImagePayloadProfile.Rgb888(3840, 2160, RgbChannelOrder.RGB),
            ImagePayloadProfile.Rgb888(3840, 2160, RgbChannelOrder.BGR),
        )
        payloads.forEach { assertValid(defaults.copy(payload = it)) }
    }

    @Test
    fun jpegCompressedLimitMustBePositiveWithinHardAndTransportBounds() {
        listOf(0, -1, 32 * MIB + 1, Int.MAX_VALUE).forEach {
            assertPaths(defaults.copy(payload = ImagePayloadProfile.Jpeg(it)), "payload.maxFrameBytes")
        }
        assertPaths(defaults.copy(transportLimits = defaults.transportLimits.copy(maxFrameBytes = 16 * MIB - 1)), "payload.maxFrameBytes")
    }

    @Test
    fun jpegOptionalDimensionsHavePrecisePathsAndCheckedDecodedBounds() {
        listOf(0, 8193, Int.MAX_VALUE).forEach { dimension ->
            assertPaths(defaults.copy(payload = ImagePayloadProfile.Jpeg(expectedWidth = dimension)), "payload.expectedWidth")
            assertPaths(defaults.copy(payload = ImagePayloadProfile.Jpeg(expectedHeight = dimension)), "payload.expectedHeight")
        }
        assertValid(defaults.copy(payload = ImagePayloadProfile.Jpeg(expectedWidth = 4096, expectedHeight = 4096)))
        val result = validator.validate(defaults.copy(payload = ImagePayloadProfile.Jpeg(expectedWidth = 4097, expectedHeight = 4097)))
        assertFalse(result.isValid)
        assertTrue(result.errors.toString(), "payload.decodedPixels" in result.errors.map { it.path })
    }

    @Test
    fun rawImageDimensionsRejectInvalidAndHugeValuesBeforeAllocation() {
        val cases = listOf(
            ImagePayloadProfile.Gray8(0, 1) to "payload.width",
            ImagePayloadProfile.Binary(1, 0, BinaryPacking.BIT_PACKED) to "payload.height",
            ImagePayloadProfile.Rgb888(8193, 1, RgbChannelOrder.RGB) to "payload.width",
            ImagePayloadProfile.Gray8(Int.MAX_VALUE, Int.MAX_VALUE) to "payload.width",
            ImagePayloadProfile.Gray8(4097, 4097) to "payload.decodedPixels",
            ImagePayloadProfile.Binary(8192, 8192, BinaryPacking.BIT_PACKED) to "payload.decodedPixels",
        )
        cases.forEach { (payload, path) ->
            val result = validator.validate(defaults.copy(payload = payload))
            assertFalse(result.isValid)
            assertTrue(result.errors.toString(), path in result.errors.map { it.path })
        }
        assertValid(defaults.copy(payload = ImagePayloadProfile.Gray8(4096, 4096)))
    }

    @Test
    fun rawWireSizeRespectsTransportLimitForEachPackingAndChannelOrder() {
        val cases = listOf(
            ImagePayloadProfile.Gray8(3, 3) to 9,
            ImagePayloadProfile.Binary(3, 3, BinaryPacking.BYTE_PER_PIXEL) to 9,
            ImagePayloadProfile.Binary(3, 3, BinaryPacking.BIT_PACKED) to 2,
            ImagePayloadProfile.Rgb888(3, 3, RgbChannelOrder.RGB) to 27,
            ImagePayloadProfile.Rgb888(3, 3, RgbChannelOrder.BGR) to 27,
        )
        cases.forEach { (payload, wireBytes) ->
            assertValid(defaults.copy(payload = payload, transportLimits = defaults.transportLimits.copy(maxFrameBytes = wireBytes)))
            assertPaths(defaults.copy(payload = payload, transportLimits = defaults.transportLimits.copy(maxFrameBytes = wireBytes - 1)), "payload.expectedPayloadBytes")
        }
        assertPaths(defaults.copy(payload = ImagePayloadProfile.Rgb888(4096, 4096, RgbChannelOrder.RGB)), "payload.expectedPayloadBytes")
    }

    @Test
    fun normalizeTwoByteHexReturnsCanonicalUppercaseSpacedBytes() {
        listOf("AA 01", "AA01", "aa01", " aa\t01 ").forEach {
            assertEquals("AA 01", normalizeTwoByteHex(it))
        }
        assertEquals("0D 0A", normalizeTwoByteHex("0d0a"))
        assertEquals("00 FF", normalizeTwoByteHex("00ff"))
    }

    @Test
    fun normalizeTwoByteHexRejectsWrongLengthAndNonAsciiHex() {
        invalidHex.forEach { value -> assertThrows(value, IllegalArgumentException::class.java) { normalizeTwoByteHex(value) } }
    }

    @Test
    fun envelopeValidationReportsTheExactFailingBoundaryField() {
        invalidHex.forEach { value ->
            assertPaths(defaults.copy(envelope = defaults.envelope.copy(headerHex = value)), "envelope.headerHex")
            assertPaths(defaults.copy(envelope = defaults.envelope.copy(footerHex = value)), "envelope.footerHex")
        }
        assertPaths(defaults.copy(envelope = DatagramEnvelopeProfile("", "GG 01")), "envelope.headerHex", "envelope.footerHex")
        assertValid(defaults.copy(envelope = DatagramEnvelopeProfile("aa01", "0d0a")))
    }

    @Test
    fun validationErrorsCarryStableFieldCodeAndUsefulMessage() {
        val range = validator.validate(defaults.copy(listenPort = 0)).errors.single()
        assertEquals("listenPort", range.path)
        assertEquals("OUT_OF_RANGE", range.code)
        assertTrue(range.message.isNotBlank())
        val malformed = validator.validate(defaults.copy(envelope = defaults.envelope.copy(headerHex = "GG 01"))).errors.single()
        assertEquals("INVALID_VALUE", malformed.code)
        assertTrue(malformed.message.isNotBlank())
    }

    @Test
    fun newModelExposesOnlyOneImageProfileWithoutLegacyTransportOrFramingControls() {
        assertEquals(setOf("Jpeg", "Gray8", "Binary", "Rgb888"), ImagePayloadProfile::class.java.declaredClasses.map { it.simpleName }.toSet())
        val fields = RaspberryPiUdpInputProfile::class.java.declaredFields.map { it.name }.toSet()
        listOf("cameraId", "cameraA", "cameraB", "transportMode", "applicationFraming").forEach { assertFalse(it, it in fields) }
        assertEquals(setOf("headerHex", "footerHex"), DatagramEnvelopeProfile::class.java.declaredFields.map { it.name }.toSet())
        assertEquals(setOf("width", "height", "packing"), ImagePayloadProfile.Binary::class.java.declaredFields.map { it.name }.toSet())
        assertEquals(setOf("width", "height", "channelOrder"), ImagePayloadProfile.Rgb888::class.java.declaredFields.map { it.name }.toSet())
    }

    private fun assertValid(profile: RaspberryPiUdpInputProfile) {
        val result = validator.validate(profile)
        assertTrue(result.errors.toString(), result.isValid)
        assertTrue(result.errors.isEmpty())
    }

    private fun assertPaths(profile: RaspberryPiUdpInputProfile, vararg expected: String) {
        val result = validator.validate(profile)
        assertFalse(result.isValid)
        assertEquals(expected.toSet(), result.errors.map { it.path }.toSet())
    }

    private companion object {
        const val MIB = 1024 * 1024
        val invalidIpv4 = listOf("", "::1", "2001:db8::1", "camera.local", "192.168.1", "192.168.1.256", "192.168.001.1", " 127.0.0.1 ", "１.2.3.4")
        val invalidHex = listOf("", " ", "AA", "AA 01 02", "AA0", "GG 01", "AA-01", "0xAA01", "ＡＡ01")
    }
}
