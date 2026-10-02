package com.filamentvision.backend.api.dto

import com.filamentvision.backend.input.inspector.*

data class CreatePacketInspectorLeaseRequest(val previewBytes: Int = 128)
data class PacketInspectorLeaseDto(val leaseId: String, val cameraId: String, val previewBytes: Int, val expiresAtMillis: Long)
data class TransportInspectionDto(val frameId: Long, val chunkIndex: Int, val chunkCount: Int,
    val sourceTimestampMicros: Long, val payloadLength: Int, val framePayloadBytes: Long)
data class RawDatagramInspectionDto(val sourceIp: String, val sourcePort: Int, val datagramSize: Int,
    val receivedAtMonotonicNanos: Long, val status: String, val hexPreview: String, val transport: TransportInspectionDto?)
data class ParsedFrameInspectionDto(val payloadFormat: String, val decodeResult: String,
    val applicationHeaderBytes: Int, val applicationFooterBytes: Int, val receivedAtMonotonicNanos: Long,
    val hexPreview: String)
data class PacketInspectorSnapshotDto(val sequence: Long, val cameraId: String,
    val rawDatagram: RawDatagramInspectionDto, val parsedFrame: ParsedFrameInspectionDto?)

fun PacketInspectorLease.toDto() = PacketInspectorLeaseDto(id, cameraId.name, previewBytes, expiresAtMillis)
fun PacketInspectorSnapshot.toDto() = PacketInspectorSnapshotDto(sequence, cameraId.name,
    RawDatagramInspectionDto(raw.sourceIp, raw.sourcePort, raw.datagramSize, raw.receivedAtMonotonicNanos,
        raw.status.name, raw.preview.hex(), raw.transport?.let { TransportInspectionDto(it.frameId, it.chunkIndex,
            it.chunkCount, it.sourceTimestampMicros, it.payloadLength, it.framePayloadBytes) }),
    parsedFrame?.let { ParsedFrameInspectionDto(it.payloadFormat, it.decodeResult, it.applicationHeaderBytes,
        it.applicationFooterBytes, it.receivedAtMonotonicNanos, it.preview.hex()) })
private fun ByteArray.hex() = joinToString(" ") { "%02X".format(it.toInt() and 0xff) }
