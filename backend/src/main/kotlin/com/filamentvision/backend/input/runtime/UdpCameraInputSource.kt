package com.filamentvision.backend.input.runtime

import com.filamentvision.backend.input.profile.*
import com.filamentvision.backend.input.transport.*
import com.filamentvision.core.input.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.filamentvision.backend.input.diagnostics.*
import com.filamentvision.backend.input.inspector.*

class UdpCameraInputSource(
    profiles: UdpInputProfileSet,
    val packetInspector: PacketInspector = PacketInspector(),
) : CameraInputSource {
    private val lifecycle = Mutex()
    private var profileSet = profiles.also {
        require(UdpInputProfileValidator().validate(it).isValid) { "UDP input profiles are invalid" }
    }
    private val udpDiagnostics = listOf(profileSet.cameraA, profileSet.cameraB).associate { it.cameraId to UdpDiagnostics(it.transportMode) }.toMutableMap()
    private val processors = listOf(profileSet.cameraA, profileSet.cameraB)
        .associate { it.cameraId to compileProcessor(it, udpDiagnostics.getValue(it.cameraId)) }.toMutableMap()
    private val receivers = mutableMapOf<UdpCameraId, UdpReceiver>()
    private val mailboxLock = Any()
    private val pending = linkedMapOf<UdpCameraId, CameraInput>()
    private val ready = Channel<Unit>(Channel.CONFLATED)
    private val drops = mutableMapOf(UdpCameraId.A to 0L, UdpCameraId.B to 0L)
    private val mutableState = MutableStateFlow(ImageInputState.IDLE)
    private val mutableDiagnostics = MutableStateFlow(ImageInputDiagnostics())

    override val state = mutableState.asStateFlow()
    override val diagnostics = mutableDiagnostics.asStateFlow()
    override var activeProducerCount: Int = 0
        private set
    var totalProducerStarts: Long = 0
        private set
    var maximumActiveProducerCount: Int = 0
        private set

    override val inputs = flow {
        while (true) {
            ready.receive()
            val input = synchronized(mailboxLock) {
                val first = pending.entries.firstOrNull() ?: return@synchronized null
                pending.remove(first.key)
            } ?: continue
            try {
                emit(input)
            } catch (error: CancellationException) {
                close(input)
                throw error
            }
        }
    }

    val pendingInputCount: Int get() = synchronized(mailboxLock) { pending.size }
    fun preAdmissionDroppedInputs(cameraId: UdpCameraId): Long = synchronized(mailboxLock) { drops.getValue(cameraId) }
    fun udpDiagnostics(cameraId: UdpCameraId): UdpDiagnosticsSnapshot = udpDiagnostics.getValue(cameraId).snapshot()

    override suspend fun start() = lifecycle.withLock {
        if (activeProducerCount == 1) return@withLock
        mutableState.value = ImageInputState.STARTING
        val started = mutableListOf<UdpReceiver>()
        try {
            for (profile in listOf(profileSet.cameraA, profileSet.cameraB).filter { it.enabled }) {
                val receiver = createReceiver(profile)
                receiver.start()
                receivers[profile.cameraId] = receiver
                started += receiver
            }
            activeProducerCount = 1
            maximumActiveProducerCount = maxOf(maximumActiveProducerCount, activeProducerCount)
            totalProducerStarts++
            mutableState.value = ImageInputState.RUNNING
        } catch (error: Throwable) {
            started.forEach { it.stop() }
            receivers.clear()
            activeProducerCount = 0
            mutableDiagnostics.value = mutableDiagnostics.value.copy(lastError = error.message)
            mutableState.value = ImageInputState.ERROR
            throw error
        }
    }

    suspend fun applyProfile(cameraId: UdpCameraId, next: UdpInputProfile, receiverRestart: Boolean) = lifecycle.withLock {
        val previous = if (cameraId == UdpCameraId.A) profileSet.cameraA else profileSet.cameraB
        if (previous == next) return@withLock
        val oldProcessor = processors.getValue(cameraId)
        val oldDiagnostics = udpDiagnostics.getValue(cameraId)
        val replacementDiagnostics = if (previous.transportMode == next.transportMode) oldDiagnostics else UdpDiagnostics(next.transportMode)
        val replacementProcessor = compileProcessor(next, replacementDiagnostics)
        val wasRunning = activeProducerCount == 1
        if (receiverRestart && wasRunning) receivers.remove(cameraId)?.stop()
        processors[cameraId] = replacementProcessor
        udpDiagnostics[cameraId] = replacementDiagnostics
        profileSet = replace(profileSet, cameraId, next)
        try {
            if (receiverRestart && wasRunning && next.enabled) {
                createReceiver(next).also { it.start(); receivers[cameraId] = it }
            }
        } catch (error: Throwable) {
            processors[cameraId] = oldProcessor
            udpDiagnostics[cameraId] = oldDiagnostics
            profileSet = replace(profileSet, cameraId, previous)
            if (wasRunning && previous.enabled) createReceiver(previous).also { it.start(); receivers[cameraId] = it }
            throw error
        }
    }

    override suspend fun stop() = lifecycle.withLock {
        if (activeProducerCount == 0 && receivers.isEmpty()) {
            clearPending()
            mutableState.value = ImageInputState.IDLE
            return@withLock
        }
        mutableState.value = ImageInputState.STOPPING
        receivers.values.forEach { it.stop() }
        receivers.clear()
        clearPending()
        activeProducerCount = 0
        mutableState.value = ImageInputState.IDLE
    }

    internal fun offerInput(input: CameraInput) {
        val cameraId = when (input.cameraId.value) {
            UdpCameraId.A.name -> UdpCameraId.A
            UdpCameraId.B.name -> UdpCameraId.B
            else -> error("Unknown UDP camera ${input.cameraId}")
        }
        synchronized(mailboxLock) {
            pending.put(cameraId, input)?.let { replaced ->
                close(replaced)
                drops[cameraId] = drops.getValue(cameraId) + 1
                mutableDiagnostics.value = mutableDiagnostics.value.copy(
                    droppedFrames = mutableDiagnostics.value.droppedFrames + 1,
                )
            }
        }
        ready.trySend(Unit)
    }

    private fun clearPending() = synchronized(mailboxLock) {
        pending.values.forEach(::close)
        pending.clear()
    }

    private fun close(input: CameraInput) {
        if (input is CameraInput.Image) input.frame.close()
    }
    private fun inspectDatagram(cameraId: UdpCameraId, datagram: ReceivedDatagram, status: RawParseStatus) {
        try {
            datagram.bytes.withBytes { bytes, size -> packetInspector.captureRaw(cameraId, bytes, size, datagram.source,
                datagram.receivedAtMonotonicNanos, status) }
        } catch (_: Throwable) { }
    }
    private fun compileProcessor(profile: UdpInputProfile, diagnostics: UdpDiagnostics): UdpCameraProcessor {
        val observer = object : com.filamentvision.backend.input.reassembly.StructuredReassemblyObserver {
            override fun onFrameStarted() = diagnostics.frameStarted()
            override fun onDuplicateChunk() = diagnostics.chunkDuplicate()
            override fun onOutOfOrderChunk() = diagnostics.chunkOutOfOrder()
            override fun onLateChunk() = diagnostics.lateChunk()
            override fun onTimeout(missingChunks: Int) = diagnostics.reassemblyTimeout(missingChunks.toLong())
            override fun onEvicted(missingChunks: Int) = diagnostics.reassemblyEviction(missingChunks.toLong())
        }
        return UdpCameraProcessor(CompiledUdpInputProfile.compile(profile, observer), diagnostics, packetInspector)
    }
    private fun createReceiver(profile: UdpInputProfile): UdpReceiver {
        val processor = processors.getValue(profile.cameraId)
        val cameraDiagnostics = udpDiagnostics.getValue(profile.cameraId)
        return UdpReceiver(profile, object : UdpReceiverObserver {
            override fun onReceived(size: Int, receivedAtMonotonicNanos: Long) = cameraDiagnostics.datagramReceived(size, receivedAtMonotonicNanos)
            override fun onSourceFiltered(datagram: ReceivedDatagram) {
                cameraDiagnostics.sourceFiltered(); inspectDatagram(profile.cameraId, datagram, RawParseStatus.SOURCE_IP_MISMATCH)
            }
            override fun onDropped(reason: UdpReceiverDropReason, datagram: ReceivedDatagram) {
                cameraDiagnostics.datagramTooLargeOrTruncated(); inspectDatagram(profile.cameraId, datagram, RawParseStatus.DATAGRAM_TOO_LARGE_OR_TRUNCATED)
            }
        }) { datagram ->
            inspectDatagram(profile.cameraId, datagram, RawParseStatus.VALID)
            val result = datagram.bytes.withBytes { bytes, size -> processor.process(
                OwnedUdpPacket(bytes, size, datagram.source, datagram.receivedAtMonotonicNanos, datagram.receivedAtEpochMillis)) }
            if (result is UdpCameraProcessResult.Accepted) offerInput(result.input)
        }
    }
    private fun replace(set: UdpInputProfileSet, id: UdpCameraId, profile: UdpInputProfile) = when (id) {
        UdpCameraId.A -> set.copy(cameraA = profile); UdpCameraId.B -> set.copy(cameraB = profile)
    }
}
