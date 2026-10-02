package com.filamentvision.backend.e2e

import com.filamentvision.backend.input.profile.UdpCameraId
import com.filamentvision.backend.input.transport.StructuredTransportCodec
import com.filamentvision.backend.input.transport.StructuredTransportHeader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

internal class UdpTestSender : AutoCloseable {
    private val socket = DatagramSocket()
    private val loopback = InetAddress.getByName("127.0.0.1")

    fun send(port: Int, bytes: ByteArray) {
        socket.send(DatagramPacket(bytes, bytes.size, loopback, port))
    }

    fun sendStructured(
        port: Int,
        cameraId: UdpCameraId,
        frameId: ULong,
        payload: ByteArray,
        chunkBytes: Int,
        order: List<Int>? = null,
    ) {
        val chunks = payload.asList().chunked(chunkBytes).map { it.toByteArray() }
        val indexes = order ?: chunks.indices.toList()
        indexes.forEach { index ->
            val chunk = chunks[index]
            send(
                port,
                StructuredTransportCodec.encode(
                    StructuredTransportHeader(
                        cameraId = cameraId,
                        frameId = frameId,
                        chunkIndex = index,
                        chunkCount = chunks.size,
                        sourceTimestampMicros = frameId.toLong() * 1_000,
                        payloadLength = chunk.size,
                        framePayloadBytes = payload.size.toLong(),
                    ),
                    chunk,
                ),
            )
        }
    }

    override fun close() = socket.close()
}
