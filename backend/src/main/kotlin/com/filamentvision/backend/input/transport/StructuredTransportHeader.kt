package com.filamentvision.backend.input.transport

import com.filamentvision.backend.input.profile.UdpCameraId
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class StructuredTransportHeader(
    /** Reserved v1 compatibility byte at offset 6; the production stream uses value 1. */
    val compatibilityStreamId: UByte,
    val frameId: ULong,
    val chunkIndex: Int,
    val chunkCount: Int,
    val sourceTimestampMicros: Long,
    val payloadLength: Int,
    val framePayloadBytes: Long,
    val protocolVersion: Int = 1,
    val headerLength: Int = StructuredTransportCodec.HEADER_BYTES,
    val flags: Int = 0,
) {
    /** Adapter for legacy consumers while the single-stream production composition migrates. */
    val cameraId: UdpCameraId
        get() = when (compatibilityStreamId.toInt()) {
            1 -> UdpCameraId.A
            2 -> UdpCameraId.B
            else -> error("Compatibility stream byte has no legacy camera identity")
        }

    constructor(
        cameraId: UdpCameraId,
        frameId: ULong,
        chunkIndex: Int,
        chunkCount: Int,
        sourceTimestampMicros: Long,
        payloadLength: Int,
        framePayloadBytes: Long,
        protocolVersion: Int = 1,
        headerLength: Int = StructuredTransportCodec.HEADER_BYTES,
        flags: Int = 0,
    ) : this(
        compatibilityStreamId = (if (cameraId == UdpCameraId.A) 1 else 2).toUByte(),
        frameId = frameId,
        chunkIndex = chunkIndex,
        chunkCount = chunkCount,
        sourceTimestampMicros = sourceTimestampMicros,
        payloadLength = payloadLength,
        framePayloadBytes = framePayloadBytes,
        protocolVersion = protocolVersion,
        headerLength = headerLength,
        flags = flags,
    )
}

object StructuredTransportCodec {
    const val HEADER_BYTES = 34
    private const val MAX_UNSIGNED_INT = 0xffff_ffffL
    private val magic = byteArrayOf(0x46, 0x56, 0x55, 0x31)

    fun encode(header: StructuredTransportHeader, payload: ByteArray): ByteArray {
        require(header.protocolVersion in 0..0xff)
        require(header.headerLength in 0..0xff)
        require(header.flags in 0..0xff)
        require(header.chunkIndex in 0..0xffff)
        require(header.chunkCount in 0..0xffff)
        require(header.payloadLength in 0..0xffff)
        require(header.framePayloadBytes in 0..MAX_UNSIGNED_INT)
        val bytes = ByteArray(HEADER_BYTES + payload.size)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        buffer.put(magic)
        buffer.put(header.protocolVersion.toByte())
        buffer.put(header.headerLength.toByte())
        buffer.put(header.compatibilityStreamId.toByte())
        buffer.put(header.flags.toByte())
        buffer.putLong(header.frameId.toLong())
        buffer.putShort(header.chunkIndex.toShort())
        buffer.putShort(header.chunkCount.toShort())
        buffer.putLong(header.sourceTimestampMicros)
        buffer.putShort(header.payloadLength.toShort())
        buffer.putInt(header.framePayloadBytes.toInt())
        buffer.put(payload)
        return bytes
    }

    internal fun hasMagic(bytes: ByteArray, offset: Int): Boolean =
        magic.indices.all { bytes[offset + it] == magic[it] }
}
