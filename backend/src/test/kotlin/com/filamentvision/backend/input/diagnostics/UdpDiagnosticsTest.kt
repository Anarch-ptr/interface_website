package com.filamentvision.backend.input.diagnostics

import com.filamentvision.backend.input.profile.TransportMode
import com.filamentvision.backend.input.runtime.UdpProcessingStage
import java.util.concurrent.Executors
import org.junit.Assert.*
import org.junit.Test

class UdpDiagnosticsTest {
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
