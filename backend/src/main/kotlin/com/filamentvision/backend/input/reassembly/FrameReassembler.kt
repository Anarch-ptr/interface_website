package com.filamentvision.backend.input.reassembly

import com.filamentvision.backend.input.profile.UdpCameraId
import java.net.InetSocketAddress

sealed interface ReassemblyInput {
    val cameraId: UdpCameraId
    val source: InetSocketAddress
    val receivedAtMonotonicNanos: Long
    val receivedAtEpochMillis: Long
}

data class SingleDatagramInput(
    override val cameraId: UdpCameraId,
    override val source: InetSocketAddress,
    val payload: ByteArray,
    override val receivedAtMonotonicNanos: Long,
    override val receivedAtEpochMillis: Long,
) : ReassemblyInput

data class StructuredChunkInput(
    override val cameraId: UdpCameraId,
    override val source: InetSocketAddress,
    val frameId: Long,
    val chunkIndex: Int,
    val chunkCount: Int,
    val framePayloadBytes: Long,
    val sourceTimestampMicros: Long,
    val payload: ByteArray,
    val payloadOffset: Int = 0,
    val payloadLength: Int = payload.size,
    override val receivedAtMonotonicNanos: Long,
    override val receivedAtEpochMillis: Long,
) : ReassemblyInput {
    init {
        require(chunkCount > 0)
        require(chunkIndex in 0 until chunkCount)
        require(payloadOffset >= 0 && payloadLength >= 0 && payloadOffset <= payload.size - payloadLength)
    }
}

data class CompletedFrame(
    val cameraId: UdpCameraId,
    val source: InetSocketAddress,
    val payload: ByteArray,
    val receivedAtMonotonicNanos: Long,
    val receivedAtEpochMillis: Long,
    val completedAtMonotonicNanos: Long,
    val sourceTimestampMicros: Long?,
)

enum class FrameDropReason {
    OVERSIZED_FRAME,
    CONFLICTING_DUPLICATE,
    METADATA_CONFLICT,
    LATE_CHUNK,
    FRAME_SIZE_MISMATCH,
    BUFFER_BUDGET_EXCEEDED,
    TOO_MANY_CHUNKS,
}

sealed interface FrameReassemblyResult {
    data class Completed(val frame: CompletedFrame) : FrameReassemblyResult
    data class Dropped(val reason: FrameDropReason) : FrameReassemblyResult
    data object Pending : FrameReassemblyResult
}

interface FrameReassembler {
    val inFlightFrameCount: Int
    val bufferedBytes: Long

    fun accept(input: ReassemblyInput): FrameReassemblyResult
}
