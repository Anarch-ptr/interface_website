package com.filamentvision.backend.input.reassembly

import com.filamentvision.backend.input.profile.UdpCameraId
import java.net.InetSocketAddress

class StructuredChunkFrameReassembler : FrameReassembler {
    private data class FrameKey(
        val cameraId: UdpCameraId,
        val source: InetSocketAddress,
        val frameId: Long,
    )

    private data class InFlightFrame(
        val chunkCount: Int,
        val framePayloadBytes: Long,
        val sourceTimestampMicros: Long,
        val firstReceivedAtMonotonicNanos: Long,
        val firstReceivedAtEpochMillis: Long,
        val chunks: Array<ByteArray?>,
    )

    private val frames = linkedMapOf<FrameKey, InFlightFrame>()
    private var retainedBytes = 0L

    var identicalDuplicateChunks: Long = 0
        private set
    var conflictingDuplicateChunks: Long = 0
        private set

    override val inFlightFrameCount: Int get() = frames.size
    override val bufferedBytes: Long get() = retainedBytes

    override fun accept(input: ReassemblyInput): FrameReassemblyResult {
        require(input is StructuredChunkInput) { "Structured reassembler requires StructuredChunkInput" }
        val key = FrameKey(input.cameraId, input.source, input.frameId)
        val frame = frames.getOrPut(key) {
            InFlightFrame(
                chunkCount = input.chunkCount,
                framePayloadBytes = input.framePayloadBytes,
                sourceTimestampMicros = input.sourceTimestampMicros,
                firstReceivedAtMonotonicNanos = input.receivedAtMonotonicNanos,
                firstReceivedAtEpochMillis = input.receivedAtEpochMillis,
                chunks = arrayOfNulls(input.chunkCount),
            )
        }

        if (
            frame.chunkCount != input.chunkCount ||
            frame.framePayloadBytes != input.framePayloadBytes ||
            frame.sourceTimestampMicros != input.sourceTimestampMicros
        ) {
            remove(key, frame)
            return FrameReassemblyResult.Dropped(FrameDropReason.METADATA_CONFLICT)
        }

        val ownedChunk = input.payload.copyOfRange(input.payloadOffset, input.payloadOffset + input.payloadLength)
        val prior = frame.chunks[input.chunkIndex]
        if (prior != null) {
            return if (prior.contentEquals(ownedChunk)) {
                identicalDuplicateChunks++
                FrameReassemblyResult.Pending
            } else {
                conflictingDuplicateChunks++
                remove(key, frame)
                FrameReassemblyResult.Dropped(FrameDropReason.CONFLICTING_DUPLICATE)
            }
        }

        frame.chunks[input.chunkIndex] = ownedChunk
        retainedBytes += ownedChunk.size
        if (frame.chunks.any { it == null }) return FrameReassemblyResult.Pending

        val payloadSize = frame.chunks.sumOf { it!!.size }
        val payload = ByteArray(payloadSize)
        var offset = 0
        frame.chunks.forEach { chunk ->
            chunk!!
            chunk.copyInto(payload, offset)
            offset += chunk.size
        }
        remove(key, frame)
        return FrameReassemblyResult.Completed(
            CompletedFrame(
                cameraId = input.cameraId,
                source = input.source,
                payload = payload,
                receivedAtMonotonicNanos = frame.firstReceivedAtMonotonicNanos,
                receivedAtEpochMillis = frame.firstReceivedAtEpochMillis,
                completedAtMonotonicNanos = input.receivedAtMonotonicNanos,
                sourceTimestampMicros = frame.sourceTimestampMicros,
            ),
        )
    }

    private fun remove(key: FrameKey, frame: InFlightFrame) {
        if (frames.remove(key) != null) {
            retainedBytes -= frame.chunks.sumOf { it?.size?.toLong() ?: 0L }
        }
    }
}
