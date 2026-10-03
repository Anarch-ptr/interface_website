package com.filamentvision.backend.input.diagnostics

import com.filamentvision.backend.input.profile.TransportMode
import com.filamentvision.backend.input.runtime.UdpProcessingStage
import java.util.concurrent.Executors
import org.junit.Assert.*
import org.junit.Test

class UdpDiagnosticsTest {
    @Test fun processingBackpressureCountsDropsWithoutDecoderFailures() {
        val d = UdpDiagnostics(TransportMode.STRUCTURED_CHUNKED)
        d.rejected(com.filamentvision.backend.input.runtime.RaspberryPiProcessingStage.REASSEMBLY, "PROCESSING_BACKPRESSURE")
        val first = d.snapshot()
        assertEquals(1L, first.processingBackpressureDrops)
        assertEquals(1L, first.framesDropped)
        assertEquals(0L, first.decodeFailures)
        d.rejected(com.filamentvision.backend.input.runtime.RaspberryPiProcessingStage.DECODER, "MALFORMED_PAYLOAD")
        d.rejected(com.filamentvision.backend.input.runtime.RaspberryPiProcessingStage.REASSEMBLY, "PROCESSING_BACKPRESSURE")
        val next = d.snapshot()
        assertEquals(2L, next.processingBackpressureDrops)
        assertEquals(3L, next.framesDropped)
        assertEquals(1L, next.decodeFailures)
        assertEquals(0L, next.preAdmissionDroppedInputs)
        assertEquals(1L, first.processingBackpressureDrops)
    }
    @Test fun singleStreamMetadataAndRejectionStagesAreRetainedWithoutHistory() {
        val d = UdpDiagnostics(TransportMode.STRUCTURED_CHUNKED)
        d.datagramReceived(39, com.filamentvision.backend.input.runtime.ReceivedDatagramMetadata(
            java.net.InetSocketAddress("192.0.2.10", 9000), 12, 1234))
        d.rejected(com.filamentvision.backend.input.runtime.RaspberryPiProcessingStage.ENVELOPE, "HEADER_MISMATCH")
        d.rejected(com.filamentvision.backend.input.runtime.RaspberryPiProcessingStage.ENVELOPE, "FOOTER_MISMATCH")
        d.rejected(com.filamentvision.backend.input.runtime.RaspberryPiProcessingStage.DECODER, "INVALID_PAYLOAD_SIZE")
        d.preAdmissionDropped()
        val s = d.snapshot()
        assertEquals("192.0.2.10", s.lastPacketSourceIpv4)
        assertEquals(9000, s.lastPacketSourcePort)
        assertEquals(1234L, s.lastPacketEpochMillis)
        assertEquals(12L, s.lastPacketMonotonicNanos)
        assertEquals(39, s.bytesReceived)
        assertEquals(1, s.invalidHeaderCount); assertEquals(1, s.invalidFooterCount)
        assertEquals(1, s.decodeFailures); assertEquals(1, s.preAdmissionDroppedInputs)
        assertEquals(4, s.framesDropped)
    }
    @Test fun snapshotsAreBoundedAndChunkFieldsAreNotApplicableForSingleDatagram() {
        val d = UdpDiagnostics(TransportMode.SINGLE_DATAGRAM)
        d.datagramReceived(120, 10); d.frameCompleted(11)
        d.rejected(UdpProcessingStage.DATAGRAM_FRAMING, "INVALID_HEADER")
        d.rejected(UdpProcessingStage.FRAME_FRAMING, "INVALID_FOOTER")
        d.rejected(UdpProcessingStage.DECODER, "INVALID_PAYLOAD_SIZE")
        d.sourceFiltered(); d.datagramTooLargeOrTruncated()
        val s = d.snapshot()
        assertEquals(1, s.datagramsReceived); assertEquals(120, s.bytesReceived)
        assertEquals(1, s.framesCompleted); assertEquals(3, s.framesDropped)
        assertEquals(1, s.invalidHeaderCount); assertEquals(1, s.invalidFooterCount)
        assertEquals(1, s.invalidPayloadSizeCount); assertEquals(1, s.decodeFailures)
        assertEquals(1, s.sourceFilteredDatagrams); assertEquals(1, s.oversizedOrTruncatedDatagrams)
        assertEquals(10L, s.lastPacketMonotonicNanos); assertEquals(11L, s.lastValidFrameMonotonicNanos)
        assertNull(s.duplicateChunks); assertNull(s.reassemblyTimeouts)
    }

    @Test fun structuredCountersAreAtomicAndCameraInstancesStayIsolated() {
        val a = UdpDiagnostics(TransportMode.STRUCTURED_CHUNKED)
        val b = UdpDiagnostics(TransportMode.STRUCTURED_CHUNKED)
        val pool = Executors.newFixedThreadPool(4)
        repeat(1000) { pool.submit { a.datagramReceived(5, it.toLong()); a.chunkDuplicate(); a.chunkOutOfOrder() } }
        pool.shutdown(); assertTrue(pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS))
        a.frameStarted(); a.missingChunk(); a.reassemblyTimeout(); a.lateChunk()
        val sa = a.snapshot(); val sb = b.snapshot()
        assertEquals(1000, sa.datagramsReceived); assertEquals(5000, sa.bytesReceived)
        assertEquals(1000L, sa.duplicateChunks); assertEquals(1000L, sa.outOfOrderChunks)
        assertEquals(1L, sa.framesStarted); assertEquals(1L, sa.missingChunks)
        assertEquals(1L, sa.reassemblyTimeouts); assertEquals(1L, sa.lateChunks)
        assertEquals(0, sb.datagramsReceived)
    }
}
