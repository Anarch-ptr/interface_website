package com.filamentvision.backend.e2e

import com.filamentvision.backend.input.transport.StructuredTransportCodec
import com.filamentvision.backend.input.transport.StructuredTransportHeader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

internal class UdpTestSender(
    sourceIpv4: String = "127.0.0.1",
    destinationIpv4: String = "127.0.0.1",
) : AutoCloseable {
    private val destination = InetAddress.getByName(destinationIpv4)
    private val socket = DatagramSocket(InetSocketAddress(InetAddress.getByName(sourceIpv4), 0))

    fun send(port: Int, bytes: ByteArray) {
        socket.send(DatagramPacket(bytes, bytes.size, destination, port))
    }

    fun sendStructured(
        port: Int,
        frameId: ULong,
        payload: ByteArray,
        chunkBytes: Int,
        order: List<Int>? = null,
    ) {
        require(chunkBytes in 1..60000 && payload.isNotEmpty())
        val chunkCount = (payload.size.toLong() + chunkBytes - 1).div(chunkBytes).toInt()
        val indexes = order ?: (0 until chunkCount).toList()
        indexes.forEach { index ->
            require(index in 0 until chunkCount)
            val start = index * chunkBytes
            val chunk = payload.copyOfRange(start, minOf(payload.size, start + chunkBytes))
            send(
                port,
                byteArrayOf(0xaa.toByte(), 1) + StructuredTransportCodec.encode(
                    StructuredTransportHeader(
                        compatibilityStreamId = 1u,
                        frameId = frameId,
                        chunkIndex = index,
                        chunkCount = chunkCount,
                        sourceTimestampMicros = frameId.toLong() * 1_000,
                        payloadLength = chunk.size,
                        framePayloadBytes = payload.size.toLong(),
                    ),
                    chunk,
                ) + byteArrayOf(13, 10),
            )
        }
    }

    override fun close() = socket.close()
}
