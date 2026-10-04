package com.filamentvision.backend.input.runtime

import com.filamentvision.backend.input.diagnostics.UdpDiagnostics
import com.filamentvision.backend.input.inspector.PacketInspector
import com.filamentvision.backend.input.inspector.RawParseStatus
import com.filamentvision.backend.input.network.BackendIpv4Resolver
import com.filamentvision.backend.input.network.EffectiveUdpNetworkPlan
import com.filamentvision.backend.input.network.NetworkConsistencyStatus
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
    profile: RaspberryPiUdpInputProfile,
    private val ipv4Resolver: BackendIpv4Resolver = BackendIpv4Resolver(),
    private val channelFactory: UdpChannelFactory = UdpChannelFactory { DatagramChannel.open(StandardProtocolFamily.INET) },
    private val monotonicClock: MonotonicClock = MonotonicClock(System::nanoTime),
    private val epochClock: EpochClock = EpochClock(System::currentTimeMillis),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val compiledFactory: (RaspberryPiUdpInputProfile, StructuredReassemblyObserver) -> CompiledRaspberryPiInputProfile =
        CompiledRaspberryPiInputProfile::compile,
    val packetInspector: PacketInspector = PacketInspector(),
    appliedNetworkPlan: EffectiveUdpNetworkPlan? = null,
) : CameraInputSource {
    init { require(RaspberryPiUdpInputProfileValidator().validate(profile).isValid) { "Invalid Raspberry Pi UDP input profile" } }

    private val lifecycle = Mutex()
    private val processingLock = Mutex()
    private val mailboxLock = Any()
    private val receiverStatusLock = Any()
    private val preparedLock = Any()
    // Caller-owned frames may outlive a processor or receiver lifecycle.
    private val decodedPermit = DecodedFramePermit()
    @Volatile private var profile = profile
    @Volatile private var appliedNetworkPlan = appliedNetworkPlan
    @Volatile private var activity = UdpActivity()
    @Volatile private var consistencyFailure: RaspberryPiUdpConsistencyException? = null
    private val udp = UdpDiagnostics(TransportMode.STRUCTURED_CHUNKED)
    private val ready = Channel<Unit>(Channel.CONFLATED)
    private var pending: CameraInput.Image? = null
    private var admitting = false
    private var processor: RaspberryPiUdpProcessor? = null
    @Volatile private var receiver: UdpReceiver? = null
    @Volatile private var preparedReceiver: PreparedUdpReceiver? = null
    private var preparingReceiver = false
    @Volatile var maximumPreparedReceiverCount = 0
        private set
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
    val effectiveProfile: RaspberryPiUdpInputProfile get() = profile
    val effectiveNetworkPlan: EffectiveUdpNetworkPlan? get() = appliedNetworkPlan
    val networkConsistency: NetworkConsistencyStatus
        get() = if (consistencyFailure == null) NetworkConsistencyStatus.HEALTHY
        else NetworkConsistencyStatus.NETWORK_CONFIGURATION_INCONSISTENT
    val receiverCapacity: UdpReceiveCapacity? get() = receiver?.receiveCapacity
    val pendingInputCount: Int get() = synchronized(mailboxLock) { if (pending == null) 0 else 1 }
    val preAdmissionDroppedInputs: Long get() = udp.snapshot().preAdmissionDroppedInputs
    fun udpDiagnostics() = udp.snapshot()
    fun operationalStatus() = activity.snapshot(monotonicClock.nowNanos())

    override suspend fun start() = lifecycle.withLock {
        checkConsistency()
        if (activeProducerCount == 1) return@withLock
        mutableState.value = ImageInputState.STARTING
        mutableDiagnostics.value = mutableDiagnostics.value.copy(lastError = null)
        activity.reset(profile.enabled)
        activeProducerCount = 1 // Runtime's source lifecycle reservation; jobs never increment it.
        try {
            if (profile.enabled) {
                processingLock.withLock {
                    processor = RaspberryPiUdpProcessor(compiledFactory(profile, reassemblyObserver()), decodedPermit, packetInspector)
                }
                synchronized(mailboxLock) { admitting = true }
                // Refresh the selected address immediately before constructing/opening the receiver.
                val bindIpv4 = try { resolveBind(profile) } catch (error: Throwable) {
                    activity.failed("SELECTED_IPV4_INTERFACE_UNAVAILABLE")
                    throw error
                }
                val next = createReceiver(bindIpv4, profile, appliedNetworkPlan)
                receiver = next
                next.start()
                activity.bound(monotonicClock.nowNanos())
            }
            expiryJob = CoroutineScope(dispatcher).launch {
                while (isActive) {
                    delay(100)
                    processingLock.withLock { processor?.expire(monotonicClock.nowNanos()) }
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
        val candidate = synchronized(preparedLock) { preparedReceiver }
        candidate?.closePrepared()
        receiver?.stop()
        receiver = null
        expiryJob?.cancelAndJoin()
        expiryJob = null
        processingLock.withLock { processor = null }
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

    internal fun checkConsistency() { consistencyFailure?.let { throw it } }

    /** The lifecycle and processing locks span the repository decision and any compensation.
     * Receive jobs can wait here, but no provisional frame is decoded/admitted before commit.
     * The mailbox and shared decoded permit are never replaced or cleared by Apply.
     */
    internal suspend fun reconfigure(candidate: RaspberryPiUdpInputProfile, persist: suspend () -> Boolean): Boolean = lifecycle.withLock {
        checkConsistency()
        val compiled = compiledFactory(candidate, reassemblyObserver())
        val next = compiled.profile
        val bindIpv4 = resolveBind(next)
        val preparedProcessor = RaspberryPiUdpProcessor(compiled, decodedPermit, packetInspector)
        currentCoroutineContext().ensureActive()
        withContext(NonCancellable) {
            processingLock.withLock {
                val oldProfile = profile
                val oldProcessor = processor
                val oldReceiver = receiver
                val oldCapacity = oldReceiver?.receiveCapacity
                val oldActivity = activity
                val (oldState, oldDiagnostics) = synchronized(receiverStatusLock) {
                    mutableState.value to mutableDiagnostics.value
                }
                val oldAdmitting = synchronized(mailboxLock) { admitting }
                val active = activeProducerCount == 1
                val oldReceiverRequired = active && oldProfile.enabled && oldState == ImageInputState.RUNNING
                val restart = oldProfile.enabled != next.enabled || oldProfile.networkInterface != next.networkInterface ||
                    oldProfile.listenPort != next.listenPort || oldProfile.raspberryPiIpv4 != next.raspberryPiIpv4
                var committed = false
                var failure: Throwable? = null
                try {
                    if (restart) oldReceiver?.stop() // Close and join before constructing the replacement.
                    profile = next
                    processor = if (active && next.enabled) preparedProcessor else null
                    if (restart) {
                        receiver = null
                        activity = UdpActivity().also { it.reset(active && next.enabled) }
                        if (active) {
                            mutableState.value = ImageInputState.STARTING
                            mutableDiagnostics.value = mutableDiagnostics.value.copy(lastError = null)
                        }
                        if (active && next.enabled) {
                            receiver = createReceiver(bindIpv4, next, null)
                            receiver!!.start()
                            activity.bound(monotonicClock.nowNanos())
                        }
                        if (active) mutableState.compareAndSet(ImageInputState.STARTING, ImageInputState.RUNNING)
                        synchronized(mailboxLock) { admitting = active && next.enabled }
                    } else {
                        receiver?.updateReceiveCapacity(receiveCapacity(next))
                    }
                    committed = persist()
                } catch (error: Throwable) {
                    failure = error
                }
                if (committed) return@withLock true
                try {
                    if (restart) receiver?.stop()
                    profile = oldProfile
                    processor = oldProcessor
                    receiver = oldReceiver
                    if (oldCapacity != null) oldReceiver.updateReceiveCapacity(oldCapacity)
                    // A parser-only swap keeps its socket, but that socket can fail while the CAS waits.
                    // Serialize the health check/status restoration with onFailure so ERROR cannot be erased.
                    val restoreReceiver = synchronized(receiverStatusLock) {
                        if (oldReceiverRequired && (oldReceiver?.isRunning != true || !oldReceiver.isSocketOpen)) {
                            true
                        } else {
                            activity = oldActivity
                            mutableState.value = oldState
                            mutableDiagnostics.value = oldDiagnostics
                            false
                        }
                    }
                    if (restoreReceiver) {
                        checkNotNull(oldReceiver) { "UDP_ROLLBACK_RECEIVER_MISSING" }
                        oldReceiver.stop() // Join a failed loop before reopening, including parser-only rollback.
                        synchronized(receiverStatusLock) {
                            activity = UdpActivity().also { it.reset(true) }
                            mutableState.value = ImageInputState.STARTING
                        }
                        oldReceiver.start()
                        synchronized(receiverStatusLock) {
                            check(oldReceiver.isRunning && oldReceiver.isSocketOpen && mutableState.value == ImageInputState.STARTING) {
                                "UDP_ROLLBACK_RECEIVER_NOT_RUNNING"
                            }
                            activity.bound(monotonicClock.nowNanos())
                            mutableState.value = oldState
                            mutableDiagnostics.value = oldDiagnostics
                        }
                    }
                    synchronized(mailboxLock) { admitting = oldAdmitting }
                } catch (compensation: Throwable) {
                    val fatal = RaspberryPiUdpConsistencyException(failure ?: IllegalStateException("UDP_APPLY_CAS_CONFLICT"), compensation)
                    consistencyFailure = fatal
                    try { receiver?.stop() } catch (cleanup: Throwable) { fatal.addSuppressed(cleanup) }
                    receiver = null
                    activity.reset(false)
                    mutableState.value = ImageInputState.ERROR
                    mutableDiagnostics.value = mutableDiagnostics.value.copy(lastError = "UDP_APPLY_CONSISTENCY_FAILURE")
                    synchronized(mailboxLock) { admitting = false }
                    throw fatal
                }
                failure?.let { throw it }
                false
            }
        }
    }

    private fun resolveBind(candidate: RaspberryPiUdpInputProfile): String = appliedNetworkPlan?.actualBindIpv4 ?: when (val selected = candidate.networkInterface) {
        NetworkInterfaceSelection.Auto -> "0.0.0.0"
        is NetworkInterfaceSelection.Specific -> ipv4Resolver.resolve(selected, candidate.raspberryPiIpv4).selectedInterface?.ipv4
            ?: error("SELECTED_IPV4_INTERFACE_UNAVAILABLE")
    }

    private fun receiveCapacity(candidate: RaspberryPiUdpInputProfile): UdpReceiveCapacity =
        UdpReceiveCapacityCalculator.calculate(38L + candidate.transportLimits.maxChunkPayloadBytes)

    internal suspend fun prepareReceiver(
        candidate: RaspberryPiUdpInputProfile,
        plan: EffectiveUdpNetworkPlan,
    ): PreparedUdpReceiver {
        synchronized(preparedLock) {
            check(!preparingReceiver && preparedReceiver == null) { "Only one candidate UDP receiver may be prepared" }
            preparingReceiver = true
        }
        val udpReceiver = createReceiver(plan.actualBindIpv4, candidate, plan)
        return try {
            val prepared = PreparedUdpReceiver.prepare(udpReceiver) {
                synchronized(preparedLock) { preparedReceiver = null }
            }
            synchronized(preparedLock) {
                preparingReceiver = false
                preparedReceiver = prepared
                maximumPreparedReceiverCount = maxOf(maximumPreparedReceiverCount, 1)
            }
            prepared
        } catch (error: Throwable) {
            synchronized(preparedLock) { preparingReceiver = false }
            throw error
        }
    }

    val preparedReceiverCount: Int
        get() = synchronized(preparedLock) { if (preparingReceiver || preparedReceiver != null) 1 else 0 }

    /**
     * Stages compilation and any non-overlapping replacement socket while the currently applied
     * source remains authoritative. [persist] is the transaction boundary: no candidate state is
     * observable before it returns true, and a false/failed CAS only closes provisional resources.
     */
    internal suspend fun applyResolvedConfiguration(
        candidate: RaspberryPiUdpInputProfile,
        plan: EffectiveUdpNetworkPlan,
        persist: suspend () -> Boolean,
    ): Boolean = lifecycle.withLock {
        checkConsistency()
        val compiled = compiledFactory(candidate, reassemblyObserver())
        val next = compiled.profile
        val nextProcessor = RaspberryPiUdpProcessor(compiled, decodedPermit, packetInspector)
        val active = activeProducerCount == 1
        val currentReceiver = receiver
        val restart = active && next.enabled && (
            currentReceiver == null ||
                profile.listenPort != next.listenPort ||
                appliedNetworkPlan?.actualBindIpv4 != plan.actualBindIpv4
            )
        val prepared = if (restart) prepareReceiver(next, plan) else null
        val persisted = try {
            persist()
        } catch (error: Throwable) {
            prepared?.closePrepared()
            throw error
        }
        if (!persisted) {
            prepared?.closePrepared()
            return@withLock false
        }

        // Everything that can fail (validation, compilation, resolution, bind and CAS) is complete.
        // Publication is deliberately kept inside the source lifecycle boundary.
        try {
            withContext(NonCancellable) {
                processingLock.withLock {
                if (restart || (active && profile.enabled && !next.enabled)) currentReceiver?.stop()
                profile = next
                appliedNetworkPlan = plan.immutableCopy()
                processor = if (active && next.enabled) nextProcessor else null
                if (active && next.enabled) {
                    if (prepared != null) {
                        receiver = prepared.receiver
                        prepared.commit()
                    } else {
                        currentReceiver?.updateReceiveCapacity(receiveCapacity(next))
                        currentReceiver?.updateSourceIpv4s(compileSourceFilter(next, plan))
                        receiver = currentReceiver
                    }
                    activity = UdpActivity().also {
                        it.reset(true)
                        it.bound(monotonicClock.nowNanos())
                    }
                    mutableState.value = ImageInputState.RUNNING
                    mutableDiagnostics.value = mutableDiagnostics.value.copy(lastError = null)
                    synchronized(mailboxLock) { admitting = true }
                } else {
                    receiver = null
                    activity = UdpActivity().also { it.reset(false) }
                    if (active) mutableState.value = ImageInputState.RUNNING
                    synchronized(mailboxLock) { admitting = false }
                }
                }
            }
        } catch (publicationFailure: Throwable) {
            val fatal = RaspberryPiUdpConsistencyException(
                publicationFailure,
                IllegalStateException("UDP_RUNTIME_PUBLICATION_FAILED_AFTER_PERSISTENCE"),
            )
            consistencyFailure = fatal
            withContext(NonCancellable) {
                try { prepared?.closePrepared() } catch (cleanup: Throwable) { fatal.addSuppressed(cleanup) }
                try { receiver?.stop() } catch (cleanup: Throwable) { fatal.addSuppressed(cleanup) }
                receiver = null
                synchronized(mailboxLock) { admitting = false }
                activity.reset(false)
                mutableState.value = ImageInputState.ERROR
                mutableDiagnostics.value = mutableDiagnostics.value.copy(lastError = "NETWORK_CONFIGURATION_INCONSISTENT")
            }
            throw fatal
        }
        true
    }

    private fun createReceiver(
        bindIpv4: String,
        targetProfile: RaspberryPiUdpInputProfile,
        targetPlan: EffectiveUdpNetworkPlan?,
    ): UdpReceiver {
        // Legacy receiver configuration is an internal adapter only; the public profile stays single-stream.
        val receiveProfile = UdpInputProfile(UdpCameraId.A, targetProfile.enabled, null, targetProfile.listenPort,
            TransportMode.STRUCTURED_CHUNKED, PayloadProfile.Gray8(1, 1), ApplicationFramingProfile(), targetProfile.transportLimits)
        return UdpReceiver(receiveProfile, observer = object : UdpReceiverObserver {
            override fun onReceived(datagram: ReceivedDatagram) = udp.datagramReceived(datagram.size, datagram.metadata())
            override fun onSourceFiltered(datagram: ReceivedDatagram) {
                udp.sourceFiltered(); activity.rejected(datagram.receivedAtMonotonicNanos)
                inspectRejected(datagram, RawParseStatus.SOURCE_IP_MISMATCH)
            }
            override fun onDropped(reason: UdpReceiverDropReason, datagram: ReceivedDatagram) {
                activity.accepted(datagram.receivedAtMonotonicNanos)
                udp.datagramTooLargeOrTruncated()
                inspectRejected(datagram, RawParseStatus.DATAGRAM_TOO_LARGE_OR_TRUNCATED)
            }
            override fun onFailure(error: Throwable) {
                synchronized(receiverStatusLock) {
                    activity.reset(false)
                    mutableDiagnostics.value = mutableDiagnostics.value.copy(lastError = "UDP_RECEIVE_FAILED")
                    mutableState.value = ImageInputState.ERROR
                }
            }
        }, channelFactory = channelFactory, monotonicClock = monotonicClock, epochClock = epochClock,
            dispatcher = dispatcher, envelopeBytes = 4, bindIpv4 = bindIpv4,
            maximumReceiveCapacity = UdpReceiveCapacityCalculator.calculate(38L + 60_000),
            sourceIpv4s = compileSourceFilter(targetProfile, targetPlan)) { datagram ->
            activity.accepted(datagram.receivedAtMonotonicNanos)
            processingLock.withLock {
                // A datagram may have passed the receiver guard immediately before a limit swap.
                if (datagram.size > receiveCapacity(profile).acceptedLimit) {
                    udp.datagramTooLargeOrTruncated()
                    inspectRejected(datagram, RawParseStatus.DATAGRAM_TOO_LARGE_OR_TRUNCATED)
                    return@withLock
                }
                when (val result = checkNotNull(processor).process(datagram.bytes, datagram.metadata())) {
                    is ProcessorResult.Accepted -> { udp.frameCompleted(result.completedAtMonotonicNanos); offerInput(result.input) }
                    is ProcessorResult.Rejected -> udp.rejected(result.stage, result.reason)
                    ProcessorResult.Pending -> Unit
                }
            }
        }
    }

    private fun compileSourceFilter(
        targetProfile: RaspberryPiUdpInputProfile,
        targetPlan: EffectiveUdpNetworkPlan?,
    ): Set<java.net.Inet4Address>? {
        val configured = targetPlan?.resolvedSourceIpv4s ?: return targetProfile.raspberryPiIpv4?.let {
            setOf(UdpReceiver.requireIpv4LiteralForConfiguration(it))
        }
        return configured.mapTo(linkedSetOf()) { UdpReceiver.requireIpv4LiteralForConfiguration(it) }
    }

    private fun inspectRejected(datagram: ReceivedDatagram, status: RawParseStatus) {
        if (packetInspector.captureEnabled()) datagram.bytes.withBytes { bytes, length ->
            packetInspector.captureRaw(bytes, length, datagram.metadata(), profile, status)
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
