package com.filamentvision.backend.persistence.sqlite

import com.filamentvision.backend.input.profile.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class UdpInputProfileCodecTest {
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
}
