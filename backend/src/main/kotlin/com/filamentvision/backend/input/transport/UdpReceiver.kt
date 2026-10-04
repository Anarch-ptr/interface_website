package com.filamentvision.backend.input.transport

import com.filamentvision.backend.input.profile.UdpInputProfile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.StandardProtocolFamily
import java.net.StandardSocketOptions
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousCloseException
import java.nio.channels.DatagramChannel
import java.nio.channels.ClosedChannelException
import kotlin.math.max

enum class UdpReceiverState { IDLE, PREPARED, RUNNING, ERROR }

enum class UdpReceiverDropReason { DATAGRAM_TOO_LARGE_OR_TRUNCATED }

interface UdpReceiverObserver {
    fun onReceived(size: Int, receivedAtMonotonicNanos: Long) = Unit
    fun onReceived(datagram: ReceivedDatagram) = onReceived(datagram.size, datagram.receivedAtMonotonicNanos)
    fun onSourceFiltered(datagram: ReceivedDatagram) = Unit
    fun onDropped(reason: UdpReceiverDropReason) = Unit
    fun onDropped(reason: UdpReceiverDropReason, datagram: ReceivedDatagram) = onDropped(reason)
    fun onFailure(error: Throwable) = Unit
}

fun interface UdpDatagramConsumer {
    suspend fun accept(datagram: ReceivedDatagram)
}

fun interface UdpChannelFactory {
    fun open(): DatagramChannel
}

fun interface MonotonicClock {
    fun nowNanos(): Long
}

fun interface EpochClock {
    fun nowMillis(): Long
}

class UdpReceiver(
    private val profile: UdpInputProfile,
    private val observer: UdpReceiverObserver = object : UdpReceiverObserver {},
    private val channelFactory: UdpChannelFactory = UdpChannelFactory {
        DatagramChannel.open(StandardProtocolFamily.INET)
    },
    private val monotonicClock: MonotonicClock = MonotonicClock(System::nanoTime),
    private val epochClock: EpochClock = EpochClock(System::currentTimeMillis),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    envelopeBytes: Int = 0,
    private val bindIpv4: String = "0.0.0.0",
    maximumReceiveCapacity: UdpReceiveCapacity? = null,
    sourceIpv4s: Set<Inet4Address>? = profile.sourceIp?.let { setOf(requireIpv4LiteralForConfiguration(it)) },
    private val consumer: UdpDatagramConsumer,
) {
    private val lifecycle = Mutex()
    @Volatile private var sourceFilter = Ipv4SourceFilter(sourceIpv4s)
    @Volatile var receiveCapacity: UdpReceiveCapacity = UdpReceiveCapacityCalculator.forProfile(profile, envelopeBytes)
        private set
    private val physicalCapacity = maximumReceiveCapacity ?: receiveCapacity

    init {
        require(physicalCapacity == UdpReceiveCapacityCalculator.calculate(physicalCapacity.acceptedLimit.toLong()))
        require(receiveCapacity.acceptedLimit <= physicalCapacity.acceptedLimit)
    }

    /** Changes the logical sentinel guard without replacing the single receive buffer or job. */
    internal fun updateReceiveCapacity(capacity: UdpReceiveCapacity) {
        require(capacity == UdpReceiveCapacityCalculator.calculate(capacity.acceptedLimit.toLong()))
        require(capacity.acceptedLimit <= physicalCapacity.acceptedLimit)
        receiveCapacity = capacity
    }

    /** Atomically replaces the already-resolved applied source set; this never performs DNS. */
    internal fun updateSourceIpv4s(sourceIpv4s: Set<Inet4Address>?) {
        sourceFilter = Ipv4SourceFilter(sourceIpv4s)
    }

    @Volatile
    var state: UdpReceiverState = UdpReceiverState.IDLE
        private set

    @Volatile
    private var channel: DatagramChannel? = null
    private var receiverJob: Job? = null

    val isRunning: Boolean get() = state == UdpReceiverState.RUNNING && receiverJob?.isActive == true
    val isSocketOpen: Boolean get() = channel?.isOpen == true
    val activeReceiverCount: Int get() = if (receiverJob?.isActive == true) 1 else 0
    val localAddress: InetSocketAddress
        get() = channel?.localAddress as? InetSocketAddress
            ?: error("UDP receiver is not bound")
    val actualSocketReceiveBufferBytes: Int
        get() = channel?.getOption(StandardSocketOptions.SO_RCVBUF)
            ?: error("UDP receiver is not bound")

    suspend fun prepare() = lifecycle.withLock {
        if (channel?.isOpen == true) return@withLock
        prepareLocked()
    }

    suspend fun commit() = lifecycle.withLock {
        check(channel?.isOpen == true) { "UDP receiver must be prepared before commit" }
        commitLocked()
    }

    suspend fun start() = lifecycle.withLock {
        if (receiverJob?.isActive == true) return@withLock
        if (channel?.isOpen != true) prepareLocked()
        commitLocked()
    }

    private suspend fun prepareLocked() {
        var opening: DatagramChannel? = null
        val opened = try {
            withContext(dispatcher) {
                channelFactory.open().also { socket ->
                    opening = socket
                    socket.setOption(
                        StandardSocketOptions.SO_RCVBUF,
                        max(MIN_SOCKET_RECEIVE_QUEUE_BYTES, physicalCapacity.receiveCapacity),
                    )
                    socket.bind(InetSocketAddress(InetAddress.getByName(bindIpv4), profile.listenPort))
                    check((socket.localAddress as InetSocketAddress).address is Inet4Address)
                }
            }
        } catch (error: Throwable) {
            opening?.close()
            state = UdpReceiverState.ERROR
            throw error
        }

        channel = opened
        state = UdpReceiverState.PREPARED
    }

    private fun commitLocked() {
        if (receiverJob?.isActive == true) return
        val opened = checkNotNull(channel) { "UDP receiver must be prepared before commit" }
        state = UdpReceiverState.RUNNING
        receiverJob = CoroutineScope(SupervisorJob() + dispatcher).launch {
            receiveLoop(opened)
        }
    }

    suspend fun stop() {
        val job = lifecycle.withLock {
            channel?.close()
            channel = null
            receiverJob.also { receiverJob = null }
        }
        job?.cancelAndJoin()
        state = UdpReceiverState.IDLE
    }

    private suspend fun receiveLoop(socket: DatagramChannel) {
        val receiveArray = ByteArray(physicalCapacity.receiveCapacity)
        val receiveBuffer = ByteBuffer.wrap(receiveArray)
        try {
            while (currentCoroutineContext().isActive) {
                receiveBuffer.clear()
                val source = socket.receive(receiveBuffer) as? InetSocketAddress ?: continue
                val receivedSize = receiveBuffer.position()
                val datagram = ReceivedDatagram(
                    source = source,
                    receivedAtMonotonicNanos = monotonicClock.nowNanos(),
                    receivedAtEpochMillis = epochClock.nowMillis(),
                    bytes = BorrowedDatagramBytes(receiveArray, receivedSize),
                )
                try {
                    observer.onReceived(datagram)
                    if (!sourceFilter.accepts(source)) {
                        observer.onSourceFiltered(datagram)
                    } else if (receivedSize > receiveCapacity.acceptedLimit ||
                        (physicalCapacity.sentinelEnabled && receivedSize == physicalCapacity.receiveCapacity)) {
                        observer.onDropped(UdpReceiverDropReason.DATAGRAM_TOO_LARGE_OR_TRUNCATED, datagram)
                    } else {
                        consumer.accept(datagram)
                    }
                } finally {
                    datagram.bytes.invalidate()
                }
            }
        } catch (_: AsynchronousCloseException) {
            // Normal stop closes the blocking IPv4 channel.
        } catch (_: ClosedChannelException) {
            // Normal stop may surface as ClosedChannelException on some JDKs.
        } catch (error: Throwable) {
            if (socket.isOpen) {
                state = UdpReceiverState.ERROR
                observer.onFailure(error)
            }
        } finally {
            socket.close()
        }
    }

    companion object {
        const val MIN_SOCKET_RECEIVE_QUEUE_BYTES = 256 * 1024

        internal fun requireIpv4LiteralForConfiguration(value: String): Inet4Address {
            val parts = value.split('.')
            require(parts.size == 4) { "sourceIp must be an IPv4 literal" }
            val bytes = ByteArray(4)
            parts.forEachIndexed { index, part ->
                require(part.isNotEmpty() && part.all { it in '0'..'9' } && (part.length == 1 || part.first() != '0')) {
                    "sourceIp must be a canonical IPv4 literal"
                }
                val octet = requireNotNull(part.toIntOrNull()).also { require(it in 0..255) }
                bytes[index] = octet.toByte()
            }
            return InetAddress.getByAddress(bytes) as Inet4Address
        }
    }
}
