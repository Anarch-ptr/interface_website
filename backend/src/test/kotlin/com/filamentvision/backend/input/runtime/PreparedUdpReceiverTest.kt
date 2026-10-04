package com.filamentvision.backend.input.runtime

import com.filamentvision.backend.input.profile.*
import com.filamentvision.backend.input.network.*
import com.filamentvision.backend.input.transport.*
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.StandardProtocolFamily
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PreparedUdpReceiverTest {
    @Test
    fun prepareBindsWithoutStartingReceiveOrPublishingObserverAndCommitStartsExactlyOneLoop() = runBlocking {
        val received = CompletableDeferred<Unit>()
        var counters = 0
        val receiver = UdpReceiver(
            profile(freePort()),
            observer = object : UdpReceiverObserver { override fun onReceived(datagram: ReceivedDatagram) { counters++ } },
        ) { received.complete(Unit) }
        val prepared = PreparedUdpReceiver.prepare(receiver)
        try {
            assertTrue(receiver.isSocketOpen)
            assertFalse(receiver.isRunning)
            assertEquals(0, receiver.activeReceiverCount)
            send(receiver.localAddress.port, byteArrayOf(1))
            assertEquals(0, counters)
            prepared.commit()
            prepared.commit()
            withTimeout(3_000) { received.await() }
            assertEquals(1, counters)
            assertEquals(1, receiver.activeReceiverCount)
        } finally { prepared.close() }
    }

    @Test
    fun closeBeforeCommitDiscardsPreparedSocketAndNeverPublishes() = runBlocking {
        var consumed = 0
        val receiver = UdpReceiver(profile(freePort())) { consumed++ }
        val prepared = PreparedUdpReceiver.prepare(receiver)
        prepared.close()
        assertFalse(receiver.isSocketOpen)
        assertEquals(0, receiver.activeReceiverCount)
        assertEquals(0, consumed)
        assertThrows(IllegalStateException::class.java) { runBlocking { prepared.commit() } }
        Unit
    }

    @Test
    fun bindFailureClosesCandidateAndDoesNotCreateAPreparedInstance() = runBlocking {
        val port = freePort()
        DatagramChannel.open(StandardProtocolFamily.INET).use { occupied ->
            occupied.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port))
            val receiver = UdpReceiver(profile(port), bindIpv4 = "127.0.0.1") { }
            assertThrows(BindException::class.java) { runBlocking { PreparedUdpReceiver.prepare(receiver) } }
            assertFalse(receiver.isSocketOpen)
        }
    }

    @Test
    fun sourceAllowsAtMostOnePreparedCandidateAndReleasesTheSlotOnClose() = runBlocking {
        val port = freePort()
        val inputProfile = RaspberryPiUdpInputProfile.defaults().copy(enabled = true, listenPort = port)
        val plan = EffectiveUdpNetworkPlan(
            "127.0.0.1", "loopback-test", null, "127.0.0.1", null, emptySet(),
            ResolutionStatus.RESOLVED, ResolutionStatus.NOT_REQUIRED, null, null, 1, 2, 1,
        )
        val source = RaspberryPiUdpInputSource(inputProfile)
        val first = source.prepareReceiver(inputProfile, plan)
        try {
            assertEquals(1, source.preparedReceiverCount)
            assertEquals(1, source.maximumPreparedReceiverCount)
            assertThrows(IllegalStateException::class.java) {
                runBlocking { source.prepareReceiver(inputProfile, plan) }
            }
        } finally { first.close() }
        assertEquals(0, source.preparedReceiverCount)
    }

    private fun profile(port: Int) = UdpInputProfile(
        UdpCameraId.A, true, null, port, TransportMode.SINGLE_DATAGRAM,
        PayloadProfile.Diameter(DiameterValueEncoding.FLOAT32, DiameterByteOrder.BIG_ENDIAN),
        ApplicationFramingProfile(), TransportLimits(maxFrameBytes = 32),
    )

    private fun freePort(): Int = DatagramChannel.open(StandardProtocolFamily.INET).use {
        it.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
        (it.localAddress as InetSocketAddress).port
    }

    private fun send(port: Int, bytes: ByteArray) {
        DatagramChannel.open(StandardProtocolFamily.INET).use {
            it.send(ByteBuffer.wrap(bytes), InetSocketAddress(InetAddress.getByName("127.0.0.1"), port))
        }
    }
}
