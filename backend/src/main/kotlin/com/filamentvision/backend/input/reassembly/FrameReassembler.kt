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
