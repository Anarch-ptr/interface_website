package com.filamentvision.backend.input.inspector

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.filamentvision.backend.api.dto.toDto
import com.filamentvision.backend.input.decoder.*
import com.filamentvision.backend.input.profile.*
import com.filamentvision.backend.input.runtime.*
import com.filamentvision.backend.input.transport.*
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import org.junit.Assert.*
import org.junit.Test

class PacketInspectorTest {
    private var now = 1_000L
    private val inspector = PacketInspector(clockMillis = { now }, leaseDurationMillis = 500)
    private val profile = RaspberryPiUdpInputProfile.defaults().copy(payload = ImagePayloadProfile.Gray8(2, 1))
    private val metadata = ReceivedDatagramMetadata(InetSocketAddress("192.0.2.10", 9000), 123, 456)

    @Test fun leasesShareOneLatestAndReleaseOrExpiryDiscardsAllOwnedBytes() {
        val first = inspector.createLease(128)
        val second = inspector.createLease(64)
        capture(byteArrayOf(1, 2))
        assertEquals(inspector.latest(first.id)!!.sequence, inspector.latest(second.id)!!.sequence)
        assertTrue(inspector.releaseLease(first.id))
        assertFalse(inspector.releaseLease(first.id))
        assertEquals(1, inspector.retainedSnapshotCount)
        assertTrue(inspector.releaseLease(second.id))
        assertEquals(0, inspector.retainedPreviewBytes)
        assertNull(inspector.latest(second.id))
        val expiring = inspector.createLease(256)
        capture(ByteArray(600))
        now += 500
        assertNull(inspector.latest(expiring.id))
        assertFalse(inspector.captureEnabled())
        assertEquals(0, inspector.retainedSnapshotCount)
        assertEquals(0, inspector.retainedPreviewBytes)
    }

    @Test fun onlyRequestedSizesAreAcceptedAndEachLeaseBoundsTheSharedSnapshot() {
        for (invalid in listOf(-1, 0, 63, 65, 512, 513, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { inspector.createLease(invalid) }
        }
        val largest = inspector.createLease(256)
        for (size in listOf(64, 128, 256)) {
            val lease = inspector.createLease(size)
            capture(ByteArray(700))
            assertEquals(size, inspector.latest(lease.id)!!.raw.preview.size)
            assertEquals(64, inspector.latest(lease.id, 64)!!.raw.preview.size)
            assertThrows(IllegalArgumentException::class.java) { inspector.latest(lease.id, 512) }
            inspector.releaseLease(lease.id)
        }
        inspector.releaseLease(largest.id)
    }

    @Test fun rawIsCompleteEnvelopeWhileTransportUsesStrippedHeaderAndPayloadRange() {
        val lease = inspector.createLease(64)
        val processor = processor()
        val packet = wire(byteArrayOf(7, 8), frameId = ULong.MAX_VALUE)
        (receive(processor, packet) as ProcessorResult.Accepted).input.frame.close()
        val snapshot = inspector.latest(lease.id)!!
        assertArrayEquals(packet, snapshot.raw.preview)
        assertEquals(40, snapshot.raw.datagramSize)
        assertEquals("192.0.2.10", snapshot.raw.sourceIpv4)
        assertEquals(9000, snapshot.raw.sourcePort)
        assertEquals(5000, snapshot.raw.backendListenPort)
        assertEquals("AA 01", snapshot.raw.headerHex)
        assertEquals("0D 0A", snapshot.raw.footerHex)
        assertEquals(true, snapshot.raw.headerMatched)
        assertEquals(true, snapshot.raw.footerMatched)
        val transport = snapshot.raw.transport!!
        assertEquals(1, transport.protocolVersion)
        assertEquals(1, transport.compatibilityStreamId)
        assertEquals("18446744073709551615", transport.frameId)
        assertEquals(0, transport.chunkIndex)
        assertEquals(1, transport.chunkCount)
        assertEquals(345L, transport.sourceTimestampMicros)
        assertEquals(2, transport.payloadLength)
        assertEquals(2L, transport.framePayloadBytes)
        val dto = snapshot.toDto()
        assertEquals("07 08", dto.rawDatagram.transport!!.hexPreview)
        assertEquals("VALID", dto.rawDatagram.transport!!.status)
        assertTrue(dto.rawDatagram.hexPreview.endsWith("07 08 0D 0A"))
        val json = ObjectMapper().valueToTree<JsonNode>(dto)
        assertFalse(json.has("cameraId"))
        assertFalse(json["parsedFrame"].has("applicationHeaderBytes"))
        assertFalse(json["parsedFrame"].has("applicationFooterBytes"))
        assertEquals(2, json["parsedFrame"]["completedFrameBytes"].asInt())
    }

    @Test fun envelopeAndTransportFailuresRemainRawAndNeverReachReassemblyOrDecoder() {
        val lease = inspector.createLease(64)
        var views = 0
        var decodes = 0
        val compiled = CompiledRaspberryPiInputProfile.compile(profile)
        val processor = RaspberryPiUdpProcessor(CompiledRaspberryPiInputProfile(compiled.profile, compiled.envelopeParser,
            TransportDatagramParser(BorrowedPayloadViewFactory { b, o, n -> views++; BorrowedPayloadView(b, o, n) }),
            compiled.reassembler, PayloadDecoder { decodes++; compiled.decoder.decode(it) }), inspector = inspector)
        val valid = wire(byteArrayOf(7, 8))
        val cases = listOf(
            Triple(valid.copyOf().also { it[0] = 0; it[2] = 0 }, RawParseStatus.HEADER_MISMATCH, false to true),
            Triple(valid.copyOf().also { it[it.lastIndex] = 0; it[2] = 0 }, RawParseStatus.FOOTER_MISMATCH, true to false),
            Triple(byteArrayOf(1), RawParseStatus.DATAGRAM_TOO_SHORT, false to false),
            Triple(valid.copyOf().also { it[2] = 0 }, RawParseStatus.INVALID_TRANSPORT_HEADER, true to true),
        )
        for ((bytes, status, flags) in cases) {
            assertTrue(receive(processor, bytes) is ProcessorResult.Rejected)
            val snapshot = inspector.latest(lease.id)!!
            assertEquals(status, snapshot.raw.status)
            assertEquals(flags.first, snapshot.raw.headerMatched)
            assertEquals(flags.second, snapshot.raw.footerMatched)
            assertNull(snapshot.raw.transport)
            assertNull(snapshot.parsedFrame)
            assertEquals(0, processor.inFlightFrameCount)
        }
        assertEquals(0, views)
        assertEquals(0, decodes)
    }

    @Test fun completedFramesExposeAllFourFormatsDimensionsAndDecodeFailures() {
        val lease = inspector.createLease(128)
        val jpeg = ByteArrayOutputStream().use { output ->
            assertTrue(ImageIO.write(BufferedImage(2, 1, BufferedImage.TYPE_INT_RGB), "jpg", output))
            output.toByteArray()
        }
        val cases = listOf(
            Triple(ImagePayloadProfile.Jpeg(4096), jpeg, "JPEG"),
            Triple(ImagePayloadProfile.Gray8(2, 1), byteArrayOf(7, 8), "GRAY8"),
            Triple(ImagePayloadProfile.Binary(2, 1, BinaryPacking.BYTE_PER_PIXEL), byteArrayOf(0, 1), "BINARY"),
            Triple(ImagePayloadProfile.Rgb888(2, 1, RgbChannelOrder.RGB), byteArrayOf(1, 2, 3, 4, 5, 6), "RGB888"),
        )
        for ((payload, bytes, format) in cases) {
            val processor = processor(payload)
            val half = bytes.size / 2
            assertEquals(ProcessorResult.Pending, receive(processor, wire(bytes.copyOfRange(half, bytes.size), 1, 2, bytes.size)))
            assertNull(inspector.latest(lease.id)!!.parsedFrame)
            val accepted = receive(processor, wire(bytes.copyOfRange(0, half), 0, 2, bytes.size)) as ProcessorResult.Accepted
            accepted.input.frame.close()
            val frame = inspector.latest(lease.id)!!.parsedFrame!!
            assertEquals(format, frame.payloadFormat)
            assertEquals(2, frame.width)
            assertEquals(1, frame.height)
            assertEquals("VALID", frame.decodeResult)
            assertEquals(bytes.size, frame.completedFrameBytes)
            assertArrayEquals(bytes.copyOf(minOf(128, bytes.size)), frame.preview)
            val rejected = receive(processor, wire(byteArrayOf(1), frameId = 2u)) as ProcessorResult.Rejected
            assertEquals(RaspberryPiProcessingStage.DECODER, rejected.stage)
            assertEquals(rejected.reason, inspector.latest(lease.id)!!.parsedFrame!!.decodeResult)
        }
    }

    @Test fun threeViewsRetainAtMost512UniqueBytesAndBackingOrPollingMutationCannotChangeThem() {
        val lease = inspector.createLease(256)
        val payload = ByteArray(600) { it.toByte() }
        val packet = wire(payload)
        val processor = processor(ImagePayloadProfile.Gray8(600, 1))
        (receive(processor, packet) as ProcessorResult.Accepted).input.frame.close()
        val before = inspector.latest(lease.id)!!
        assertEquals(256, before.raw.preview.size)
        assertEquals(256, before.parsedFrame!!.preview.size)
        assertEquals(220, before.toDto().rawDatagram.transport!!.hexPreview.split(' ').size)
        assertEquals(512, inspector.retainedPreviewBytes)
        assertEquals(2L, inspector.previewCopies)
        packet.fill(99); payload.fill(99)
        val expectedRaw = before.raw.preview.copyOf()
        before.raw.preview.fill(77); before.parsedFrame!!.preview.fill(77)
        val after = inspector.latest(lease.id)!!
        assertArrayEquals(expectedRaw, after.raw.preview)
        assertArrayEquals(ByteArray(256) { it.toByte() }, after.parsedFrame!!.preview)
        repeat(100) { capture(byteArrayOf(it.toByte())) }
        assertTrue(inspector.latest(lease.id)!!.sequence > after.sequence)
        assertEquals(1, inspector.retainedSnapshotCount)
        assertEquals(1, inspector.retainedPreviewBytes)
        assertNull(inspector.latest(lease.id)!!.parsedFrame)
        inspector.releaseLease(lease.id)
        assertEquals(0, inspector.retainedPreviewBytes)
    }

    @Test fun disabledOrExpiredObserverCreatesNoCopiesAndDoesNotChangeProcessingResults() {
        val processor = processor()
        repeat(20) { index ->
            capture(byteArrayOf(0), RawParseStatus.SOURCE_IP_MISMATCH)
            receive(processor, byteArrayOf(0))
            (receive(processor, wire(byteArrayOf(7, 8), frameId = index.toULong())) as ProcessorResult.Accepted).input.frame.close()
        }
        assertEquals(0L, inspector.previewCopies)
        assertEquals(0, inspector.retainedSnapshotCount)
        inspector.createLease(64); now += 500
        capture(byteArrayOf(0))
        assertEquals(0L, inspector.previewCopies)
        assertEquals(0, inspector.retainedPreviewBytes)
    }

    @Test fun staleCaptureTokenCannotAttachAfterNewDatagramReleaseOrNewLease() {
        val lease = inspector.createLease(64)
        val old = capture(byteArrayOf(1))
        capture(byteArrayOf(2))
        assertFalse(inspector.captureParsed(old, byteArrayOf(8), profile.payload, "VALID", 2, 1, 123))
        inspector.releaseLease(lease.id)
        val next = inspector.createLease(64)
        capture(byteArrayOf(3))
        assertFalse(inspector.captureParsed(old, byteArrayOf(8), profile.payload, "VALID", 2, 1, 123))
        assertNull(inspector.latest(next.id)!!.parsedFrame)
    }

    @Test fun pollingCongestionDropsOnlyInspectorObservationAndNeverBlocksProcessor() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        var block = false
        val slow = PacketInspector(clockMillis = {
            if (block && Thread.currentThread().name == "inspector-poll") {
                entered.countDown(); check(release.await(3, TimeUnit.SECONDS))
            }; 1_000
        })
        val lease = slow.createLease(64)
        block = true
        val poll = Thread({ slow.latest(lease.id) }, "inspector-poll").also { it.start() }
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            val processor = RaspberryPiUdpProcessor(CompiledRaspberryPiInputProfile.compile(profile), inspector = slow)
            (receive(processor, wire(byteArrayOf(7, 8))) as ProcessorResult.Accepted).input.frame.close()
            assertEquals(1L, release.count)
            assertEquals(0L, slow.previewCopies)
        } finally { release.countDown(); poll.join(3000) }
    }

    private fun processor(payload: ImagePayloadProfile = profile.payload) =
        RaspberryPiUdpProcessor(CompiledRaspberryPiInputProfile.compile(profile.copy(payload = payload)), inspector = inspector)
    private fun capture(bytes: ByteArray, status: RawParseStatus = RawParseStatus.VALID): Long =
        inspector.captureRaw(bytes, bytes.size, metadata, profile, status)
    private fun receive(processor: RaspberryPiUdpProcessor, bytes: ByteArray): ProcessorResult {
        val borrowed = BorrowedDatagramBytes(bytes, bytes.size)
        return try { processor.process(borrowed, metadata) } finally { borrowed.invalidate() }
    }
    private fun wire(payload: ByteArray, index: Int = 0, count: Int = 1, total: Int = payload.size, frameId: ULong = 42u): ByteArray =
        byteArrayOf(0xaa.toByte(), 1) + StructuredTransportCodec.encode(
            StructuredTransportHeader(1u, frameId, index, count, 345, payload.size, total.toLong()), payload) + byteArrayOf(13, 10)
}
