package com.filamentvision.backend.input.runtime

import com.filamentvision.backend.adapter.DecodedFrameAdapter
import com.filamentvision.backend.input.decoder.*
import com.filamentvision.backend.input.framing.*
import com.filamentvision.backend.input.profile.*
import com.filamentvision.backend.input.reassembly.*
import com.filamentvision.backend.input.transport.*
import com.filamentvision.core.frame.CameraId
import com.filamentvision.core.input.*
import java.net.InetSocketAddress
import com.filamentvision.backend.input.diagnostics.UdpDiagnostics
import com.filamentvision.backend.input.inspector.*

data class OwnedUdpPacket(
    val bytes: ByteArray,
    val length: Int,
    val source: InetSocketAddress,
    val receivedAtMonotonicNanos: Long,
    val receivedAtEpochMillis: Long,
)

enum class UdpProcessingStage { TRANSPORT, DATAGRAM_FRAMING, REASSEMBLY, FRAME_FRAMING, DECODER }

sealed interface UdpCameraProcessResult {
    data class Accepted(val input: CameraInput) : UdpCameraProcessResult
    data object Pending : UdpCameraProcessResult
    data class Rejected(val stage: UdpProcessingStage, val reason: String) : UdpCameraProcessResult
}

class UdpCameraProcessor(private val compiled: CompiledUdpInputProfile, private val diagnostics: UdpDiagnostics? = null,
    private val inspector: PacketInspector? = null) {
    private var sequence = 0L

    fun process(packet: OwnedUdpPacket): UdpCameraProcessResult {
        val result = try {
        val completed = when (compiled.profile.transportMode) {
            TransportMode.SINGLE_DATAGRAM -> processSingle(packet)
            TransportMode.STRUCTURED_CHUNKED -> processStructured(packet)
        }
        when (completed) {
            is FrameReassemblyResult.Completed -> decodeFrame(completed.frame)
            is FrameReassemblyResult.Dropped -> rejected(UdpProcessingStage.REASSEMBLY, completed.reason.name)
            FrameReassemblyResult.Pending -> UdpCameraProcessResult.Pending
        }
    } catch (error: StageFailure) {
        rejected(error.stage, error.message.orEmpty())
    } catch (error: Throwable) {
        rejected(UdpProcessingStage.DECODER, error.message ?: error::class.simpleName.orEmpty())
        }
        when (result) {
            is UdpCameraProcessResult.Accepted -> diagnostics?.frameCompleted(packet.receivedAtMonotonicNanos)
            is UdpCameraProcessResult.Rejected -> diagnostics?.rejected(result.stage, result.reason)
            UdpCameraProcessResult.Pending -> Unit
        }
        return result
    }

    private fun processSingle(packet: OwnedUdpPacket): FrameReassemblyResult {
        val framed = compiled.framingParser.parse(
            FramingScope.DATAGRAM,
            ByteRange(packet.bytes, 0, packet.length),
        )
        if (framed is ApplicationFramingResult.Rejected) throw StageFailure(UdpProcessingStage.DATAGRAM_FRAMING, framed.error.name)
        val payload = (framed as ApplicationFramingResult.Accepted).payload.copyOwned()
        return compiled.reassembler.accept(
            SingleDatagramInput(
                compiled.profile.cameraId, packet.source, payload,
                packet.receivedAtMonotonicNanos, packet.receivedAtEpochMillis,
            ),
        )
    }

    private fun processStructured(packet: OwnedUdpPacket): FrameReassemblyResult {
        val parsed = compiled.transportParser.parse(
            packet.bytes, packet.length, compiled.profile.cameraId, compiled.profile.transportLimits,
        )
        if (parsed is TransportParseResult.Rejected) throw StageFailure(UdpProcessingStage.TRANSPORT, parsed.error.code.name)
        val datagram = (parsed as TransportParseResult.Accepted).datagram
        inspect { inspector?.attachTransport(compiled.profile.cameraId, datagram.header.let {
            TransportInspection(it.frameId.toLong(), it.chunkIndex, it.chunkCount, it.sourceTimestampMicros,
                it.payloadLength, it.framePayloadBytes)
        }) }
        return datagram.payload.withBytes { bytes, offset, length ->
            val framed = compiled.framingParser.parse(FramingScope.DATAGRAM, ByteRange(bytes, offset, length))
            if (framed is ApplicationFramingResult.Rejected) throw StageFailure(UdpProcessingStage.DATAGRAM_FRAMING, framed.error.name)
            val range = (framed as ApplicationFramingResult.Accepted).payload
            compiled.reassembler.accept(
                StructuredChunkInput(
                    cameraId = compiled.profile.cameraId,
                    source = packet.source,
                    frameId = datagram.header.frameId.toLong(),
                    chunkIndex = datagram.header.chunkIndex,
                    chunkCount = datagram.header.chunkCount,
                    framePayloadBytes = datagram.header.framePayloadBytes,
                    sourceTimestampMicros = datagram.header.sourceTimestampMicros,
                    payload = range.bytes,
                    payloadOffset = range.offset,
                    payloadLength = range.length,
                    receivedAtMonotonicNanos = packet.receivedAtMonotonicNanos,
                    receivedAtEpochMillis = packet.receivedAtEpochMillis,
                ),
            )
        }
    }

    private fun decodeFrame(frame: CompletedFrame): UdpCameraProcessResult {
        val framed = compiled.framingParser.parse(FramingScope.FRAME, frame.payload)
        if (framed is ApplicationFramingResult.Rejected) return rejected(UdpProcessingStage.FRAME_FRAMING, framed.error.name)
        val payload = (framed as ApplicationFramingResult.Accepted).payload.copyOwned()
        val decoded = compiled.decoder.decode(payload)
        if (decoded is PayloadDecodeResult.Failure) return rejected(UdpProcessingStage.DECODER, decoded.error.name)
        inspect { inspector?.captureParsed(frame.cameraId, payload, payloadFormat(), "VALID",
            framed.matchedHeader?.length ?: 0, framed.matchedFooter?.length ?: 0, frame.completedAtMonotonicNanos) }
        val value = (decoded as PayloadDecodeResult.Success).value
        val nextSequence = ++sequence
        val input = when (value) {
            is DecodedInput.Image -> CameraInput.Image(
                DecodedFrameAdapter.toFrame(
                    value.image, frame.cameraId.toCoreId(), frame.receivedAtEpochMillis,
                    frame.receivedAtMonotonicNanos, nextSequence,
                ),
            )
            is DecodedInput.DirectDiameter -> CameraInput.DirectDiameter(
                DirectDiameterEvidence(
                    cameraId = frame.cameraId.toCoreId(),
                    rawValue = value.evidence.rawValue,
                    encoding = DirectDiameterEncoding.valueOf(value.evidence.encoding.name),
                    unit = DirectDiameterUnit.valueOf(value.evidence.unit.name),
                    normalizedDiameterMm = value.evidence.normalizedMillimeters,
                    deviceConfidence = null,
                    receivedAtEpochMillis = frame.receivedAtEpochMillis,
                    receivedAtMonotonicNanos = frame.receivedAtMonotonicNanos,
                    sourceTimestampMicros = frame.sourceTimestampMicros,
                    sequence = nextSequence,
                ),
            )
        }
        return UdpCameraProcessResult.Accepted(input)
    }

    private fun rejected(stage: UdpProcessingStage, reason: String) = UdpCameraProcessResult.Rejected(stage, reason)
    private fun payloadFormat() = when (compiled.profile.payload) {
        is PayloadProfile.Gray8 -> "GRAY8"; is PayloadProfile.Binary -> "BINARY"; is PayloadProfile.Jpeg -> "JPEG"
        is PayloadProfile.Rgb888 -> "RGB888"; is PayloadProfile.Diameter -> "DIAMETER"
    }
    private inline fun inspect(action: () -> Unit) { try { action() } catch (_: Throwable) { } }

    private class StageFailure(val stage: UdpProcessingStage, message: String) : RuntimeException(message)

    private fun UdpCameraId.toCoreId() = CameraId(name)
}
