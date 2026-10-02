package com.filamentvision.backend.input.transport

import com.filamentvision.backend.input.profile.UdpCameraId
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class StructuredTransportHeader(
    val cameraId: UdpCameraId,
    val frameId: ULong,
    val chunkIndex: Int,
    val chunkCount: Int,
    val sourceTimestampMicros: Long,
    val payloadLength: Int,
    val framePayloadBytes: Long,
    val protocolVersion: Int = 1,
    val headerLength: Int = StructuredTransportCodec.HEADER_BYTES,
    val flags: Int = 0,
)

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
        buffer.put(if (header.cameraId == UdpCameraId.A) 1 else 2)
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
