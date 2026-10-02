package com.filamentvision.backend.input.reassembly

import com.filamentvision.backend.input.profile.UdpCameraId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress

class StructuredChunkFrameReassemblerLimitsTest {
    private val source = InetSocketAddress("127.0.0.1", 9_000)

    @Test
    fun missingChunkTimesOutAndLateChunkDoesNotRestartFrame() {
        val r = reassembler(timeout = 100)
        assertEquals(FrameReassemblyResult.Pending, r.accept(chunk(frame = 1, index = 0, count = 2, now = 0)))
        assertEquals(1, r.expire(101))
        assertEquals(0, r.inFlightFrameCount)
        assertEquals(0, r.bufferedBytes)
        assertEquals(
            FrameReassemblyResult.Dropped(FrameDropReason.LATE_CHUNK),
            r.accept(chunk(frame = 1, index = 1, count = 2, now = 102)),
        )
        assertEquals(1, r.lateChunks)
    }

    @Test
    fun tombstonesAreBounded() {
        val r = reassembler(timeout = 100, maxTombstones = 2, maxInFlight = 4)
        repeat(3) { frame ->
            r.accept(chunk(frame = frame.toLong(), index = 0, count = 2, now = 0))
        }
        r.expire(101)
        assertEquals(2, r.tombstoneCount)
    }

    @Test
    fun exactCompletedLengthIsRequiredForStripAndRetainSemantics() {
        val stripped = reassembler()
        stripped.accept(chunk(frame = 2, index = 0, count = 2, bytes = byteArrayOf(1), declared = 2))
        assertTrue(stripped.accept(chunk(frame = 2, index = 1, count = 2, bytes = byteArrayOf(2), declared = 2)) is FrameReassemblyResult.Completed)

        val retained = reassembler()
        retained.accept(chunk(frame = 3, index = 0, count = 2, bytes = byteArrayOf(9, 1), declared = 4))
        assertTrue(retained.accept(chunk(frame = 3, index = 1, count = 2, bytes = byteArrayOf(9, 2), declared = 4)) is FrameReassemblyResult.Completed)

        val mismatch = reassembler()
        mismatch.accept(chunk(frame = 4, index = 0, count = 2, bytes = byteArrayOf(1), declared = 3))
        assertEquals(
            FrameReassemblyResult.Dropped(FrameDropReason.FRAME_SIZE_MISMATCH),
            mismatch.accept(chunk(frame = 4, index = 1, count = 2, bytes = byteArrayOf(2), declared = 3)),
        )
        assertEquals(0, mismatch.bufferedBytes)
    }

    @Test
    fun oldestIncompleteFrameIsEvictedAtInFlightLimit() {
        val r = reassembler(maxInFlight = 2)
        r.accept(chunk(frame = 10, index = 0, count = 2, now = 1))
        r.accept(chunk(frame = 11, index = 0, count = 2, now = 2))
        r.accept(chunk(frame = 12, index = 0, count = 2, now = 3))
        assertEquals(2, r.inFlightFrameCount)
        assertEquals(1, r.inFlightEvictions)
        assertEquals(2, r.bufferedBytes)
    }

    @Test
    fun byteBudgetDropsCurrentFrameAndReleasesAccounting() {
        val r = reassembler(maxBufferedBytes = 2)
        r.accept(chunk(frame = 20, index = 0, count = 2, bytes = byteArrayOf(1, 2), declared = 4))
        assertEquals(
            FrameReassemblyResult.Dropped(FrameDropReason.BUFFER_BUDGET_EXCEEDED),
            r.accept(chunk(frame = 20, index = 1, count = 2, bytes = byteArrayOf(3, 4), declared = 4)),
        )
        assertEquals(0, r.inFlightFrameCount)
        assertEquals(0, r.bufferedBytes)
    }

    @Test
    fun declarationAndChunkCountAreRejectedBeforeFrameAllocation() {
        val r = reassembler(maxFrameBytes = 4, maxChunkCount = 2)
        assertEquals(
            FrameReassemblyResult.Dropped(FrameDropReason.OVERSIZED_FRAME),
            r.accept(chunk(frame = 30, index = 0, count = 1, declared = 5)),
        )
        assertEquals(
            FrameReassemblyResult.Dropped(FrameDropReason.TOO_MANY_CHUNKS),
            r.accept(chunk(frame = 31, index = 0, count = 3, declared = 3)),
        )
        assertEquals(0, r.inFlightFrameCount)
        assertEquals(0, r.bufferedBytes)
    }

    @Test
    fun declarationDoesNotCauseEagerPayloadAllocation() {
        val r = reassembler(maxFrameBytes = 1_000_000, maxBufferedBytes = 32)
        r.accept(chunk(frame = 40, index = 0, count = 2, bytes = byteArrayOf(1), declared = 1_000_000))
        assertEquals(1, r.bufferedBytes)
    }

    private fun reassembler(
        maxInFlight: Int = 4,
        maxFrameBytes: Long = 1_024,
        maxChunkCount: Int = 16,
        maxBufferedBytes: Long = 1_024,
        timeout: Long = 1_000,
        maxTombstones: Int = 8,
    ) = StructuredChunkFrameReassembler(
        maxInFlightFrames = maxInFlight,
        maxFrameBytes = maxFrameBytes,
        maxChunkCount = maxChunkCount,
        maxBufferedBytes = maxBufferedBytes,
        frameTimeoutNanos = timeout,
        maxTombstones = maxTombstones,
    )

    private fun chunk(
        frame: Long,
        index: Int,
        count: Int,
        bytes: ByteArray = byteArrayOf(index.toByte()),
        declared: Long = count.toLong(),
        now: Long = index.toLong(),
    ) = StructuredChunkInput(
        cameraId = UdpCameraId.A,
        source = source,
        frameId = frame,
        chunkIndex = index,
        chunkCount = count,
        framePayloadBytes = declared,
        sourceTimestampMicros = 7,
        payload = bytes,
        receivedAtMonotonicNanos = now,
        receivedAtEpochMillis = now,
    )
}
