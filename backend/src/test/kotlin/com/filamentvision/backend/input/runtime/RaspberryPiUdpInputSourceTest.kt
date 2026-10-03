package com.filamentvision.backend.input.runtime

import com.filamentvision.backend.input.decoder.PayloadDecoder
import com.filamentvision.backend.input.network.*
import com.filamentvision.backend.input.profile.*
import com.filamentvision.backend.input.transport.*
import com.filamentvision.core.frame.*
import com.filamentvision.core.input.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.*
import org.junit.Test
import java.net.*
import java.nio.ByteBuffer
import java.nio.channels.*
import java.nio.channels.spi.SelectorProvider
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class RaspberryPiUdpInputSourceTest {
    @Test fun statusUsesExactGraceRecencyAndNewestSourceActivity() = runBlocking {
        val f = Fixture(profile().copy(raspberryPiIpv4 = "192.0.2.10"))
        assertEquals(UdpOperationalStatus.DISABLED, f.source.operationalStatus().status)
        f.source.start()
        try {
            assertEquals(UdpOperationalStatus.UDP_READY, f.source.operationalStatus().status)
            f.now.set(1_999_999_999); assertEquals(UdpOperationalStatus.UDP_READY, f.source.operationalStatus().status)
            f.now.set(2_000_000_000); assertEquals(UdpOperationalStatus.WAITING_FOR_RASPBERRY_PI, f.source.operationalStatus().status)
            f.packet(byteArrayOf(0), "192.0.2.11")
            assertEquals(UdpOperationalStatus.SOURCE_IP_MISMATCH, f.source.operationalStatus().status)
            assertEquals(0, f.source.udpDiagnostics().framesDropped)
            // A newer accepted source wins even when the monotonic clock has the same value.
            f.packet(byteArrayOf(0))
            assertEquals(UdpOperationalStatus.RECEIVING, f.source.operationalStatus().status)
            f.packet(byteArrayOf(0), "192.0.2.11")
            assertEquals(UdpOperationalStatus.SOURCE_IP_MISMATCH, f.source.operationalStatus().status)
            f.now.set(4_999_999_999); assertEquals(UdpOperationalStatus.SOURCE_IP_MISMATCH, f.source.operationalStatus().status)
            f.now.set(5_000_000_000); assertEquals(UdpOperationalStatus.NO_PACKETS_RECENTLY, f.source.operationalStatus().status)
            f.packet(byteArrayOf(0))
            f.now.set(7_999_999_999); assertEquals(UdpOperationalStatus.RECEIVING, f.source.operationalStatus().status)
            f.now.set(8_000_000_000); assertEquals(UdpOperationalStatus.NO_PACKETS_RECENTLY, f.source.operationalStatus().status)
            val d = f.source.udpDiagnostics()
            assertEquals("192.0.2.10", d.lastPacketSourceIpv4)
            assertEquals(9000, d.lastPacketSourcePort)
            assertEquals(1234L, d.lastPacketEpochMillis)
            assertEquals(4, d.datagramsReceived)
        } finally { f.source.stop() }
        assertEquals(UdpOperationalStatus.DISABLED, f.source.operationalStatus().status)
    }

    @Test fun recentMismatchWithoutAcceptedPacketReturnsToWaiting() = runBlocking {
        val f = Fixture(profile().copy(raspberryPiIpv4 = "192.0.2.10"))
        f.source.start()
        try {
            f.packet(byteArrayOf(0), "192.0.2.11")
            assertEquals(UdpOperationalStatus.SOURCE_IP_MISMATCH, f.source.operationalStatus().status)
            f.now.set(3_000_000_000)
            assertEquals(UdpOperationalStatus.WAITING_FOR_RASPBERRY_PI, f.source.operationalStatus().status)
        } finally { f.source.stop() }
    }

    @Test fun repeatedConcurrentStartsOwnOneReceiverAndStopJoinsBeforeRestart() = runBlocking {
        val f = Fixture()
        coroutineScope { repeat(30) { launch(Dispatchers.Default) { f.source.start() } } }
        assertEquals(1, f.opens)
        assertEquals(1, f.source.activeReceiverCount)
        assertEquals(1, f.source.activeProducerCount)
        assertEquals(1, f.source.totalProducerStarts)
        coroutineScope { repeat(30) { launch(Dispatchers.Default) { f.source.stop() } } }
        assertFalse(f.socket.isOpen)
        assertEquals(0, f.source.activeReceiverCount)
        assertEquals(0, f.source.activeProducerCount)
        f.source.start()
        try {
            assertEquals(2, f.opens)
            assertEquals(1, f.source.activeProducerCount)
            assertEquals(UdpOperationalStatus.UDP_READY, f.source.operationalStatus().status)
        } finally { f.source.stop() }
    }

    @Test fun disabledProfileReservesOnlySourceLifecycleAndNeverOpensSocket() = runBlocking {
        val f = Fixture(profile().copy(enabled = false))
        f.source.start(); f.source.start()
        assertEquals(1, f.source.activeProducerCount)
        assertEquals(0, f.source.activeReceiverCount)
        assertEquals(0, f.opens)
        assertEquals(UdpOperationalStatus.DISABLED, f.source.operationalStatus().status)
        f.source.stop()
        assertEquals(0, f.source.activeProducerCount)
    }

    @Test fun resolvesSpecificOwnershipImmediatelyBeforeOpeningAndRechecksOnRestart() = runBlocking {
        var entries = listOf(Ipv4NetworkInterfaceEntry("wifi", "Wi-Fi", "192.0.2.20", true, false, false, true, 1))
        val calls = mutableListOf<String>()
        val socket = FakeChannel()
        val source = RaspberryPiUdpInputSource(profile().copy(networkInterface = NetworkInterfaceSelection.Specific("wifi", "192.0.2.20")),
            ipv4Resolver = BackendIpv4Resolver(Ipv4NetworkInterfaceProvider { calls += "resolve"; entries }),
            channelFactory = UdpChannelFactory { calls += "open"; socket })
        source.start()
        assertEquals(listOf("resolve", "open"), calls)
        assertEquals("192.0.2.20", (socket.localAddress as InetSocketAddress).address.hostAddress)
        source.stop(); entries = emptyList()
        assertThrows(IllegalStateException::class.java) { runBlocking { source.start() } }
        assertEquals(listOf("resolve", "open", "resolve"), calls)
        assertEquals(UdpOperationalStatus.BIND_FAILED, source.operationalStatus().status)
        assertEquals("SELECTED_IPV4_INTERFACE_UNAVAILABLE", source.operationalStatus().detail)
        assertEquals(0, source.activeProducerCount)
        source.stop()
    }

    @Test fun bindFailureClosesSocketAndExposesOnlySafeCode() = runBlocking {
        val socket = FakeChannel(bindFailure = BindException("secret host detail"))
        val source = RaspberryPiUdpInputSource(profile(), channelFactory = UdpChannelFactory { socket })
        assertThrows(BindException::class.java) { runBlocking { source.start() } }
        assertFalse(socket.isOpen)
        assertEquals(UdpOperationalStatus.BIND_FAILED, source.operationalStatus().status)
        assertEquals("UDP_BIND_FAILED", source.operationalStatus().detail)
        assertEquals("UDP_BIND_FAILED", source.diagnostics.value.lastError)
        assertEquals(0, source.activeProducerCount)
        assertEquals(0, source.activeReceiverCount)
        source.stop()
    }

    @Test fun receiveFailureCannotContinueReportingBoundOrReceiving() = runBlocking {
        val f = Fixture(); f.source.start()
        try {
            f.packet(byteArrayOf(0))
            f.socket.failReceive()
            eventually { f.source.state.value == ImageInputState.ERROR && f.source.activeReceiverCount == 0 }
            assertEquals(UdpOperationalStatus.DISABLED, f.source.operationalStatus().status)
            assertEquals("UDP_RECEIVE_FAILED", f.source.diagnostics.value.lastError)
            // The lifecycle reservation remains Runtime-owned until Runtime stops the source.
            assertEquals(1, f.source.activeProducerCount)
        } finally { f.source.stop() }
    }

    @Test fun cancellingStartClosesTheOpeningSocketAndReleasesReservation() = runBlocking {
        val opening = CountDownLatch(1); val release = CountDownLatch(1)
        val socket = FakeChannel()
        val source = RaspberryPiUdpInputSource(profile(), channelFactory = UdpChannelFactory {
            opening.countDown(); check(release.await(3, TimeUnit.SECONDS)); socket
        })
        val starting = launch(Dispatchers.Default) { source.start() }
        try {
            assertTrue(opening.await(3, TimeUnit.SECONDS))
            starting.cancel(); release.countDown()
            withTimeout(3000) { starting.join() }
            assertFalse(socket.isOpen)
            assertEquals(0, source.activeProducerCount)
            assertEquals(0, source.activeReceiverCount)
            assertEquals(UdpOperationalStatus.DISABLED, source.operationalStatus().status)
        } finally { release.countDown(); starting.cancelAndJoin(); source.stop() }
    }

    @Test fun immediateReceiveFailureDuringStartIsNotOverwrittenByRunningState() = runBlocking {
        val socket = FakeChannel().also { it.failReceive() }
        val source = RaspberryPiUdpInputSource(profile(), channelFactory = UdpChannelFactory { socket },
            dispatcher = Dispatchers.Unconfined)
        try {
            source.start()
            assertEquals(ImageInputState.ERROR, source.state.value)
            assertEquals("UDP_RECEIVE_FAILED", source.diagnostics.value.lastError)
            assertEquals(UdpOperationalStatus.DISABLED, source.operationalStatus().status)
        } finally { source.stop() }
    }

    @Test fun envelopeCapacityAndSourceFilteringPrecedeParsing() = runBlocking {
        val f = Fixture(profile().copy(raspberryPiIpv4 = "192.0.2.10"))
        f.source.start()
        try {
            f.packet(wire(1))
            val input = withTimeout(3000) { f.source.inputs.first() } as CameraInput.Image
            assertEquals("camera", input.cameraId.value)
            assertEquals(0xff070707.toInt(), input.frame.pixels[0]); input.frame.close()
            assertEquals(1, f.source.udpDiagnostics().framesCompleted)
            f.packet(ByteArray(40)) // 38 envelope/transport + 1 chunk + 1 sentinel.
            assertEquals(1, f.source.udpDiagnostics().oversizedOrTruncatedDatagrams)
            f.packet(ByteArray(40), "192.0.2.11")
            assertEquals(1, f.source.udpDiagnostics().sourceFilteredDatagrams)
            assertEquals(0, f.source.udpDiagnostics().invalidHeaderCount)
            f.packet(ByteArray(39))
            assertEquals(1, f.source.udpDiagnostics().invalidHeaderCount)
        } finally { f.source.stop() }
    }

    @Test fun mailboxClosesAndCountsReplacementBeforeAdmissionAndCallerOwnsTakenImage() = runBlocking {
        val f = Fixture()
        f.source.start()
        var oldClosed = 0; var latestClosed = 0
        try {
            val old = image(1, FrameLease { oldClosed++ })
            val latest = image(2, FrameLease { latestClosed++ })
            f.source.offerInput(old)
            f.source.offerInput(latest)
            assertEquals(1, oldClosed)
            assertEquals(1, f.source.pendingInputCount)
            assertEquals(1, f.source.preAdmissionDroppedInputs)
            assertEquals(1, f.source.diagnostics.value.droppedFrames)
            val admitted = withTimeout(3000) { f.source.inputs.first() }
            assertSame(latest, admitted)
            assertEquals(0, latestClosed)
            assertEquals(0, f.source.pendingInputCount)
            f.source.stop()
            assertEquals(0, latestClosed)
            latest.frame.close(); assertEquals(1, latestClosed)
        } finally { f.source.stop() }
    }

    @Test fun stopClosesPendingImageAndRejectsLateOffers() = runBlocking {
        val f = Fixture(); var releases = 0
        f.source.start(); f.source.offerInput(image(1, FrameLease { releases++ }))
        f.source.stop(); f.source.offerInput(image(2, FrameLease { releases++ }))
        assertEquals(2, releases)
        assertEquals(0, f.source.pendingInputCount)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun cancelledWaitingCollectorCannotStrandThePendingImage() = runTest {
        val f = Fixture(); f.source.start()
        val pending = image(1, FrameLease())
        try {
            val cancelled = backgroundScope.launch { f.source.inputs.first() }
            runCurrent() // Collector is waiting for the notification.
            f.source.offerInput(pending)
            cancelled.cancel() // Cancel after notification delivery but before mailbox admission.
            runCurrent()
            assertEquals(1, f.source.pendingInputCount)
            assertSame(pending, withTimeout(1000) { f.source.inputs.first() })
            pending.frame.close()
        } finally { f.source.stop() }
    }

    @Test fun idleExpiryAndProcessingAreSerializedAndStopWaitsForProcessing() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val now = AtomicLong(0); val socket = FakeChannel()
        val source = RaspberryPiUdpInputSource(profile(), channelFactory = UdpChannelFactory { socket },
            monotonicClock = MonotonicClock(now::get),
            compiledFactory = { p, observer ->
                val c = CompiledRaspberryPiInputProfile.compile(p, observer)
                CompiledRaspberryPiInputProfile(c.profile, c.envelopeParser, c.transportParser, c.reassembler,
                    PayloadDecoder { bytes -> entered.countDown(); check(release.await(3, TimeUnit.SECONDS)); c.decoder.decode(bytes) })
            })
        source.start()
        try {
            socket.enqueue(wire(1, count = 2, total = 2))
            eventually { source.udpDiagnostics().framesStarted == 1L }
            socket.enqueue(wire(2))
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            now.set(2_000_000_000)
            delay(200)
            assertEquals(0L, source.udpDiagnostics().reassemblyTimeouts)
            val stopping = async(Dispatchers.Default) { source.stop() }
            delay(100); assertFalse(stopping.isCompleted)
            release.countDown(); withTimeout(3000) { stopping.await() }
            assertEquals(0, source.activeReceiverCount)
            assertEquals(0, source.pendingInputCount)
        } finally { release.countDown(); source.stop() }
    }

    @Test fun incompleteFramesExpireWithoutFurtherPacketsAndRestartDropsOldAssembly() = runBlocking {
        val f = Fixture(); f.source.start()
        try {
            f.packet(wire(1, count = 2, total = 2))
            f.now.set(1_000_000_001)
            eventually { f.source.udpDiagnostics().reassemblyTimeouts == 1L }
            assertEquals(1L, f.source.udpDiagnostics().missingChunks)
            f.packet(wire(2, count = 2, total = 2))
            f.source.stop(); f.source.start()
            f.packet(wire(2, index = 1, count = 2, total = 2))
            assertEquals(0, f.source.udpDiagnostics().framesCompleted)
        } finally { f.source.stop() }
    }

    private class Fixture(p: RaspberryPiUdpInputProfile = profile()) {
        val now = AtomicLong(0)
        var opens = 0
        lateinit var socket: FakeChannel
        val source = RaspberryPiUdpInputSource(p, monotonicClock = MonotonicClock(now::get),
            epochClock = EpochClock { 1234 }, channelFactory = UdpChannelFactory { opens++; FakeChannel().also { socket = it } })
        suspend fun packet(bytes: ByteArray, ip: String = "192.0.2.10") {
            val next = socket.completedReceives.get() + 1
            socket.enqueue(bytes, ip)
            eventually { socket.completedReceives.get() >= next }
        }
    }

    /** Socket-only fake: the real receiver loop, borrowed buffer, filter and processor all execute. */
    private class FakeChannel(private val bindFailure: Exception? = null) : DatagramChannel(SelectorProvider.provider()) {
        private val packets = LinkedBlockingQueue<Pair<ByteArray, InetSocketAddress>>()
        private var bound: SocketAddress? = null
        private var delivered = false
        val completedReceives = AtomicLong()
        fun enqueue(bytes: ByteArray, ip: String = "192.0.2.10") { packets.put(bytes to InetSocketAddress(ip, 9000)) }
        fun failReceive() { packets.put(byteArrayOf() to InetSocketAddress("127.0.0.1", 0)) }
        override fun receive(dst: ByteBuffer): SocketAddress {
            if (delivered) completedReceives.incrementAndGet()
            delivered = false
            val (bytes, address) = packets.take()
            if (!isOpen) throw AsynchronousCloseException()
            if (address.port == 0) throw java.io.IOException("private platform detail")
            dst.put(bytes, 0, minOf(bytes.size, dst.remaining())); delivered = true
            return address
        }
        override fun bind(local: SocketAddress?): DatagramChannel { bindFailure?.let { throw it }; bound = local; return this }
        override fun getLocalAddress() = bound
        override fun <T : Any?> setOption(name: SocketOption<T>, value: T): DatagramChannel = this
        override fun <T : Any?> getOption(name: SocketOption<T>): T = throw UnsupportedOperationException()
        override fun supportedOptions(): Set<SocketOption<*>> = emptySet()
        override fun implCloseSelectableChannel() { packets.offer(byteArrayOf() to InetSocketAddress("127.0.0.1", 1)) }
        override fun implConfigureBlocking(block: Boolean) = Unit
        override fun socket(): DatagramSocket = throw UnsupportedOperationException()
        override fun isConnected() = false
        override fun connect(remote: SocketAddress?): DatagramChannel = this
        override fun disconnect(): DatagramChannel = this
        override fun getRemoteAddress(): SocketAddress? = null
        override fun send(src: ByteBuffer?, target: SocketAddress?) = throw UnsupportedOperationException()
        override fun read(dst: ByteBuffer?) = throw UnsupportedOperationException()
        override fun read(dsts: Array<out ByteBuffer>?, offset: Int, length: Int): Long = throw UnsupportedOperationException()
        override fun write(src: ByteBuffer?) = throw UnsupportedOperationException()
        override fun write(srcs: Array<out ByteBuffer>?, offset: Int, length: Int): Long = throw UnsupportedOperationException()
        override fun join(group: InetAddress?, interf: NetworkInterface?): MembershipKey = throw UnsupportedOperationException()
        override fun join(group: InetAddress?, interf: NetworkInterface?, source: InetAddress?): MembershipKey = throw UnsupportedOperationException()
    }

    companion object {
        private fun profile() = RaspberryPiUdpInputProfile.defaults().copy(enabled = true, payload = ImagePayloadProfile.Gray8(1, 1),
            transportLimits = RaspberryPiUdpInputProfile.defaults().transportLimits.copy(maxChunkPayloadBytes = 1))
        private fun wire(id: Long, index: Int = 0, count: Int = 1, total: Int = 1) = byteArrayOf(0xaa.toByte(), 1) +
            StructuredTransportCodec.encode(StructuredTransportHeader(UdpCameraId.A, id.toULong(), index, count, 0, 1, total.toLong()), byteArrayOf(7)) + byteArrayOf(13, 10)
        private fun image(sequence: Long, lease: FrameLease) = CameraInput.Image(CameraFrame(CameraId("camera"), 0, 0,
            1, 1, PixelFormat.ARGB32, PixelBuffer.ints(intArrayOf(-1)), 0, false, sequence, lease = lease))
        private suspend fun eventually(condition: () -> Boolean) = withTimeout(3000) { while (!condition()) delay(5) }
    }
}
