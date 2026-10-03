package com.filamentvision.backend.api.dto

import com.filamentvision.backend.input.inspector.*
import com.filamentvision.backend.input.transport.StructuredTransportCodec

data class CreatePacketInspectorLeaseRequest(val previewBytes: Int = 128)
data class PacketInspectorLeaseDto(val leaseId: String, val previewBytes: Int, val expiresAtMillis: Long)
data class TransportInspectionDto(val protocolVersion: Int, val compatibilityStreamId: Int,
    val frameId: String, val chunkIndex: Int, val chunkCount: Int, val sourceTimestampMicros: Long,
    val payloadLength: Int, val framePayloadBytes: Long, val status: String, val hexPreview: String)
data class RawDatagramInspectionDto(val sourceIpv4: String, val sourcePort: Int, val backendListenPort: Int,
    val datagramSize: Int, val receivedAtMonotonicNanos: Long, val status: String,
    val headerHex: String?, val footerHex: String?, val expectedHeaderHex: String, val expectedFooterHex: String,
    val headerMatched: Boolean?, val footerMatched: Boolean?,
    val hexPreview: String, val transport: TransportInspectionDto?)
data class ParsedFrameInspectionDto(val payloadFormat: String, val decodeResult: String,
    val width: Int?, val height: Int?, val completedFrameBytes: Int, val receivedAtMonotonicNanos: Long,
    val hexPreview: String)
data class PacketInspectorSnapshotDto(val sequence: Long,
    val rawDatagram: RawDatagramInspectionDto, val parsedFrame: ParsedFrameInspectionDto?)

fun PacketInspectorLease.toDto() = PacketInspectorLeaseDto(id, previewBytes, expiresAtMillis)
fun PacketInspectorSnapshot.toDto() = PacketInspectorSnapshotDto(sequence,
    RawDatagramInspectionDto(raw.sourceIpv4, raw.sourcePort, raw.backendListenPort, raw.datagramSize,
        raw.receivedAtMonotonicNanos, raw.status.name, raw.actualHeader.boundaryHex(), raw.actualFooter.boundaryHex(),
        raw.expectedHeaderHex, raw.expectedFooterHex, raw.headerMatched, raw.footerMatched,
        raw.preview.hex(), raw.transport?.let {
            // Outer header is two bytes. This range excludes both structured header and outer footer.
            val start = 2 + StructuredTransportCodec.HEADER_BYTES
            TransportInspectionDto(it.protocolVersion, it.compatibilityStreamId, it.frameId, it.chunkIndex,
                it.chunkCount, it.sourceTimestampMicros, it.payloadLength, it.framePayloadBytes, it.status,
                raw.preview.hex(start, minOf(raw.preview.size, start + it.payloadLength)))
        }),
    parsedFrame?.let { ParsedFrameInspectionDto(it.payloadFormat, it.decodeResult, it.width, it.height,
        it.completedFrameBytes, it.receivedAtMonotonicNanos, it.preview.hex()) })

/** Hex allocation is exclusively at the REST read boundary. */
private fun ByteArray.hex(start: Int = 0, end: Int = size): String = buildString {
    val digits = "0123456789ABCDEF"
    for (index in start until end) {
        if (index > start) append(' ')
        val byte = this@hex[index].toInt() and 0xff
        append(digits[byte ushr 4]); append(digits[byte and 15])
    }
}

private fun Int?.boundaryHex(): String? = this?.let { value -> buildString(5) {
    val digits = "0123456789ABCDEF"
    append(digits[(value ushr 12) and 15]); append(digits[(value ushr 8) and 15])
    append(' ')
    append(digits[(value ushr 4) and 15]); append(digits[value and 15])
} }
