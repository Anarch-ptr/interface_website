package com.filamentvision.backend.input.runtime

import com.filamentvision.backend.input.profile.*
import com.filamentvision.backend.input.transport.StructuredTransportCodec
import com.filamentvision.backend.input.transport.StructuredTransportHeader
import com.filamentvision.core.input.CameraInput
import com.filamentvision.core.input.DirectDiameterUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress

class UdpCameraProcessorTest {
    @Test
    fun singleDatagramDiameterRunsDatagramAndFrameFramingThenDecodesEvidence() {
        val profile = profile(
            TransportMode.SINGLE_DATAGRAM,
            PayloadProfile.Diameter(DiameterValueEncoding.ASCII, DiameterByteOrder.BIG_ENDIAN),
            ApplicationFramingProfile(true, FramingFormat.ASCII, "D", FramingScope.DATAGRAM, true),
        )
        val result = UdpCameraProcessor(CompiledUdpInputProfile.compile(profile)).process(packet("D1.75".encodeToByteArray()))
            as UdpCameraProcessResult.Accepted
        val evidence = (result.input as CameraInput.DirectDiameter).evidence
        assertEquals(1.75, evidence.normalizedDiameterMm, 0.0)
        assertEquals(DirectDiameterUnit.MM, evidence.unit)
        assertEquals(10, evidence.receivedAtMonotonicNanos)
        assertEquals(1, evidence.sequence)
    }

    @Test
    fun structuredGrayRunsTransportDatagramFramingReassemblyFrameFramingAndDecode() {
        val framing = ApplicationFramingProfile(
            headerEnabled = true, headerFormat = FramingFormat.ASCII, headerBytes = "D",
            headerScope = FramingScope.DATAGRAM, stripHeader = true,
            footerEnabled = true, footerFormat = FramingFormat.ASCII, footerBytes = "F",
            footerScope = FramingScope.FRAME, stripFooter = true,
        )
        val profile = profile(TransportMode.STRUCTURED_CHUNKED, PayloadProfile.Gray8(1, 1), framing)
        val transportPayload = byteArrayOf('D'.code.toByte(), 0x7f, 'F'.code.toByte())
        val bytes = StructuredTransportCodec.encode(
            StructuredTransportHeader(UdpCameraId.A, 7u, 0, 1, 123, transportPayload.size, 2),
            transportPayload,
        )
        val result = UdpCameraProcessor(CompiledUdpInputProfile.compile(profile)).process(packet(bytes))
            as UdpCameraProcessResult.Accepted
        val image = (result.input as CameraInput.Image).frame
        assertEquals(0xff7f7f7f.toInt(), image.pixels[0])
        assertEquals(10, image.receivedAtMonotonicNanos)
        image.close()
    }

    @Test
    fun malformedLayerIsRejectedWithoutEscapingProcessor() {
        val profile = profile(TransportMode.STRUCTURED_CHUNKED, PayloadProfile.Gray8(1, 1))
        val result = UdpCameraProcessor(CompiledUdpInputProfile.compile(profile)).process(packet(byteArrayOf(1, 2)))
        assertTrue(result is UdpCameraProcessResult.Rejected)
        assertEquals(UdpProcessingStage.TRANSPORT, (result as UdpCameraProcessResult.Rejected).stage)
    }

    private fun packet(bytes: ByteArray) = OwnedUdpPacket(bytes, bytes.size, InetSocketAddress("127.0.0.1", 9_000), 10, 20)

    private fun profile(
        mode: TransportMode,
        payload: PayloadProfile,
        framing: ApplicationFramingProfile = ApplicationFramingProfile(),
    ) = UdpInputProfile(
        UdpCameraId.A, true, null, 45_001, mode, payload, framing,
        TransportLimits(maxFrameBytes = 1024, maxChunkPayloadBytes = 128),
    )
}
