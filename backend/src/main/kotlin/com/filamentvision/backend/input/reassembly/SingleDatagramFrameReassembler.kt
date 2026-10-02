package com.filamentvision.backend.input.reassembly

class SingleDatagramFrameReassembler(
    private val maxFrameBytes: Int,
) : FrameReassembler {
    init {
        require(maxFrameBytes > 0)
    }

    override val inFlightFrameCount: Int get() = 0
    override val bufferedBytes: Long get() = 0

    override fun accept(input: ReassemblyInput): FrameReassemblyResult {
        require(input is SingleDatagramInput) { "Single-datagram reassembler requires SingleDatagramInput" }
        if (input.payload.size > maxFrameBytes) {
            return FrameReassemblyResult.Dropped(FrameDropReason.OVERSIZED_FRAME)
        }
        return FrameReassemblyResult.Completed(
            CompletedFrame(
                cameraId = input.cameraId,
                source = input.source,
                payload = input.payload,
                receivedAtMonotonicNanos = input.receivedAtMonotonicNanos,
                receivedAtEpochMillis = input.receivedAtEpochMillis,
                completedAtMonotonicNanos = input.receivedAtMonotonicNanos,
                sourceTimestampMicros = null,
            ),
        )
    }
}
