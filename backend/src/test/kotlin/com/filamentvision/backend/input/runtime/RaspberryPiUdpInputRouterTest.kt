package com.filamentvision.backend.input.runtime

import com.filamentvision.backend.input.network.*
import com.filamentvision.backend.input.profile.*
import com.filamentvision.backend.input.transport.*
import com.filamentvision.core.input.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.net.*
import java.nio.ByteBuffer
import java.nio.channels.*
import java.nio.channels.spi.SelectorProvider
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicLong

class RaspberryPiUdpInputRouterTest {
    @Test fun parserEnvelopeAndLimitsSwapOnSameSocketAndDiscardOldReassembly() = runBlocking {
        val f = Task16SourceHarness(); val router = RaspberryPiUdpInputRouter(1, f.source)
        f.source.start()
        try {
            f.packet(task16Wire(1, index = 0, count = 2, payload = byteArrayOf(7), total = 2))
            val next = task16Profile().copy(payload = ImagePayloadProfile.Binary(2, 1, BinaryPacking.BYTE_PER_PIXEL),
                envelope = DatagramEnvelopeProfile("BB 02", "0E 0B"),
                transportLimits = task16Profile().transportLimits.copy(maxChunkPayloadBytes = 4, frameTimeoutMs = 2000))
            assertTrue(router.apply(2, next) { true })
            f.packet(task16Wire(1, index = 1, count = 2, payload = byteArrayOf(1), total = 2, alternateEnvelope = true))
            assertEquals(0L, f.source.udpDiagnostics().framesCompleted)
            f.packet(task16Wire(2, payload = byteArrayOf(0, 1), alternateEnvelope = true))
            val input = f.take()
            assertEquals(0xff000000.toInt(), input.frame.pixels[0])
            assertEquals(0xffffffff.toInt(), input.frame.pixels[1]); input.frame.close()
            f.packet(task16Wire(3, payload = byteArrayOf(0, 1)))
            assertEquals(1L, f.source.udpDiagnostics().invalidHeaderCount)
            assertEquals(42, f.source.receiverCapacity!!.acceptedLimit)
            assertEquals(1, f.channels.size)
            assertEquals(2L, router.appliedRevision)
            f.assertSingleOwner()
        } finally { f.source.stop() }
    }

    @Test fun limitsCanGrowAndShrinkWhileReceiveIsBlockedIncludingMaximumAndSentinel() = runBlocking {
        val f = Task16SourceHarness(); val router = RaspberryPiUdpInputRouter(1, f.source)
        f.source.start()
        try {
            val large = task16Profile().copy(payload = ImagePayloadProfile.Gray8(300, 200),
                transportLimits = task16Profile().transportLimits.copy(maxChunkPayloadBytes = 60000))
            assertTrue(router.apply(2, large) { true })
            assertEquals(60038, f.source.receiverCapacity!!.acceptedLimit)
            assertEquals(60039, f.source.receiverCapacity!!.receiveCapacity)
            f.packet(task16Wire(1, payload = ByteArray(60000) { 7 }))
            val max = f.take(); assertEquals(60000, max.frame.width * max.frame.height); max.frame.close()
            f.packet(ByteArray(60039))
            f.packet(ByteArray(65000))
            assertEquals(2L, f.source.udpDiagnostics().oversizedOrTruncatedDatagrams)
            assertTrue(router.apply(3, task16Profile()) { true })
            assertEquals(40, f.source.receiverCapacity!!.acceptedLimit)
            f.packet(ByteArray(41))
            assertEquals(3L, f.source.udpDiagnostics().oversizedOrTruncatedDatagrams)
            f.packet(task16Wire(2, payload = byteArrayOf(8, 9)))
            f.take().frame.close()
            assertEquals(1, f.channels.size)
            f.assertSingleOwner()
        } finally { f.source.stop() }
    }

    @Test fun eachReceiverEffectiveFieldRestartsOneReceiverWithoutAnotherSourceStart() = runBlocking {
        val f = Task16SourceHarness(); val router = RaspberryPiUdpInputRouter(1, f.source)
        f.source.start()
        try {
            var next = task16Profile().copy(listenPort = 46011)
            assertTrue(router.apply(2, next) { true })
            next = next.copy(raspberryPiIpv4 = "192.0.2.10")
            assertTrue(router.apply(3, next) { true })
            f.packet(task16Wire(1, payload = byteArrayOf(7, 8)), "192.0.2.11")
            assertEquals(1L, f.source.udpDiagnostics().sourceFilteredDatagrams)
            next = next.copy(networkInterface = NetworkInterfaceSelection.Specific("wifi", "192.0.2.20"))
            assertTrue(router.apply(4, next) { true })
            assertEquals("192.0.2.20", (f.channels.last().localAddress as InetSocketAddress).address.hostAddress)
            next = next.copy(networkInterface = NetworkInterfaceSelection.Specific("ethernet", "192.0.2.20"))
            assertTrue(router.apply(5, next) { true })
            assertEquals(5, f.channels.size)
            assertTrue(router.apply(6, next.copy(enabled = false)) { true })
            assertEquals(0, f.source.activeReceiverCount)
            assertEquals(UdpOperationalStatus.DISABLED, f.source.operationalStatus().status)
            assertEquals(1, f.source.activeProducerCount)
            assertTrue(router.apply(7, next) { true })
            f.assertSingleOwner()
            assertEquals(1L, f.source.totalProducerStarts)
        } finally { f.source.stop() }
    }

    @Test fun failedBindRestoresOldSocketAndDoesNotCallPersistence() = runBlocking {
        val f = Task16SourceHarness(); val router = RaspberryPiUdpInputRouter(1, f.source)
        f.source.start()
        try {
            f.failNextBind = true
            assertTrue(task16Failure { router.apply(2, task16Profile().copy(listenPort = 46011)) { error("must not persist") } } is BindException)
            assertEquals(1L, router.appliedRevision)
            assertEquals(task16Profile(), f.source.effectiveProfile)
            assertEquals(listOf(46010, 46010), f.boundPorts())
            f.packet(task16Wire(1, payload = byteArrayOf(7, 8)))
            f.take().frame.close()
            f.assertSingleOwner()
        } finally { f.source.stop() }
    }

    @Test fun validationCompilationAndInterfaceOwnershipFailuresPrecedeAnySwap() = runBlocking {
        val f = Task16SourceHarness(); val router = RaspberryPiUdpInputRouter(1, f.source)
        f.source.start()
        try {
            assertTrue(task16Failure { router.apply(2, task16Profile().copy(listenPort = 0)) { true } } is IllegalArgumentException)
            f.failCompilation = true
            assertTrue(task16Failure { router.apply(2, task16Profile().copy(payload = ImagePayloadProfile.Gray8(1, 1))) { true } } is IllegalStateException)
            f.failCompilation = false
            val missing = task16Profile().copy(networkInterface = NetworkInterfaceSelection.Specific("gone", "192.0.2.20"))
            assertTrue(task16Failure { router.apply(2, missing) { true } } is IllegalStateException)
            assertEquals(1, f.channels.size)
            assertEquals(task16Profile(), f.source.effectiveProfile)
            assertEquals(1L, router.appliedRevision)
            f.assertSingleOwner()
        } finally { f.source.stop() }
    }

    @Test fun parserRollbackRestoresLogicalCapacityAndPendingImageWithoutRestart() = runBlocking {
        val f = Task16SourceHarness(); val router = RaspberryPiUdpInputRouter(1, f.source)
        f.source.start()
        try {
            f.packet(task16Wire(1, payload = byteArrayOf(7, 8)))
            assertFalse(router.apply(2, task16Profile().copy(transportLimits = task16Profile().transportLimits.copy(maxChunkPayloadBytes = 9))) {
                assertEquals(47, f.source.receiverCapacity!!.acceptedLimit)
                false
            })
            assertEquals(40, f.source.receiverCapacity!!.acceptedLimit)
            assertEquals(1, f.source.pendingInputCount)
            val old = f.take(); assertEquals(0xff070707.toInt(), old.frame.pixels[0]); old.frame.close()
            assertEquals(1, f.channels.size)
            assertEquals(1L, router.appliedRevision)
        } finally { f.source.stop() }
    }

    @Test fun idleApplyNeverStartsSourceAndConcurrentLifecycleWaitsForTransaction() = runBlocking {
        val f = Task16SourceHarness(); val router = RaspberryPiUdpInputRouter(1, f.source)
        assertTrue(router.apply(2, task16Profile().copy(listenPort = 46011)) { true })
        assertEquals(0, f.channels.size)
        assertEquals(0, f.source.activeProducerCount)
        f.source.start()
        try {
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val applying = async { router.apply(3, task16Profile()) { entered.complete(Unit); withTimeout(3000) { release.await() }; true } }
            withTimeout(3000) { entered.await() }
            val stopping = async(start = CoroutineStart.UNDISPATCHED) { f.source.stop() }
            assertFalse(stopping.isCompleted)
            release.complete(Unit); assertTrue(applying.await()); stopping.await()
            assertEquals(0, f.source.activeReceiverCount)
            assertEquals(0, f.source.activeProducerCount)
        } finally { f.source.stop() }
    }

    @Test fun successfulReceiverRestartClearsThePreviousReceiveFailureState() = runBlocking {
        val f = Task16SourceHarness(); val router = RaspberryPiUdpInputRouter(1, f.source)
        f.source.start()
        try {
            f.channels.last().failReceive()
            withTimeout(3000) { while (f.source.state.value != ImageInputState.ERROR || f.source.activeReceiverCount != 0) delay(2) }
            assertTrue(router.apply(2, task16Profile().copy(listenPort = 46011)) { true })
            assertEquals(ImageInputState.RUNNING, f.source.state.value)
            assertNull(f.source.diagnostics.value.lastError)
            f.packet(task16Wire(1, payload = byteArrayOf(7, 8)))
            f.take().frame.close()
            f.assertSingleOwner()
        } finally { f.source.stop() }
    }

    @Test fun provisionalPacketWaitsForPersistenceAndOldErrorStateSurvivesRollback() = runBlocking {
        val f = Task16SourceHarness(); val router = RaspberryPiUdpInputRouter(1, f.source)
        f.source.start()
        try {
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val applying = async {
                router.apply(2, task16Profile().copy(payload = ImagePayloadProfile.Binary(2, 1, BinaryPacking.BYTE_PER_PIXEL))) {
                    entered.complete(Unit); withTimeout(3000) { release.await() }; true
                }
            }
            withTimeout(3000) { entered.await() }
            f.channels.last().enqueue(task16Wire(1, payload = byteArrayOf(0, 1)), "192.0.2.10")
            withTimeout(3000) { while (f.source.udpDiagnostics().datagramsReceived == 0L) delay(2) }
            assertEquals(0L, f.source.udpDiagnostics().framesCompleted)
            assertEquals(0, f.source.pendingInputCount)
            release.complete(Unit); assertTrue(applying.await())
            val input = f.take(); assertEquals(0xffffffff.toInt(), input.frame.pixels[1]); input.frame.close()
            f.channels.last().failReceive()
            withTimeout(3000) { while (f.source.state.value != ImageInputState.ERROR || f.source.activeReceiverCount != 0) delay(2) }
            assertFalse(router.apply(3, task16Profile().copy(listenPort = 46011)) { false })
            assertEquals(ImageInputState.ERROR, f.source.state.value)
            assertEquals("UDP_RECEIVE_FAILED", f.source.diagnostics.value.lastError)
            assertEquals(0, f.source.activeReceiverCount)
            assertEquals(1, f.source.activeProducerCount)
            assertEquals(2L, router.appliedRevision)
        } finally { f.source.stop() }
    }
}

internal fun task16Profile() = RaspberryPiUdpInputProfile.defaults().copy(enabled = true, listenPort = 46010,
    payload = ImagePayloadProfile.Gray8(2, 1), transportLimits = RaspberryPiUdpInputProfile.defaults().transportLimits.copy(maxChunkPayloadBytes = 2))

internal fun task16Wire(id: Long, index: Int = 0, count: Int = 1, payload: ByteArray,
    total: Int = payload.size, alternateEnvelope: Boolean = false): ByteArray =
    (if (alternateEnvelope) byteArrayOf(0xbb.toByte(), 2) else byteArrayOf(0xaa.toByte(), 1)) +
        StructuredTransportCodec.encode(StructuredTransportHeader(UdpCameraId.A, id.toULong(), index, count, 0,
            payload.size, total.toLong()), payload) + (if (alternateEnvelope) byteArrayOf(14, 11) else byteArrayOf(13, 10))

internal suspend fun task16Failure(action: suspend () -> Unit): Throwable {
    try { action() } catch (failure: Throwable) { return failure }
    throw AssertionError("Expected operation to fail")
}

/** Only the operating system socket is substituted; receiver loop, processor and frame ownership are real. */
internal open class Task16SourceHarness(
    profile: RaspberryPiUdpInputProfile = task16Profile(),
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    appliedNetworkPlan: EffectiveUdpNetworkPlan? = null,
    private val allowPreparedCandidate: Boolean = false,
) {
    val channels = mutableListOf<Task16Channel>()
    var failNextBind = false
    var failCompilation = false
    var failNextStop = false
    var onOpen: (Task16Channel) -> Unit = {}
    val source = RaspberryPiUdpInputSource(profile,
        dispatcher = dispatcher,
        ipv4Resolver = BackendIpv4Resolver(Ipv4NetworkInterfaceProvider {
            listOf("wifi", "ethernet").map { Ipv4NetworkInterfaceEntry(it, it, "192.0.2.20", true, false, false, true, 1) }
        }),
        channelFactory = UdpChannelFactory {
            check(allowPreparedCandidate || channels.none { it.isOpen || it.receiving }) {
                "Second receiver opened before previous receiver joined"
            }
            Task16Channel(failNextBind.also { failNextBind = false }) { failNextStop.also { failNextStop = false } }
                .also { channels += it; onOpen(it) }
        }, compiledFactory = { p, observer ->
            check(!failCompilation) { "compile failure" }
            CompiledRaspberryPiInputProfile.compile(p, observer)
        }, appliedNetworkPlan = appliedNetworkPlan)
    suspend fun packet(bytes: ByteArray, ip: String = "192.0.2.10") {
        val socket = channels.last()
        val completed = socket.completedReceives.get() + 1
        socket.enqueue(bytes, ip)
        withTimeout(3000) { while (socket.completedReceives.get() < completed) delay(2) }
    }
    suspend fun take() = withTimeout(3000) { source.inputs.first() } as CameraInput.Image
    fun boundPorts() = channels.mapNotNull { (it.localAddress as? InetSocketAddress)?.port }
    fun assertSingleOwner() {
        assertEquals(1, source.activeProducerCount)
        assertEquals(1, source.activeReceiverCount)
        assertEquals(1, channels.count { it.isOpen })
    }
}

internal class Task16Channel(
    private val failBind: Boolean,
    private val failClose: () -> Boolean = { false },
) : DatagramChannel(SelectorProvider.provider()) {
    private val packets = LinkedBlockingQueue<Pair<ByteArray, InetSocketAddress>>()
    private var bound: SocketAddress? = null
    private var delivered = false
    @Volatile var receiving = false
    val completedReceives = AtomicLong()
    fun enqueue(bytes: ByteArray, ip: String) { packets.put(bytes to InetSocketAddress(ip, 9000)) }
    fun failReceive() { packets.put(byteArrayOf() to InetSocketAddress("127.0.0.1", 0)) }
    override fun receive(dst: ByteBuffer): SocketAddress {
        if (delivered) completedReceives.incrementAndGet()
        delivered = false
        receiving = true
        try {
            val (bytes, address) = packets.take()
            if (!isOpen) throw AsynchronousCloseException()
            if (address.port == 0) throw java.io.IOException("injected receive failure")
            dst.put(bytes, 0, minOf(bytes.size, dst.remaining())); delivered = true
            return address
        } finally { receiving = false }
    }
    override fun bind(local: SocketAddress?): DatagramChannel {
        if (failBind) throw BindException("injected bind failure")
        bound = local; return this
    }
    override fun getLocalAddress() = bound
    override fun <T : Any?> setOption(name: SocketOption<T>, value: T): DatagramChannel = this
    override fun <T : Any?> getOption(name: SocketOption<T>): T = throw UnsupportedOperationException()
    override fun supportedOptions(): Set<SocketOption<*>> = emptySet()
    override fun implCloseSelectableChannel() {
        packets.offer(byteArrayOf() to InetSocketAddress("127.0.0.1", 1))
        if (failClose()) throw java.io.IOException("injected close failure")
    }
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
