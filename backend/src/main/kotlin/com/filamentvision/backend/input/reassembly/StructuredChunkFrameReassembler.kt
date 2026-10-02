package com.filamentvision.backend.input.reassembly

import com.filamentvision.backend.input.profile.UdpCameraId
import java.net.InetSocketAddress

class StructuredChunkFrameReassembler(
    private val maxInFlightFrames: Int = 4,
    private val maxFrameBytes: Long = 8L * 1024 * 1024,
    private val maxChunkCount: Int = 4_096,
    private val maxBufferedBytes: Long = 32L * 1024 * 1024,
    private val frameTimeoutNanos: Long = 1_000_000_000,
    private val maxTombstones: Int = 64,
    private val observer: StructuredReassemblyObserver = object : StructuredReassemblyObserver {},
) : FrameReassembler {
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
        var receivedChunkCount: Int = 0,
    )

    private val frames = linkedMapOf<FrameKey, InFlightFrame>()
    private val timeoutTombstones = linkedMapOf<FrameKey, Long>()
    private var retainedBytes = 0L

    init {
        require(maxInFlightFrames in 1..32)
        require(maxFrameBytes in 1..HARD_MAX_FRAME_BYTES)
        require(maxChunkCount in 1..HARD_MAX_CHUNK_COUNT)
        require(maxBufferedBytes in 1..HARD_MAX_BUFFERED_BYTES)
        require(frameTimeoutNanos > 0)
        require(maxTombstones > 0)
    }

    var identicalDuplicateChunks: Long = 0
        private set
    var conflictingDuplicateChunks: Long = 0
        private set
    var lateChunks: Long = 0
        private set
    var reassemblyTimeouts: Long = 0
        private set
    var inFlightEvictions: Long = 0
        private set
    var bufferBudgetDrops: Long = 0
        private set

    override val inFlightFrameCount: Int get() = frames.size
    override val bufferedBytes: Long get() = retainedBytes
    val tombstoneCount: Int get() = timeoutTombstones.size

    fun expire(nowMonotonicNanos: Long): Int {
        purgeOldTombstones(nowMonotonicNanos)
        val expired = frames.entries
            .filter { nowMonotonicNanos - it.value.firstReceivedAtMonotonicNanos > frameTimeoutNanos }
            .map { it.key to it.value }
        expired.forEach { (key, frame) ->
            remove(key, frame)
            addTombstone(key, nowMonotonicNanos)
            reassemblyTimeouts++
            observer.onTimeout(frame.chunks.count { it == null })
        }
        return expired.size
    }

    override fun accept(input: ReassemblyInput): FrameReassemblyResult {
        require(input is StructuredChunkInput) { "Structured reassembler requires StructuredChunkInput" }
        expire(input.receivedAtMonotonicNanos)
        val key = FrameKey(input.cameraId, input.source, input.frameId)
        if (timeoutTombstones.containsKey(key)) {
            lateChunks++
            observer.onLateChunk()
            return FrameReassemblyResult.Dropped(FrameDropReason.LATE_CHUNK)
        }
        if (input.framePayloadBytes > maxFrameBytes) {
            return FrameReassemblyResult.Dropped(FrameDropReason.OVERSIZED_FRAME)
        }
        if (input.chunkCount > maxChunkCount) {
            return FrameReassemblyResult.Dropped(FrameDropReason.TOO_MANY_CHUNKS)
        }
        if (!frames.containsKey(key) && frames.size >= maxInFlightFrames) {
            val oldest = frames.entries.first()
            observer.onEvicted(oldest.value.chunks.count { it == null })
            remove(oldest.key, oldest.value)
            inFlightEvictions++
        }
        val isNewFrame = !frames.containsKey(key)
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
        if (isNewFrame) observer.onFrameStarted()

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
            observer.onDuplicateChunk()
            return if (prior.contentEquals(ownedChunk)) {
                identicalDuplicateChunks++
                FrameReassemblyResult.Pending
            } else {
                conflictingDuplicateChunks++
                remove(key, frame)
                FrameReassemblyResult.Dropped(FrameDropReason.CONFLICTING_DUPLICATE)
            }
        }

        if (input.chunkIndex != frame.receivedChunkCount) observer.onOutOfOrderChunk()

        if (!makeRoomFor(key, frame, ownedChunk.size.toLong())) {
            remove(key, frame)
            bufferBudgetDrops++
            return FrameReassemblyResult.Dropped(FrameDropReason.BUFFER_BUDGET_EXCEEDED)
        }

        frame.chunks[input.chunkIndex] = ownedChunk
        frame.receivedChunkCount++
        retainedBytes += ownedChunk.size
        val currentFrameBytes = frame.chunks.sumOf { it?.size?.toLong() ?: 0L }
        if (currentFrameBytes > frame.framePayloadBytes) {
            remove(key, frame)
            return FrameReassemblyResult.Dropped(FrameDropReason.FRAME_SIZE_MISMATCH)
        }
        if (frame.chunks.any { it == null }) return FrameReassemblyResult.Pending

        val payloadSize = frame.chunks.sumOf { it!!.size }
        if (payloadSize.toLong() != frame.framePayloadBytes) {
            remove(key, frame)
            return FrameReassemblyResult.Dropped(FrameDropReason.FRAME_SIZE_MISMATCH)
        }
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

    private fun makeRoomFor(currentKey: FrameKey, currentFrame: InFlightFrame, incomingBytes: Long): Boolean {
        if (incomingBytes > maxBufferedBytes) return false
        while (retainedBytes + incomingBytes > maxBufferedBytes) {
            val oldestOther = frames.entries.firstOrNull { it.key != currentKey } ?: return false
            observer.onEvicted(oldestOther.value.chunks.count { it == null })
            remove(oldestOther.key, oldestOther.value)
            inFlightEvictions++
        }
        return frames[currentKey] === currentFrame
    }

    private fun addTombstone(key: FrameKey, nowMonotonicNanos: Long) {
        timeoutTombstones[key] = nowMonotonicNanos
        while (timeoutTombstones.size > maxTombstones) {
            timeoutTombstones.remove(timeoutTombstones.keys.first())
        }
    }

    private fun purgeOldTombstones(nowMonotonicNanos: Long) {
        val expiredKeys = timeoutTombstones
            .filterValues { nowMonotonicNanos - it > frameTimeoutNanos }
            .keys
            .toList()
        expiredKeys.forEach(timeoutTombstones::remove)
    }

    private companion object {
        const val HARD_MAX_FRAME_BYTES = 32L * 1024 * 1024
        const val HARD_MAX_CHUNK_COUNT = 8_192
        const val HARD_MAX_BUFFERED_BYTES = 64L * 1024 * 1024
    }
}

interface StructuredReassemblyObserver {
    fun onFrameStarted() = Unit
    fun onDuplicateChunk() = Unit
    fun onOutOfOrderChunk() = Unit
    fun onLateChunk() = Unit
    fun onTimeout(missingChunks: Int) = Unit
    fun onEvicted(missingChunks: Int) = Unit
}
