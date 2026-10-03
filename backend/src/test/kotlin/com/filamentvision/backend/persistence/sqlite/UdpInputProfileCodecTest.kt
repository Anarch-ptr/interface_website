package com.filamentvision.backend.persistence.sqlite

import com.filamentvision.backend.input.profile.*
import com.filamentvision.core.config.*
import com.filamentvision.core.model.FusionConfiguration
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class UdpInputProfileCodecTest {
    @Test fun neutralMonitoringConfigurationWritesV2WithoutLegacyMetadata() {
        val expected = MonitoringConfiguration(vision = vision(), targetDiameter = 1.8, writeBatchSize = 12,
            flushIntervalMillis = 750, realtimeCapacity = 90, previewFramesPerSecond = 3,
            publicationIntervalMillis = 333)
        val encoded = ConfigurationCodec.encode(expected)
        assertEquals(2, ByteBuffer.wrap(encoded).int)
        assertEquals(expected, ConfigurationCodec.decode(encoded))
        assertNull(ConfigurationCodec.decode(encoded).legacy)
    }

    @Test fun neutralMonitoringRoundTripsEveryVisionEnumAndGeometry() {
        for (grayscale in GrayscaleMethod.entries) for (threshold in ThresholdMode.entries)
            for (rotation in listOf(0, 90, 180, 270)) for (horizontal in listOf(false, true))
                for (vertical in listOf(false, true)) {
                    val expected = MonitoringConfiguration(vision = vision().copy(
                        calibration = vision().calibration.copy(grayscaleMethod = grayscale, thresholdMode = threshold),
                        geometry = ImageGeometry(rotation, horizontal, vertical)))
                    assertEquals(expected, ConfigurationCodec.decode(ConfigurationCodec.encode(expected)))
                }
    }

    @Test fun literalV1MonitoringBytesChooseCameraAAndRemainImmutable() {
        val stored = hex(LEGACY_MONITORING_HEX)
        val before = stored.copyOf()
        val decoded = ConfigurationCodec.decode(stored)
        assertEquals(vision(), decoded.vision)
        assertEquals(vision().copy(calibration = vision().calibration.copy(
            grayscaleMethod = GrayscaleMethod.LUMA_BT601, thresholdMode = ThresholdMode.MANUAL),
            geometry = ImageGeometry(270, false, true)), decoded.legacy!!.cameraB)
        assertEquals(FusionConfiguration(.3, .7, .8, 321), decoded.legacy!!.fusion)
        assertEquals(MonitoringConfiguration(vision = vision(), targetDiameter = 1.8, writeBatchSize = 12,
            flushIntervalMillis = 750, realtimeCapacity = 90, previewFramesPerSecond = 3,
            publicationIntervalMillis = 333), decoded.copy(legacy = null))
        assertArrayEquals(before, stored)
        assertArrayEquals(before, ConfigurationCodec.encode(decoded))
        val neutralBytes = ConfigurationCodec.encode(decoded.copy(legacy = null))
        assertEquals(2, ByteBuffer.wrap(neutralBytes).int)
        assertNull(ConfigurationCodec.decode(neutralBytes).legacy)
        assertArrayEquals(before, stored)
    }

    @Test fun monitoringRejectsUnknownVersionsTruncatedTrailingAndInvalidBytes() {
        val valid = ConfigurationCodec.encode(MonitoringConfiguration(vision = vision()))
        val unknown = valid.copyOf().also { ByteBuffer.wrap(it).putInt(99) }
        for (bytes in listOf(unknown, byteArrayOf(), byteArrayOf(1, 2, 3), valid.copyOf(valid.size - 1),
            valid + byteArrayOf(0), hex(LEGACY_MONITORING_HEX).copyOf(80))) {
            assertThrows(IllegalArgumentException::class.java) { ConfigurationCodec.decode(bytes) }
        }
        // First ROI coordinate follows the version tag in both formats.
        val invalid = valid.copyOf().also { ByteBuffer.wrap(it, 4, 4).putInt(-1) }
        assertThrows(IllegalArgumentException::class.java) { ConfigurationCodec.decode(invalid) }
        val unknownEnum = valid.copyOf().also { it[22] = 'X'.code.toByte() }
        assertThrows(IllegalArgumentException::class.java) { ConfigurationCodec.decode(unknownEnum) }
    }

    @Test fun literalLegacyUdpBytesAndDiameterFramingReadsStayUnchanged() {
        val stored = hex(LEGACY_UDP_HEX)
        val before = stored.copyOf()
        val expected = set(PayloadProfile.Gray8(31, 17))
        assertEquals(expected, UdpInputProfileCodec.decode(stored))
        assertArrayEquals(before, UdpInputProfileCodec.encode(expected))
        assertArrayEquals(before, stored)
    }

    @Test fun roundTripsEveryPayloadAndEnumVariant() {
        val payloads = buildList<PayloadProfile> {
            add(PayloadProfile.Gray8(31, 17))
            BinaryPacking.entries.forEach { add(PayloadProfile.Binary(31, 17, it)) }
            RgbChannelOrder.entries.forEach { add(PayloadProfile.Rgb888(31, 17, it)) }
            add(PayloadProfile.Jpeg(100_000, 31, 17))
            DiameterValueEncoding.entries.forEachIndexed { index, encoding ->
                DiameterByteOrder.entries.forEach { order ->
                    add(PayloadProfile.Diameter(encoding, order, 0.5, -index.toDouble(),
                        DiameterUnit.entries[index % DiameterUnit.entries.size], 0.75))
                }
            }
        }
        payloads.forEachIndexed { index, payload ->
            val expected = set(payload, if (index % 2 == 0) FramingFormat.HEX else FramingFormat.ASCII,
                FramingScope.entries[index % FramingScope.entries.size])
            assertEquals(expected, UdpInputProfileCodec.decode(UdpInputProfileCodec.encode(expected)))
        }
    }

    @Test fun rejectsUnknownVersionAndMalformedOrInvalidStoredProfile() {
        val encoded = UdpInputProfileCodec.encode(set(PayloadProfile.Gray8(31, 17)))
        encoded[3] = 2
        assertThrows(IllegalArgumentException::class.java) { UdpInputProfileCodec.decode(encoded) }
        assertThrows(IllegalArgumentException::class.java) { UdpInputProfileCodec.decode(byteArrayOf(1, 2, 3)) }
    }

    private fun set(payload: PayloadProfile, format: FramingFormat = FramingFormat.HEX,
        scope: FramingScope = FramingScope.DATAGRAM) = UdpInputProfileSet(
        cameraA = profile(UdpCameraId.A, payload, 5101, format, scope),
        cameraB = profile(UdpCameraId.B, PayloadProfile.Diameter(DiameterValueEncoding.ASCII,
            DiameterByteOrder.LITTLE_ENDIAN, 2.0, 0.1, DiameterUnit.INCH, .6), 5102,
            if (format == FramingFormat.HEX) FramingFormat.ASCII else FramingFormat.HEX,
            if (scope == FramingScope.DATAGRAM) FramingScope.FRAME else FramingScope.DATAGRAM),
    )

    private fun profile(camera: UdpCameraId, payload: PayloadProfile, port: Int,
        format: FramingFormat, scope: FramingScope) = UdpInputProfile(
        camera, true, if (camera == UdpCameraId.A) "127.0.0.1" else null, port,
        if (camera == UdpCameraId.A) TransportMode.STRUCTURED_CHUNKED else TransportMode.SINGLE_DATAGRAM,
        payload, ApplicationFramingProfile(true, format, if (format == FramingFormat.HEX) "AA 55" else "HEAD",
            scope, false, true, format, if (format == FramingFormat.HEX) "0D 0A" else "TAIL", scope, true),
        TransportLimits(3, 1_000_000, 750, 700, 1300),
    )

    private fun vision() = VisionConfiguration(CalibrationProfile(1, 2, 30, 40, GrayscaleMethod.AVERAGE,
        ThresholdMode.AUTOMATIC, 57, 12, 3, 27, .021, .83), ImageGeometry(90, true, false))

    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    companion object {
        // Hand-checked v1 DataOutput fields, frozen before the v2 implementation.
        private const val LEGACY_MONITORING_HEX =
            "0000000100000001000000020000001e0000002800074156455241474500094155544f4d41544943" +
            "000000390000000c000000030000001b3f95810624dd2f1b3fea8f5c28f5c28f0000005a0100" +
            "00000001000000020000001e00000028000a4c554d415f425436303100064d414e55414c" +
            "000000390000000c000000030000001b3f95810624dd2f1b3fea8f5c28f5c28f0000010e0001" +
            "3fd33333333333333fe66666666666663fe999999999999a0000000000000141" +
            "3ffccccccccccccd0000000c00000000000002ee0000005a00000003000000000000014d"
        private const val LEGACY_UDP_HEX =
            "00000001000141010100093132372e302e302e31000013ed0012535452554354555245445f4348554e4b4544" +
            "000547524159380000001f00000011010003484558000541412035350008444154414752414d00" +
            "010003484558000530442030410008444154414752414d0100000003000f424000000000000002ee000002bc00000514" +
            "0001420100000013ee000f53494e474c455f444154414752414d00084449414d4554455200054153434949" +
            "000d4c4954544c455f454e4449414e40000000000000003fb999999999999a0004494e43483fe3333333333333" +
            "010005415343494900044845414400054652414d4500010005415343494900045441494c00054652414d4501" +
            "00000003000f424000000000000002ee000002bc00000514"
    }
}
