package com.filamentvision.backend.input.transport

import com.filamentvision.backend.input.framing.DatagramEnvelopeParser
import com.filamentvision.backend.input.framing.EnvelopeParseResult
import com.filamentvision.backend.input.profile.ApplicationFramingProfile
import com.filamentvision.backend.input.profile.DiameterByteOrder
import com.filamentvision.backend.input.profile.DiameterValueEncoding
import com.filamentvision.backend.input.profile.PayloadProfile
import com.filamentvision.backend.input.profile.TransportLimits
import com.filamentvision.backend.input.profile.TransportMode
import com.filamentvision.backend.input.profile.UdpCameraId
import com.filamentvision.backend.input.profile.UdpInputProfile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.BindException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.StandardProtocolFamily
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel

class UdpReceiverTest {
    @Test
    fun bindsSelectedIpv4AndReportsBorrowedPacketMetadata() = runBlocking {
        val observed = CompletableDeferred<Triple<String, Int, Long>>()
        val receiver = UdpReceiver(profile(freePort()), bindIpv4 = "127.0.0.1",
            epochClock = EpochClock { 1234 }, observer = object : UdpReceiverObserver {
                override fun onReceived(datagram: ReceivedDatagram) {
                    observed.complete(Triple(datagram.source.address.hostAddress, datagram.size, datagram.receivedAtEpochMillis))
                }
            }) { }
        try {
            receiver.start()
            assertEquals("127.0.0.1", receiver.localAddress.address.hostAddress)
            send(receiver.localAddress.port, byteArrayOf(7))
            assertEquals(Triple("127.0.0.1", 1, 1234L), withTimeout(3000) { observed.await() })
        } finally { receiver.stop() }
    }
    @Test
    fun receivesMaximumLegalStructuredEnvelopeWithoutTruncation() = runBlocking {
        val observedSize = CompletableDeferred<Int>()
        val outcome = CompletableDeferred<Any>()
        val wire = envelopedChunk(1_400)
        val parser = DatagramEnvelopeParser(byteArrayOf(0xAA.toByte(), 1), byteArrayOf(13, 10))
        val receiver = UdpReceiver(
            profile = structuredProfile(freePort(), 1_400),
            envelopeBytes = 4,
            observer = object : UdpReceiverObserver {
                override fun onReceived(size: Int, receivedAtMonotonicNanos: Long) { observedSize.complete(size) }
                override fun onDropped(reason: UdpReceiverDropReason) { outcome.complete(reason) }
            },
        ) {
            val parsed = parser.parse(it.bytes)
            outcome.complete(if (parsed is EnvelopeParseResult.Accepted) it.bytes.copyOwned() else parsed)
        }
        try {
            receiver.start()
            send(receiver.localAddress.port, wire)
            val result = withTimeout(3_000) { outcome.await() }
            assertEquals(1_438, observedSize.await())
            assertTrue(result is ByteArray)
            assertArrayEquals(wire, result as ByteArray)
        } finally {
            receiver.stop()
        }
    }

    @Test
    fun exactStructuredEnvelopeSentinelIsDroppedBeforeParser() = runBlocking {
        assertStructuredEnvelopeDropped(ByteArray(1_439), 1_439)
    }

    @Test
    fun oversizedStructuredEnvelopeIsDroppedBeforeParserAfterTruncation() = runBlocking {
        assertStructuredEnvelopeDropped(ByteArray(2_000), 1_439)
    }

    private suspend fun assertStructuredEnvelopeDropped(wire: ByteArray, expectedReceivedSize: Int) {
        val dropped = CompletableDeferred<Pair<UdpReceiverDropReason, Int>>()
        var parserCalls = 0
        val parser = DatagramEnvelopeParser(byteArrayOf(0xAA.toByte(), 1), byteArrayOf(13, 10))
        val receiver = UdpReceiver(
            profile = structuredProfile(freePort(), 1_400),
            envelopeBytes = 4,
            observer = object : UdpReceiverObserver {
                override fun onDropped(reason: UdpReceiverDropReason, datagram: ReceivedDatagram) {
                    dropped.complete(reason to datagram.size)
                }
            },
        ) {
            parserCalls++
            parser.parse(it.bytes)
        }
        try {
            receiver.start()
            send(receiver.localAddress.port, wire)
            val result = withTimeout(3_000) { dropped.await() }
            assertEquals(UdpReceiverDropReason.DATAGRAM_TOO_LARGE_OR_TRUNCATED, result.first)
            assertEquals(expectedReceivedSize, result.second)
        } finally {
            receiver.stop()
        }
        assertEquals(0, parserCalls)
    }

    private fun structuredProfile(port: Int, maxChunkPayloadBytes: Int): UdpInputProfile =
        profile(port).copy(
            transportMode = TransportMode.STRUCTURED_CHUNKED,
            payload = PayloadProfile.Gray8(10, 10),
            transportLimits = TransportLimits(maxChunkPayloadBytes = maxChunkPayloadBytes),
        )

    private fun envelopedChunk(payloadBytes: Int): ByteArray =
        byteArrayOf(0xAA.toByte(), 1) + StructuredTransportCodec.encode(
            StructuredTransportHeader(
                cameraId = UdpCameraId.A,
                frameId = 1uL,
                chunkIndex = 0,
                chunkCount = 1,
                sourceTimestampMicros = 0,
                payloadLength = payloadBytes,
                framePayloadBytes = payloadBytes.toLong(),
            ),
            ByteArray(payloadBytes) { 7 },
        ) + byteArrayOf(13, 10)

    @Test
    fun bindsIpv4AnyAddressAndClosesCleanly() = runBlocking {
        val receiver = UdpReceiver(profile(port = freePort())) { }
        receiver.start()

        val local = receiver.localAddress
        assertTrue(local.address is Inet4Address)
        assertTrue(local.address.isAnyLocalAddress)
        assertTrue(receiver.isRunning)
        assertEquals(1, receiver.activeReceiverCount)
        assertTrue(receiver.actualSocketReceiveBufferBytes >= receiver.receiveCapacity.receiveCapacity)

        receiver.stop()
        assertFalse(receiver.isRunning)
        assertFalse(receiver.isSocketOpen)
        assertEquals(0, receiver.activeReceiverCount)
    }

    @Test
    fun acceptsMatchingSourceAndFiltersMismatchBeforeConsumer() = runBlocking {
        val accepted = CompletableDeferred<Unit>()
        val matching = UdpReceiver(profile(port = freePort(), sourceIp = "127.0.0.1")) { accepted.complete(Unit) }
        matching.start()
        send(matching.localAddress.port, byteArrayOf(1))
        withTimeout(3_000) { accepted.await() }
        matching.stop()

        val filtered = CompletableDeferred<Unit>()
        var consumed = 0
        val mismatch = UdpReceiver(
            profile = profile(port = freePort(), sourceIp = "127.0.0.2"),
            observer = object : UdpReceiverObserver {
                override fun onSourceFiltered(datagram: ReceivedDatagram) { filtered.complete(Unit) }
            },
        ) { consumed++ }
        mismatch.start()
        send(mismatch.localAddress.port, byteArrayOf(2))
        withTimeout(3_000) { filtered.await() }
        mismatch.stop()
        assertEquals(0, consumed)
    }

    @Test
    fun sentinelDatagramIsRejectedWithExplicitReason() = runBlocking {
        val dropped = CompletableDeferred<UdpReceiverDropReason>()
        var consumed = 0
        val receiver = UdpReceiver(
            profile = profile(port = freePort(), maxFrameBytes = 3),
            observer = object : UdpReceiverObserver {
                override fun onDropped(reason: UdpReceiverDropReason) { dropped.complete(reason) }
            },
        ) { consumed++ }
        receiver.start()
        send(receiver.localAddress.port, byteArrayOf(1, 2, 3, 4))

        assertEquals(UdpReceiverDropReason.DATAGRAM_TOO_LARGE_OR_TRUNCATED, withTimeout(3_000) { dropped.await() })
        receiver.stop()
        assertEquals(0, consumed)
    }

    @Test
    fun receivesMaximumLegalIpv4DatagramWithoutTreatingItAsSentinel() = runBlocking {
        val size = CompletableDeferred<Int>()
        val receiver = UdpReceiver(profile(port = freePort(), maxFrameBytes = 8 * 1024 * 1024)) {
            size.complete(it.size)
        }
        receiver.start()
        send(receiver.localAddress.port, ByteArray(UdpReceiveCapacityCalculator.IPV4_UDP_PAYLOAD_MAX) { 7 })
        assertEquals(UdpReceiveCapacityCalculator.IPV4_UDP_PAYLOAD_MAX, withTimeout(5_000) { size.await() })
        receiver.stop()
    }

    @Test
    fun supportsRestartWithoutDuplicateReceiverAndReportsBindFailure() = runBlocking {
        val port = freePort()
        val receiver = UdpReceiver(profile(port = port)) { }
        receiver.start()
        receiver.start()
        assertEquals(1, receiver.activeReceiverCount)

        val competing = UdpReceiver(profile(port = port)) { }
        assertThrows(BindException::class.java) { runBlocking { competing.start() } }
        assertEquals(UdpReceiverState.ERROR, competing.state)
        assertFalse(competing.isSocketOpen)

        receiver.stop()
        receiver.start()
        assertEquals(1, receiver.activeReceiverCount)
        receiver.stop()
    }

    private fun profile(port: Int, sourceIp: String? = null, maxFrameBytes: Int = 128): UdpInputProfile = UdpInputProfile(
        cameraId = UdpCameraId.A,
        enabled = true,
        sourceIp = sourceIp,
        listenPort = port,
        transportMode = TransportMode.SINGLE_DATAGRAM,
        payload = PayloadProfile.Diameter(DiameterValueEncoding.FLOAT32, DiameterByteOrder.BIG_ENDIAN),
        applicationFraming = ApplicationFramingProfile(),
        transportLimits = TransportLimits(maxFrameBytes = maxFrameBytes),
    )

    private fun freePort(): Int = DatagramChannel.open(StandardProtocolFamily.INET).use { channel ->
        channel.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
        (channel.localAddress as InetSocketAddress).port
    }

    private fun send(port: Int, bytes: ByteArray) {
        DatagramChannel.open(StandardProtocolFamily.INET).use { channel ->
            channel.send(ByteBuffer.wrap(bytes), InetSocketAddress(InetAddress.getByName("127.0.0.1"), port))
        }
    }
}
