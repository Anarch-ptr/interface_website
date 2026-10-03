package com.filamentvision.backend.input.runtime

import com.filamentvision.backend.input.decoder.PayloadDecoder
import com.filamentvision.backend.input.profile.*
import com.filamentvision.backend.input.transport.*
import org.junit.Assert.*
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import javax.imageio.ImageIO

class RaspberryPiUdpProcessorTest {
    private val source = InetSocketAddress("192.0.2.10", 9_000)

    @Test
    fun allRawFormatsReassembleOutOfOrderChunksIntoOnlyCameraImages() {
        val cases = listOf(
            Triple(ImagePayloadProfile.Gray8(2, 1), byteArrayOf(0, 127), intArrayOf(0xff000000.toInt(), 0xff7f7f7f.toInt())),
            Triple(ImagePayloadProfile.Binary(2, 1, BinaryPacking.BYTE_PER_PIXEL), byteArrayOf(0, 2), intArrayOf(0xff000000.toInt(), -1)),
            Triple(ImagePayloadProfile.Binary(9, 1, BinaryPacking.BIT_PACKED), byteArrayOf(0x80.toByte(), 0x80.toByte()),
                intArrayOf(-1, 0xff000000.toInt(), 0xff000000.toInt(), 0xff000000.toInt(), 0xff000000.toInt(),
                    0xff000000.toInt(), 0xff000000.toInt(), 0xff000000.toInt(), -1)),
            Triple(ImagePayloadProfile.Rgb888(2, 1, RgbChannelOrder.RGB), byteArrayOf(1, 2, 3, 4, 5, 6), intArrayOf(0xff010203.toInt(), 0xff040506.toInt())),
            Triple(ImagePayloadProfile.Rgb888(2, 1, RgbChannelOrder.BGR), byteArrayOf(3, 2, 1, 6, 5, 4), intArrayOf(0xff010203.toInt(), 0xff040506.toInt())),
        )
        cases.forEach { (format, bytes, pixels) ->
            val processor = processor(format)
            val split = bytes.size / 2
            assertEquals(ProcessorResult.Pending, receive(processor, chunk(bytes.copyOfRange(split, bytes.size), 1, 2, bytes.size)))
            val result = receive(processor, chunk(bytes.copyOfRange(0, split), 0, 2, bytes.size)) as ProcessorResult.Accepted
            result.input.frame.use { frame ->
                assertEquals("camera", frame.cameraId.value)
                assertEquals(pixels.size, frame.pixels.count)
                pixels.forEachIndexed { index, argb -> assertEquals(argb, frame.pixels[index]) }
            }
            assertEquals(0, processor.bufferedReassemblyBytes)
            assertEquals(0, processor.inFlightFrameCount)
        }
    }

    @Test
    fun jpegDecodesOnlyAfterEveryChunkArrives() {
        val bytes = ByteArrayOutputStream().use { output ->
            val image = BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)
            repeat(2) { x -> repeat(2) { y -> image.setRGB(x, y, 0xffffff) } }
            check(ImageIO.write(image, "jpg", output))
            output.toByteArray()
        }
        val processor = processor(ImagePayloadProfile.Jpeg(4096, 2, 2))
        val split = bytes.size / 2
        assertEquals(ProcessorResult.Pending, receive(processor, chunk(bytes.copyOfRange(split, bytes.size), 1, 2, bytes.size)))
        val result = receive(processor, chunk(bytes.copyOfRange(0, split), 0, 2, bytes.size)) as ProcessorResult.Accepted
        result.input.frame.use {
            assertEquals(2, it.width)
            assertEquals(2, it.height)
            repeat(4) { index -> assertEquals(-1, it.pixels[index]) }
        }
    }

    @Test
    fun identicalDuplicateRemainsPendingAndConflictingDuplicateDropsRetainedFrame() {
        val processor = processor()
        val first = chunk(byteArrayOf(10), 0, 2, 2)
        assertEquals(ProcessorResult.Pending, receive(processor, first))
        assertEquals(ProcessorResult.Pending, receive(processor, first))
        assertEquals(1, processor.bufferedReassemblyBytes)
        val conflict = receive(processor, chunk(byteArrayOf(11), 0, 2, 2))
        assertEquals(ProcessorResult.Rejected(RaspberryPiProcessingStage.REASSEMBLY, "CONFLICTING_DUPLICATE"), conflict)
        assertEquals(0, processor.inFlightFrameCount)
        assertEquals(0, processor.bufferedReassemblyBytes)
    }

    @Test
    fun missingChunkExpiresAndLateChunkCannotRecreateTheFrame() {
        val processor = processor()
        assertEquals(ProcessorResult.Pending, receive(processor, chunk(byteArrayOf(10), 0, 2, 2), monotonic = 10))
        assertEquals(1, processor.expire(1_000_000_011))
        assertEquals(0, processor.bufferedReassemblyBytes)
        assertEquals(ProcessorResult.Rejected(RaspberryPiProcessingStage.REASSEMBLY, "LATE_CHUNK"),
            receive(processor, chunk(byteArrayOf(20), 1, 2, 2), monotonic = 1_000_000_012))
    }

    @Test
    fun envelopeFailuresShortCircuitTransportReassemblyAndDecode() {
        val compiled = CompiledRaspberryPiInputProfile.compile(profile())
        var payloadViews = 0
        var decodes = 0
        val processor = RaspberryPiUdpProcessor(CompiledRaspberryPiInputProfile(
            compiled.profile, compiled.envelopeParser,
            TransportDatagramParser(BorrowedPayloadViewFactory { bytes, offset, length ->
                payloadViews++
                BorrowedPayloadView(bytes, offset, length)
            }), compiled.reassembler,
            PayloadDecoder { bytes -> decodes++; compiled.decoder.decode(bytes) },
        ))
        val valid = chunk(byteArrayOf(10), 0, 2, 2)
        val wrongHeader = valid.copyOf().also { it[0] = 0; it[2] = 0 }
        val wrongFooter = valid.copyOf().also { it[it.lastIndex] = 0; it[2] = 0 }
        assertEquals(ProcessorResult.Rejected(RaspberryPiProcessingStage.ENVELOPE, "HEADER_MISMATCH"), receive(processor, wrongHeader))
        assertEquals(ProcessorResult.Rejected(RaspberryPiProcessingStage.ENVELOPE, "FOOTER_MISMATCH"), receive(processor, wrongFooter))
        assertEquals(ProcessorResult.Rejected(RaspberryPiProcessingStage.ENVELOPE, "DATAGRAM_TOO_SHORT"), receive(processor, byteArrayOf(1, 2)))
        assertEquals(0, payloadViews)
        assertEquals(0, decodes)
        assertEquals(0, processor.inFlightFrameCount)
        assertEquals(ProcessorResult.Pending, receive(processor, valid))
        assertEquals(1, payloadViews)
        assertEquals(0, decodes)
        val accepted = receive(processor, chunk(byteArrayOf(20), 1, 2, 2)) as ProcessorResult.Accepted
        accepted.input.frame.close()
        assertEquals(1, decodes)
    }

    @Test
    fun productionRejectsEveryCompatibilityStreamExceptOne() {
        listOf(0, 2, 255).forEach { stream ->
            val processor = processor()
            val packet = chunk(byteArrayOf(10, 20), 0, 1, 2).also { it[2 + 6] = stream.toByte() }
            assertEquals(ProcessorResult.Rejected(RaspberryPiProcessingStage.TRANSPORT, "INVALID_TRANSPORT_HEADER"), receive(processor, packet))
            assertEquals(0, processor.bufferedReassemblyBytes)
        }
    }

    @Test
    fun metadataPreservesSourceTimestampAndFirstArrivalWhileSequenceCountsImages() {
        val processor = processor()
        val first = chunk(byteArrayOf(20), 1, 2, 2, timestamp = 345)
        assertEquals(ProcessorResult.Pending, receive(processor, first, monotonic = 100, epoch = 200))
        val result = receive(processor, chunk(byteArrayOf(10), 0, 2, 2, timestamp = 345), monotonic = 110, epoch = 210) as ProcessorResult.Accepted
        assertEquals(source, result.source)
        assertEquals(345L, result.sourceTimestampMicros)
        assertEquals(110L, result.completedAtMonotonicNanos)
        result.input.frame.use {
            assertEquals(100L, it.receivedAtMonotonicNanos)
            assertEquals(200L, it.capturedAtEpochMillis)
            assertEquals(1L, it.sequence)
        }
        val next = receive(processor, chunk(byteArrayOf(30, 40), 0, 1, 2, frameId = 2u)) as ProcessorResult.Accepted
        next.input.frame.use { assertEquals(2L, it.sequence) }
    }

    @Test
    fun sourcePortsCannotMixChunksAndTimestampConflictDropsTheFrame() {
        val processor = processor()
        assertEquals(ProcessorResult.Pending, receive(processor, chunk(byteArrayOf(10), 0, 2, 2)))
        assertEquals(ProcessorResult.Pending, receive(processor, chunk(byteArrayOf(20), 1, 2, 2), sender = InetSocketAddress("192.0.2.10", 9001)))
        assertEquals(2, processor.inFlightFrameCount)
        assertEquals(ProcessorResult.Rejected(RaspberryPiProcessingStage.REASSEMBLY, "METADATA_CONFLICT"),
            receive(processor, chunk(byteArrayOf(20), 1, 2, 2, timestamp = 999)))
        assertEquals(1, processor.inFlightFrameCount)
    }

    @Test
    fun receiveBackingIsBorrowedAtNonzeroOffsetAndOnlyChunksSurviveReuse() {
        val compiled = CompiledRaspberryPiInputProfile.compile(profile())
        val backing = ByteArray(128) { 99 }
        val processor = RaspberryPiUdpProcessor(CompiledRaspberryPiInputProfile(
            compiled.profile, compiled.envelopeParser,
            TransportDatagramParser(BorrowedPayloadViewFactory { bytes, offset, length ->
                assertSame(backing, bytes)
                assertEquals(36, offset)
                assertEquals(1, length)
                BorrowedPayloadView(bytes, offset, length)
            }), compiled.reassembler, compiled.decoder,
        ))
        val first = chunk(byteArrayOf(10), 0, 2, 2)
        first.copyInto(backing)
        assertEquals(ProcessorResult.Pending, receive(processor, backing, size = first.size))
        backing.fill(77)
        assertEquals(1, processor.bufferedReassemblyBytes)
        val second = chunk(byteArrayOf(20), 1, 2, 2)
        second.copyInto(backing)
        val result = receive(processor, backing, size = second.size) as ProcessorResult.Accepted
        backing.fill(88)
        result.input.frame.use {
            assertEquals(0xff0a0a0a.toInt(), it.pixels[0])
            assertEquals(0xff141414.toInt(), it.pixels[1])
        }
    }

    @Test
    fun expiredReceiveLeaseCannotBeConsumed() {
        val bytes = chunk(byteArrayOf(10, 20), 0, 1, 2)
        val borrowed = BorrowedDatagramBytes(bytes, bytes.size)
        borrowed.invalidate()
        assertThrows(IllegalStateException::class.java) { processor().process(borrowed, metadata()) }
    }

    @Test
    fun decoderFailuresAreReportedAfterCompleteReassemblyWithoutRetainedChunks() {
        val processor = processor()
        assertEquals(ProcessorResult.Rejected(RaspberryPiProcessingStage.DECODER, "INVALID_PAYLOAD_SIZE"),
            receive(processor, chunk(byteArrayOf(10), 0, 1, 1)))
        assertEquals(0, processor.bufferedReassemblyBytes)
        val malformedJpeg = processor(ImagePayloadProfile.Jpeg(4096))
        assertEquals(ProcessorResult.Rejected(RaspberryPiProcessingStage.DECODER, "MALFORMED_PAYLOAD"),
            receive(malformedJpeg, chunk(byteArrayOf(10, 20), 0, 1, 2)))
    }

    @Test
    fun defaultAndUnsignedChunkCeilingCompileWithoutIncreasingBufferedBytes() {
        CompiledRaspberryPiInputProfile.compile(RaspberryPiUdpInputProfile.defaults())
        val profile = profile().copy(transportLimits = profile().transportLimits.copy(maxChunkCount = 65_535))
        val processor = RaspberryPiUdpProcessor(CompiledRaspberryPiInputProfile.compile(profile))
        assertEquals(ProcessorResult.Pending, receive(processor, chunk(byteArrayOf(10), 65_534, 65_535, 2)))
        assertEquals(1, processor.bufferedReassemblyBytes)
        assertEquals(1, processor.inFlightFrameCount)
    }

    @Test
    fun compiledEnvelopeNormalizesEditableValuesAndRejectsInvalidProfiles() {
        val profile = profile().copy(envelope = DatagramEnvelopeProfile("ab02", "ef03"))
        val compiled = CompiledRaspberryPiInputProfile.compile(profile)
        assertEquals(DatagramEnvelopeProfile("AB 02", "EF 03"), compiled.profile.envelope)
        val packet = chunk(byteArrayOf(10, 20), 0, 1, 2).also {
            it[0] = 0xab.toByte(); it[1] = 2; it[it.size - 2] = 0xef.toByte(); it[it.lastIndex] = 3
        }
        val accepted = receive(RaspberryPiUdpProcessor(compiled), packet) as ProcessorResult.Accepted
        accepted.input.frame.close()
        assertThrows(IllegalArgumentException::class.java) {
            CompiledRaspberryPiInputProfile.compile(profile.copy(envelope = DatagramEnvelopeProfile("AA", "0D 0A")))
        }
    }

    @Test
    fun neutralTransportOffsetOverloadKeepsAbsolutePayloadViewAndValidatesRange() {
        val packet = chunk(byteArrayOf(10, 20), 0, 1, 2)
        val parser = TransportDatagramParser()
        val result = parser.parse(packet, 2, packet.size - 4, profile().transportLimits) as TransportParseResult.Accepted
        result.datagram.payload.withBytes { bytes, offset, length ->
            assertSame(packet, bytes)
            assertEquals(36, offset)
            assertEquals(2, length)
            assertEquals(10.toByte(), bytes[offset])
        }
        listOf(-1 to 34, Int.MAX_VALUE to 34, 2 to Int.MAX_VALUE).forEach { (offset, length) ->
            assertTrue(parser.parse(packet, offset, length, profile().transportLimits) is TransportParseResult.Rejected)
        }
    }

    private fun profile(payload: ImagePayloadProfile = ImagePayloadProfile.Gray8(2, 1)) =
        RaspberryPiUdpInputProfile.defaults().copy(payload = payload,
            transportLimits = TransportLimits(maxFrameBytes = 4096, maxChunkPayloadBytes = 1024))

    private fun processor(payload: ImagePayloadProfile = ImagePayloadProfile.Gray8(2, 1)) =
        RaspberryPiUdpProcessor(CompiledRaspberryPiInputProfile.compile(profile(payload)))

    private fun metadata(monotonic: Long = 10, epoch: Long = 20, sender: InetSocketAddress = source) =
        ReceivedDatagramMetadata(sender, monotonic, epoch)

    private fun receive(processor: RaspberryPiUdpProcessor, bytes: ByteArray, monotonic: Long = 10,
        epoch: Long = 20, sender: InetSocketAddress = source, size: Int = bytes.size): ProcessorResult {
        val borrowed = BorrowedDatagramBytes(bytes, size)
        return try { processor.process(borrowed, metadata(monotonic, epoch, sender)) }
        finally { borrowed.invalidate() }
    }

    private fun chunk(payload: ByteArray, index: Int, count: Int, total: Int, frameId: ULong = 1u,
        timestamp: Long = 123): ByteArray = byteArrayOf(0xaa.toByte(), 1) + StructuredTransportCodec.encode(
        StructuredTransportHeader(1u.toUByte(), frameId, index, count, timestamp, payload.size, total.toLong()), payload,
    ) + byteArrayOf(13, 10)
}
