package com.filamentvision.backend.input.inspector

import com.filamentvision.backend.input.profile.UdpCameraId

enum class RawParseStatus { VALID, SOURCE_IP_MISMATCH, DATAGRAM_TOO_LARGE_OR_TRUNCATED, INVALID_TRANSPORT_HEADER }
data class TransportInspection(val frameId: Long, val chunkIndex: Int, val chunkCount: Int,
    val sourceTimestampMicros: Long, val payloadLength: Int, val framePayloadBytes: Long)
data class RawDatagramInspection(val sourceIp: String, val sourcePort: Int, val datagramSize: Int,
    val receivedAtMonotonicNanos: Long, val status: RawParseStatus, val preview: ByteArray,
    val transport: TransportInspection? = null)
data class ParsedFrameInspection(val payloadFormat: String, val decodeResult: String,
    val applicationHeaderBytes: Int, val applicationFooterBytes: Int,
    val receivedAtMonotonicNanos: Long, val preview: ByteArray)
data class PacketInspectorSnapshot(val sequence: Long, val cameraId: UdpCameraId,
    val raw: RawDatagramInspection, val parsedFrame: ParsedFrameInspection?)
data class PacketInspectorLease(val id: String, val cameraId: UdpCameraId, val previewBytes: Int, val expiresAtMillis: Long)
