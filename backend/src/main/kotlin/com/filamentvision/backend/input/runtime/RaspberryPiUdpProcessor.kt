package com.filamentvision.backend.input.runtime

import com.filamentvision.backend.adapter.DecodedFrameAdapter
import com.filamentvision.backend.input.decoder.DecodedInput
import com.filamentvision.backend.input.decoder.PayloadDecodeResult
import com.filamentvision.backend.input.framing.EnvelopeParseResult
import com.filamentvision.backend.input.profile.UdpCameraId
import com.filamentvision.backend.input.reassembly.*
import com.filamentvision.backend.input.transport.BorrowedDatagramBytes
import com.filamentvision.backend.input.transport.TransportParseResult
import com.filamentvision.core.frame.CameraId
import com.filamentvision.core.input.CameraInput
import java.net.InetSocketAddress

data class ReceivedDatagramMetadata(
    val source: InetSocketAddress,
    val receivedAtMonotonicNanos: Long,
    val receivedAtEpochMillis: Long,
)

enum class RaspberryPiProcessingStage { ENVELOPE, TRANSPORT, REASSEMBLY, DECODER }

sealed interface ProcessorResult {
    data class Accepted(
        val input: CameraInput.Image,
        val source: InetSocketAddress,
        val sourceTimestampMicros: Long?,
        val completedAtMonotonicNanos: Long,
    ) : ProcessorResult
    data object Pending : ProcessorResult
    data class Rejected(val stage: RaspberryPiProcessingStage, val reason: String) : ProcessorResult
}

/** Confined to the receive callback thread; borrowed bytes never escape [process]. */
class RaspberryPiUdpProcessor(private val compiled: CompiledRaspberryPiInputProfile) {
    private var sequence = 0L

    val inFlightFrameCount: Int get() = compiled.reassembler.inFlightFrameCount
    val bufferedReassemblyBytes: Long get() = compiled.reassembler.bufferedBytes
    val reassemblyTombstoneCount: Int get() = compiled.reassembler.tombstoneCount

    fun expire(nowMonotonicNanos: Long): Int = compiled.reassembler.expire(nowMonotonicNanos)

    fun process(bytes: BorrowedDatagramBytes, metadata: ReceivedDatagramMetadata): ProcessorResult {
        val envelope = compiled.envelopeParser.parse(bytes)
        if (envelope is EnvelopeParseResult.Rejected) return rejected(RaspberryPiProcessingStage.ENVELOPE, envelope.error.name)
        return (envelope as EnvelopeParseResult.Accepted).structuredBytes.withBytes { backing, offset, length ->
            val parsed = compiled.transportParser.parse(backing, offset, length, compiled.profile.transportLimits)
            if (parsed is TransportParseResult.Rejected) return@withBytes rejected(RaspberryPiProcessingStage.TRANSPORT, parsed.error.code.name)
            val datagram = (parsed as TransportParseResult.Accepted).datagram
            val reassembled = datagram.payload.withBytes { payload, payloadOffset, payloadLength ->
                // The existing reassembler makes the sole owned chunk copy before returning.
                compiled.reassembler.accept(StructuredChunkInput(
                    cameraId = UdpCameraId.A, // Internal adapter for the existing generic v1 reassembler.
                    source = metadata.source,
                    frameId = datagram.header.frameId.toLong(),
                    chunkIndex = datagram.header.chunkIndex,
                    chunkCount = datagram.header.chunkCount,
                    framePayloadBytes = datagram.header.framePayloadBytes,
                    sourceTimestampMicros = datagram.header.sourceTimestampMicros,
                    payload = payload,
                    payloadOffset = payloadOffset,
                    payloadLength = payloadLength,
                    receivedAtMonotonicNanos = metadata.receivedAtMonotonicNanos,
                    receivedAtEpochMillis = metadata.receivedAtEpochMillis,
                ))
            }
            when (reassembled) {
                FrameReassemblyResult.Pending -> ProcessorResult.Pending
                is FrameReassemblyResult.Dropped -> rejected(RaspberryPiProcessingStage.REASSEMBLY, reassembled.reason.name)
                is FrameReassemblyResult.Completed -> decode(reassembled.frame)
            }
        }
    }

    private fun decode(frame: CompletedFrame): ProcessorResult {
        val decoded = compiled.decoder.decode(frame.payload)
        if (decoded is PayloadDecodeResult.Failure) return rejected(RaspberryPiProcessingStage.DECODER, decoded.error.name)
        val image = (decoded as PayloadDecodeResult.Success).value as DecodedInput.Image
        return ProcessorResult.Accepted(
            CameraInput.Image(DecodedFrameAdapter.toFrame(
                image.image, CameraId("camera"), frame.receivedAtEpochMillis,
                frame.receivedAtMonotonicNanos, ++sequence,
            )),
            frame.source, frame.sourceTimestampMicros, frame.completedAtMonotonicNanos,
        )
    }

    private fun rejected(stage: RaspberryPiProcessingStage, reason: String) = ProcessorResult.Rejected(stage, reason)
}
