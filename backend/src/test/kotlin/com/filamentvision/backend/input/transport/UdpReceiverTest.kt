package com.filamentvision.backend.input.transport

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
