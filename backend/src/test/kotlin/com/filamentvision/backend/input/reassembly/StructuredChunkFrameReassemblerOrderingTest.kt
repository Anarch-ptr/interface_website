package com.filamentvision.backend.input.reassembly

import com.filamentvision.backend.input.profile.UdpCameraId
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.InetSocketAddress

class StructuredChunkFrameReassemblerOrderingTest {
    private val sourceA = InetSocketAddress("127.0.0.1", 9_000)
    private val sourceB = InetSocketAddress("127.0.0.1", 9_001)

    @Test
    fun completesOutOfOrderChunksInIndexOrder() {
        val reassembler = StructuredChunkFrameReassembler()
        assertEquals(
            FrameReassemblyResult.Pending,
            reassembler.accept(chunk(1, 1, 2, byteArrayOf(3, 4)).copy(receivedAtMonotonicNanos = 10)),
        )
        val result = reassembler.accept(
            chunk(1, 0, 2, byteArrayOf(1, 2)).copy(receivedAtMonotonicNanos = 11),
        ) as FrameReassemblyResult.Completed

        assertArrayEquals(byteArrayOf(1, 2, 3, 4), result.frame.payload)
        assertEquals(10, result.frame.receivedAtMonotonicNanos)
        assertEquals(11, result.frame.completedAtMonotonicNanos)
        assertEquals(77L, result.frame.sourceTimestampMicros)
        assertEquals(0, reassembler.inFlightFrameCount)
    }

    @Test
    fun identicalDuplicateIsIgnoredButConflictingDuplicateDropsFrame() {
        val reassembler = StructuredChunkFrameReassembler()
        assertEquals(FrameReassemblyResult.Pending, reassembler.accept(chunk(2, 0, 2, byteArrayOf(1))))
        assertEquals(FrameReassemblyResult.Pending, reassembler.accept(chunk(2, 0, 2, byteArrayOf(1))))
        assertEquals(1, reassembler.identicalDuplicateChunks)

        val dropped = reassembler.accept(chunk(2, 0, 2, byteArrayOf(9)))
        assertEquals(FrameReassemblyResult.Dropped(FrameDropReason.CONFLICTING_DUPLICATE), dropped)
        assertEquals(1, reassembler.conflictingDuplicateChunks)
        assertEquals(0, reassembler.inFlightFrameCount)
    }

    @Test
    fun interleavedFrameIdsAndSourceEndpointsRemainIndependent() {
        val reassembler = StructuredChunkFrameReassembler()
        reassembler.accept(chunk(3, 0, 2, byteArrayOf(1), sourceA))
        reassembler.accept(chunk(3, 0, 2, byteArrayOf(5), sourceB))
        reassembler.accept(chunk(4, 0, 2, byteArrayOf(7), sourceA))

        val a3 = reassembler.accept(chunk(3, 1, 2, byteArrayOf(2), sourceA)) as FrameReassemblyResult.Completed
        val b3 = reassembler.accept(chunk(3, 1, 2, byteArrayOf(6), sourceB)) as FrameReassemblyResult.Completed
        val a4 = reassembler.accept(chunk(4, 1, 2, byteArrayOf(8), sourceA)) as FrameReassemblyResult.Completed
        assertArrayEquals(byteArrayOf(1, 2), a3.frame.payload)
        assertArrayEquals(byteArrayOf(5, 6), b3.frame.payload)
        assertArrayEquals(byteArrayOf(7, 8), a4.frame.payload)
    }

    @Test
    fun inconsistentStructuredMetadataDropsFrame() {
        val reassembler = StructuredChunkFrameReassembler()
        reassembler.accept(chunk(5, 0, 2, byteArrayOf(1)))
        val changedTimestamp = chunk(5, 1, 2, byteArrayOf(2)).copy(sourceTimestampMicros = 78)

        assertEquals(
            FrameReassemblyResult.Dropped(FrameDropReason.METADATA_CONFLICT),
            reassembler.accept(changedTimestamp),
        )
        assertEquals(0, reassembler.inFlightFrameCount)
    }

    @Test
    fun retainedChunksOwnBytesWhenCallerReusesBackingArray() {
        val reassembler = StructuredChunkFrameReassembler()
        val reusable = byteArrayOf(1, 2)
        reassembler.accept(chunk(6, 0, 2, reusable, payloadLength = 1))
        reusable[0] = 9
        val result = reassembler.accept(chunk(6, 1, 2, reusable, payloadOffset = 1, payloadLength = 1)) as FrameReassemblyResult.Completed

        assertArrayEquals(byteArrayOf(1, 2), result.frame.payload)
    }

    private fun chunk(
        frameId: Long,
        chunkIndex: Int,
        chunkCount: Int,
        payload: ByteArray,
        source: InetSocketAddress = sourceA,
        payloadOffset: Int = 0,
        payloadLength: Int = payload.size,
    ) = StructuredChunkInput(
        cameraId = UdpCameraId.A,
        source = source,
        frameId = frameId,
        chunkIndex = chunkIndex,
        chunkCount = chunkCount,
        framePayloadBytes = (chunkCount * payloadLength).toLong(),
        sourceTimestampMicros = 77,
        payload = payload,
        payloadOffset = payloadOffset,
        payloadLength = payloadLength,
        receivedAtMonotonicNanos = (10 + chunkIndex).toLong(),
        receivedAtEpochMillis = (20 + chunkIndex).toLong(),
    )
}
