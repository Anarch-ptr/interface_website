package com.filamentvision.backend.input.runtime

import com.filamentvision.backend.input.diagnostics.UdpDiagnostics
import com.filamentvision.backend.input.network.BackendIpv4Resolver
import com.filamentvision.backend.input.profile.*
import com.filamentvision.backend.input.reassembly.StructuredReassemblyObserver
import com.filamentvision.backend.input.transport.*
import com.filamentvision.core.input.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.StandardProtocolFamily
import java.nio.channels.DatagramChannel

/** One Runtime-owned source reservation, one socket job and one serialized processing chain. */
class RaspberryPiUdpInputSource(
    private val profile: RaspberryPiUdpInputProfile,
    private val ipv4Resolver: BackendIpv4Resolver = BackendIpv4Resolver(),
    private val channelFactory: UdpChannelFactory = UdpChannelFactory { DatagramChannel.open(StandardProtocolFamily.INET) },
    private val monotonicClock: MonotonicClock = MonotonicClock(System::nanoTime),
    private val epochClock: EpochClock = EpochClock(System::currentTimeMillis),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val compiledFactory: (RaspberryPiUdpInputProfile, StructuredReassemblyObserver) -> CompiledRaspberryPiInputProfile =
        CompiledRaspberryPiInputProfile::compile,
) : CameraInputSource {
    init { require(RaspberryPiUdpInputProfileValidator().validate(profile).isValid) { "Invalid Raspberry Pi UDP input profile" } }

    private val lifecycle = Mutex()
    private val processingLock = Any()
    private val mailboxLock = Any()
    // Caller-owned frames may outlive a processor or receiver lifecycle.
    private val decodedPermit = DecodedFramePermit()
    private val activity = UdpActivity()
    private val udp = UdpDiagnostics(TransportMode.STRUCTURED_CHUNKED)
    private val ready = Channel<Unit>(Channel.CONFLATED)
    private var pending: CameraInput.Image? = null
    private var admitting = false
    private var processor: RaspberryPiUdpProcessor? = null
    @Volatile private var receiver: UdpReceiver? = null
    private var expiryJob: Job? = null
    private val mutableState = MutableStateFlow(ImageInputState.IDLE)
    private val mutableDiagnostics = MutableStateFlow(ImageInputDiagnostics())
    override val state = mutableState.asStateFlow()
    override val diagnostics = mutableDiagnostics.asStateFlow()
    @Volatile override var activeProducerCount = 0
        private set
    @Volatile var totalProducerStarts = 0L
        private set

    // Taking from the mailbox transfers ownership to the collector. In particular first()/take()
    // cancellation after delivery must not close the caller's admitted frame.
    override val inputs: Flow<CameraInput> = object : Flow<CameraInput> {
        override suspend fun collect(collector: FlowCollector<CameraInput>) {
            while (true) {
                val context = currentCoroutineContext()
                val input = synchronized(mailboxLock) {
                    context.ensureActive()
                    pending.also { pending = null }
                }
                if (input == null) {
                    ready.receive()
                    continue
                }
                collector.emit(input)
            }
        }
    }

    val activeReceiverCount: Int get() = receiver?.activeReceiverCount ?: 0
    val pendingInputCount: Int get() = synchronized(mailboxLock) { if (pending == null) 0 else 1 }
    val preAdmissionDroppedInputs: Long get() = udp.snapshot().preAdmissionDroppedInputs
    fun udpDiagnostics() = udp.snapshot()
    fun operationalStatus() = activity.snapshot(monotonicClock.nowNanos())

    override suspend fun start() = lifecycle.withLock {
        if (activeProducerCount == 1) return@withLock
        mutableState.value = ImageInputState.STARTING
        mutableDiagnostics.value = mutableDiagnostics.value.copy(lastError = null)
        activity.reset(profile.enabled)
        activeProducerCount = 1 // Runtime's source lifecycle reservation; jobs never increment it.
        try {
            if (profile.enabled) {
                synchronized(processingLock) {
                    processor = RaspberryPiUdpProcessor(compiledFactory(profile, reassemblyObserver()), decodedPermit)
                }
                synchronized(mailboxLock) { admitting = true }
                // Refresh the selected address immediately before constructing/opening the receiver.
                val bindIpv4 = when (val selected = profile.networkInterface) {
                    NetworkInterfaceSelection.Auto -> "0.0.0.0"
                    is NetworkInterfaceSelection.Specific -> {
                        val resolved = ipv4Resolver.resolve(selected, profile.raspberryPiIpv4)
                        if (resolved.selectedInterface == null) {
                            activity.failed("SELECTED_IPV4_INTERFACE_UNAVAILABLE")
                            error("SELECTED_IPV4_INTERFACE_UNAVAILABLE")
                        }
                        resolved.selectedInterface.ipv4
                    }
                }
                val next = createReceiver(bindIpv4)
                receiver = next
                next.start()
                activity.bound(monotonicClock.nowNanos())
                expiryJob = CoroutineScope(dispatcher).launch {
                    while (isActive) {
                        delay(100)
                        synchronized(processingLock) { processor?.expire(monotonicClock.nowNanos()) }
                    }
                }
            }
            totalProducerStarts++
            mutableState.compareAndSet(ImageInputState.STARTING, ImageInputState.RUNNING)
        } catch (error: Throwable) {
            withContext(NonCancellable) { cleanup() }
            if (error is CancellationException) {
                activity.reset(false)
                mutableState.value = ImageInputState.IDLE
            } else {
                if (activity.snapshot(monotonicClock.nowNanos()).detail == null) activity.failed("UDP_BIND_FAILED")
                mutableDiagnostics.value = mutableDiagnostics.value.copy(lastError = activity.snapshot(monotonicClock.nowNanos()).detail)
                mutableState.value = ImageInputState.ERROR
            }
            throw error
        }
    }

    override suspend fun stop() = lifecycle.withLock {
        withContext(NonCancellable) {
            mutableState.value = ImageInputState.STOPPING
            cleanup()
            activity.reset(false)
            mutableState.value = ImageInputState.IDLE
        }
    }

    private suspend fun cleanup() {
        synchronized(mailboxLock) { admitting = false }
        receiver?.stop()
        receiver = null
        expiryJob?.cancelAndJoin()
        expiryJob = null
        synchronized(processingLock) { processor = null }
        synchronized(mailboxLock) {
            pending?.frame?.close(); pending = null
            ready.tryReceive()
        }
        activeProducerCount = 0
    }

    internal fun offerInput(input: CameraInput.Image) = synchronized(mailboxLock) {
        if (!admitting) { input.frame.close(); return@synchronized }
        pending?.let {
            it.frame.close()
            udp.preAdmissionDropped()
            mutableDiagnostics.value = mutableDiagnostics.value.copy(droppedFrames = preAdmissionDroppedInputs)
        }
        pending = input
        ready.trySend(Unit)
        Unit
    }

    private fun createReceiver(bindIpv4: String): UdpReceiver {
        // Legacy receiver configuration is an internal adapter only; the public profile stays single-stream.
        val receiveProfile = UdpInputProfile(UdpCameraId.A, profile.enabled, profile.raspberryPiIpv4, profile.listenPort,
            TransportMode.STRUCTURED_CHUNKED, PayloadProfile.Gray8(1, 1), ApplicationFramingProfile(), profile.transportLimits)
        return UdpReceiver(receiveProfile, observer = object : UdpReceiverObserver {
            override fun onReceived(datagram: ReceivedDatagram) = udp.datagramReceived(datagram.size, datagram.metadata())
            override fun onSourceFiltered(datagram: ReceivedDatagram) {
                udp.sourceFiltered(); activity.rejected(datagram.receivedAtMonotonicNanos)
            }
            override fun onDropped(reason: UdpReceiverDropReason, datagram: ReceivedDatagram) {
                activity.accepted(datagram.receivedAtMonotonicNanos)
                udp.datagramTooLargeOrTruncated()
            }
            override fun onFailure(error: Throwable) {
                activity.reset(false)
                mutableDiagnostics.value = mutableDiagnostics.value.copy(lastError = "UDP_RECEIVE_FAILED")
                mutableState.value = ImageInputState.ERROR
            }
        }, channelFactory = channelFactory, monotonicClock = monotonicClock, epochClock = epochClock,
            dispatcher = dispatcher, envelopeBytes = 4, bindIpv4 = bindIpv4) { datagram ->
            activity.accepted(datagram.receivedAtMonotonicNanos)
            synchronized(processingLock) {
                when (val result = checkNotNull(processor).process(datagram.bytes, datagram.metadata())) {
                    is ProcessorResult.Accepted -> { udp.frameCompleted(result.completedAtMonotonicNanos); offerInput(result.input) }
                    is ProcessorResult.Rejected -> udp.rejected(result.stage, result.reason)
                    ProcessorResult.Pending -> Unit
                }
            }
        }
    }

    private fun reassemblyObserver() = object : StructuredReassemblyObserver {
        override fun onFrameStarted() = udp.frameStarted()
        override fun onDuplicateChunk() = udp.chunkDuplicate()
        override fun onOutOfOrderChunk() = udp.chunkOutOfOrder()
        override fun onLateChunk() = udp.lateChunk()
        override fun onTimeout(missingChunks: Int) = udp.reassemblyTimeout(missingChunks.toLong())
        override fun onEvicted(missingChunks: Int) = udp.reassemblyEviction(missingChunks.toLong())
    }

    private fun ReceivedDatagram.metadata() = ReceivedDatagramMetadata(source, receivedAtMonotonicNanos, receivedAtEpochMillis)
}
