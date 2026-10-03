package com.filamentvision.backend.input.runtime

import com.filamentvision.backend.adapter.DecodedFrameAdapter
import com.filamentvision.backend.input.decoder.DecodedInput
import com.filamentvision.backend.input.decoder.PayloadDecodeResult
import com.filamentvision.backend.input.framing.EnvelopeParseResult
import com.filamentvision.backend.input.inspector.PacketInspector
import com.filamentvision.backend.input.inspector.RawParseStatus
import com.filamentvision.backend.input.profile.UdpCameraId
import com.filamentvision.backend.input.reassembly.*
import com.filamentvision.backend.input.transport.BorrowedDatagramBytes
import com.filamentvision.backend.input.transport.TransportParseResult
import com.filamentvision.core.frame.CameraId
import com.filamentvision.core.frame.FrameLease
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
class RaspberryPiUdpProcessor(
    private val compiled: CompiledRaspberryPiInputProfile,
    private val decodedPermit: DecodedFramePermit = DecodedFramePermit(),
    private val inspector: PacketInspector? = null,
) {
    private var sequence = 0L
    var processingBackpressureDrops = 0L
        private set

    val inFlightFrameCount: Int get() = compiled.reassembler.inFlightFrameCount
    val bufferedReassemblyBytes: Long get() = compiled.reassembler.bufferedBytes
    val reassemblyTombstoneCount: Int get() = compiled.reassembler.tombstoneCount

    fun expire(nowMonotonicNanos: Long): Int = compiled.reassembler.expire(nowMonotonicNanos)

    fun process(bytes: BorrowedDatagramBytes, metadata: ReceivedDatagramMetadata): ProcessorResult {
        val envelope = compiled.envelopeParser.parse(bytes)
        val captureId = if (inspector?.captureEnabled() == true) bytes.withBytes { backing, length ->
            inspector.captureRaw(backing, length, metadata, compiled.profile,
                if (envelope is EnvelopeParseResult.Rejected) RawParseStatus.valueOf(envelope.error.name) else RawParseStatus.VALID)
        } else 0L
        if (envelope is EnvelopeParseResult.Rejected) return rejected(RaspberryPiProcessingStage.ENVELOPE, envelope.error.name)
        return (envelope as EnvelopeParseResult.Accepted).structuredBytes.withBytes { backing, offset, length ->
            val parsed = compiled.transportParser.parse(backing, offset, length, compiled.profile.transportLimits)
            if (parsed is TransportParseResult.Rejected) {
                if (captureId != 0L) inspector?.rejectTransport(captureId)
                return@withBytes rejected(RaspberryPiProcessingStage.TRANSPORT, parsed.error.code.name)
            }
            val datagram = (parsed as TransportParseResult.Accepted).datagram
            if (captureId != 0L) inspector?.attachTransport(captureId, datagram.header)
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
                is FrameReassemblyResult.Completed -> decode(reassembled.frame, captureId)
            }
        }
    }

    private fun decode(frame: CompletedFrame, captureId: Long): ProcessorResult {
        val lease = decodedPermit.tryAcquire() ?: run {
            processingBackpressureDrops++
            inspectFrame(captureId, frame, "PROCESSING_BACKPRESSURE")
            return rejected(RaspberryPiProcessingStage.REASSEMBLY, "PROCESSING_BACKPRESSURE")
        }
        var transferred = false
        try {
            val decoded = compiled.decoder.decode(frame.payload)
            if (decoded is PayloadDecodeResult.Failure) {
                inspectFrame(captureId, frame, decoded.error.name)
                return rejected(RaspberryPiProcessingStage.DECODER, decoded.error.name)
            }
            val image = (decoded as PayloadDecodeResult.Success).value as DecodedInput.Image
            inspectFrame(captureId, frame, "VALID", image.image.width, image.image.height)
            val result = ProcessorResult.Accepted(
                CameraInput.Image(DecodedFrameAdapter.toFrame(
                    image.image, CameraId("camera"), frame.receivedAtEpochMillis,
                    frame.receivedAtMonotonicNanos, sequence + 1,
                    lease = FrameLease(lease::close),
                )),
                frame.source, frame.sourceTimestampMicros, frame.completedAtMonotonicNanos,
            )
            sequence++
            transferred = true
            return result
        } finally {
            if (!transferred) lease.close()
        }
    }

    private fun inspectFrame(captureId: Long, frame: CompletedFrame, result: String, width: Int? = null, height: Int? = null) {
        if (captureId != 0L) inspector?.captureParsed(captureId, frame.payload, compiled.profile.payload,
            result, width, height, frame.completedAtMonotonicNanos)
    }

    private fun rejected(stage: RaspberryPiProcessingStage, reason: String) = ProcessorResult.Rejected(stage, reason)
}
