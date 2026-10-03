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

enum class UdpReceiverState { IDLE, RUNNING, ERROR }

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
    private val consumer: UdpDatagramConsumer,
) {
    private val lifecycle = Mutex()
    private val sourceFilter = Ipv4SourceFilter(profile.sourceIp)
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

    suspend fun start() = lifecycle.withLock {
        if (receiverJob?.isActive == true) return@withLock
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

    private companion object {
        const val MIN_SOCKET_RECEIVE_QUEUE_BYTES = 256 * 1024
    }
}
