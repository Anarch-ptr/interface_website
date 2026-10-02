package com.filamentvision.backend.input.transport

import com.filamentvision.backend.input.profile.TransportLimits
import com.filamentvision.backend.input.profile.UdpCameraId
import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class TransportErrorCode {
    INVALID_TRANSPORT_HEADER,
    UNSUPPORTED_PROTOCOL_VERSION,
    CAMERA_ID_MISMATCH,
    INVALID_CHUNK_INDEX,
    INVALID_CHUNK_COUNT,
    INVALID_CHUNK_LENGTH,
    FRAME_TOO_LARGE,
}

data class TransportParseError(
    val code: TransportErrorCode,
    val message: String,
)

class BorrowedPayloadView internal constructor(
    private val bytes: ByteArray,
    private val offset: Int,
    val length: Int,
) {
    operator fun get(index: Int): Byte {
        require(index in 0 until length)
        return bytes[offset + index]
    }

    fun copyOwned(): ByteArray = bytes.copyOfRange(offset, offset + length)

    fun <T> withBytes(block: (bytes: ByteArray, offset: Int, length: Int) -> T): T =
        block(bytes, offset, length)
}

data class ParsedTransportDatagram(
    val header: StructuredTransportHeader,
    val payload: BorrowedPayloadView,
)

sealed interface TransportParseResult {
    data class Accepted(val datagram: ParsedTransportDatagram) : TransportParseResult
    data class Rejected(val error: TransportParseError) : TransportParseResult
}

fun interface BorrowedPayloadViewFactory {
    fun create(bytes: ByteArray, offset: Int, length: Int): BorrowedPayloadView
}

class TransportDatagramParser(
    private val payloadViewFactory: BorrowedPayloadViewFactory =
        BorrowedPayloadViewFactory(::BorrowedPayloadView),
) {
    fun parse(
        bytes: ByteArray,
        length: Int,
        expectedCamera: UdpCameraId,
        limits: TransportLimits,
    ): TransportParseResult {
        if (length !in StructuredTransportCodec.HEADER_BYTES..bytes.size) {
            return rejected(TransportErrorCode.INVALID_TRANSPORT_HEADER, "Datagram is shorter than the transport header")
        }
        if (!StructuredTransportCodec.hasMagic(bytes, 0)) {
            return rejected(TransportErrorCode.INVALID_TRANSPORT_HEADER, "Transport magic does not match FVU1")
        }
        val buffer = ByteBuffer.wrap(bytes, 0, length).order(ByteOrder.BIG_ENDIAN)
        buffer.position(4)
        val version = buffer.get().toInt() and 0xff
        if (version != 1) {
            return rejected(TransportErrorCode.UNSUPPORTED_PROTOCOL_VERSION, "Unsupported transport protocol version")
        }
        val headerLength = buffer.get().toInt() and 0xff
        if (headerLength != StructuredTransportCodec.HEADER_BYTES) {
            return rejected(TransportErrorCode.INVALID_TRANSPORT_HEADER, "Transport header length must be 34")
        }
        val camera = when (buffer.get().toInt() and 0xff) {
            1 -> UdpCameraId.A
            2 -> UdpCameraId.B
            else -> return rejected(TransportErrorCode.INVALID_TRANSPORT_HEADER, "Unknown transport camera identifier")
        }
        if (camera != expectedCamera) {
            return rejected(TransportErrorCode.CAMERA_ID_MISMATCH, "Transport camera does not match receiver")
        }
        val flags = buffer.get().toInt() and 0xff
        if (flags != 0) {
            return rejected(TransportErrorCode.INVALID_TRANSPORT_HEADER, "Version 1 transport flags must be zero")
        }
        val frameId = buffer.long.toULong()
        val chunkIndex = buffer.short.toInt() and 0xffff
        val chunkCount = buffer.short.toInt() and 0xffff
        val sourceTimestampMicros = buffer.long
        val payloadLength = buffer.short.toInt() and 0xffff
        val framePayloadBytes = Integer.toUnsignedLong(buffer.int)

        if (chunkCount !in 1..limits.maxChunkCount) {
            return rejected(TransportErrorCode.INVALID_CHUNK_COUNT, "Chunk count exceeds configured limits")
        }
        if (chunkIndex >= chunkCount) {
            return rejected(TransportErrorCode.INVALID_CHUNK_INDEX, "Chunk index must be less than chunk count")
        }
        if (sourceTimestampMicros < 0) {
            return rejected(TransportErrorCode.INVALID_TRANSPORT_HEADER, "Source timestamp cannot be negative")
        }
        if (payloadLength > limits.maxChunkPayloadBytes) {
            return rejected(TransportErrorCode.INVALID_CHUNK_LENGTH, "Chunk payload exceeds configured limit")
        }
        if (length != headerLength + payloadLength) {
            return rejected(TransportErrorCode.INVALID_CHUNK_LENGTH, "Datagram length does not match payloadLength")
        }
        if (framePayloadBytes <= 0L || framePayloadBytes > limits.maxFrameBytes.toLong()) {
            return rejected(TransportErrorCode.FRAME_TOO_LARGE, "Frame payload declaration exceeds configured limit")
        }

        val header = StructuredTransportHeader(
            cameraId = camera,
            frameId = frameId,
            chunkIndex = chunkIndex,
            chunkCount = chunkCount,
            sourceTimestampMicros = sourceTimestampMicros,
            payloadLength = payloadLength,
            framePayloadBytes = framePayloadBytes,
            protocolVersion = version,
            headerLength = headerLength,
            flags = flags,
        )
        return TransportParseResult.Accepted(
            ParsedTransportDatagram(
                header,
                payloadViewFactory.create(bytes, headerLength, payloadLength),
            ),
        )
    }

    private fun rejected(code: TransportErrorCode, message: String) =
        TransportParseResult.Rejected(TransportParseError(code, message))
}
