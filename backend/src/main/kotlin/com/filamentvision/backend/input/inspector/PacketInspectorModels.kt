package com.filamentvision.backend.input.inspector

enum class RawParseStatus {
    VALID, SOURCE_IP_MISMATCH, DATAGRAM_TOO_LARGE_OR_TRUNCATED, DATAGRAM_TOO_SHORT,
    HEADER_MISMATCH, FOOTER_MISMATCH, INVALID_TRANSPORT_HEADER,
}
/** Metadata only: the chunk preview is a range in the owned raw prefix, never another array. */
data class TransportInspection(val protocolVersion: Int, val compatibilityStreamId: Int,
    val frameId: String, val chunkIndex: Int, val chunkCount: Int,
    val sourceTimestampMicros: Long, val payloadLength: Int, val framePayloadBytes: Long,
    val status: String = "VALID")
data class RawDatagramInspection(val sourceIpv4: String, val sourcePort: Int, val backendListenPort: Int,
    val datagramSize: Int, val receivedAtMonotonicNanos: Long, val status: RawParseStatus,
    val expectedHeaderHex: String, val expectedFooterHex: String, val actualHeader: Int?, val actualFooter: Int?,
    val headerMatched: Boolean?, val footerMatched: Boolean?,
    val preview: ByteArray, val transport: TransportInspection? = null)
data class ParsedFrameInspection(val payloadFormat: String, val decodeResult: String,
    val width: Int?, val height: Int?, val completedFrameBytes: Int,
    val receivedAtMonotonicNanos: Long, val preview: ByteArray)
data class PacketInspectorSnapshot(val sequence: Long, val raw: RawDatagramInspection,
    val parsedFrame: ParsedFrameInspection?)
data class PacketInspectorLease(val id: String, val previewBytes: Int, val expiresAtMillis: Long)
