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

class UdpCameraInputSource(
    profiles: UdpInputProfileSet,
) : CameraInputSource {
    private val lifecycle = Mutex()
    private val profileSet = profiles.also {
        require(UdpInputProfileValidator().validate(it).isValid) { "UDP input profiles are invalid" }
    }
    private val udpDiagnostics = listOf(profileSet.cameraA, profileSet.cameraB).associate { it.cameraId to UdpDiagnostics(it.transportMode) }
    private val processors = listOf(profileSet.cameraA, profileSet.cameraB)
        .filter { it.enabled }
        .associate {
            val diagnostics = udpDiagnostics.getValue(it.cameraId)
            val observer = object : com.filamentvision.backend.input.reassembly.StructuredReassemblyObserver {
                override fun onFrameStarted() = diagnostics.frameStarted()
                override fun onDuplicateChunk() = diagnostics.chunkDuplicate()
                override fun onOutOfOrderChunk() = diagnostics.chunkOutOfOrder()
                override fun onLateChunk() = diagnostics.lateChunk()
                override fun onTimeout(missingChunks: Int) = diagnostics.reassemblyTimeout(missingChunks.toLong())
                override fun onEvicted(missingChunks: Int) = diagnostics.reassemblyEviction(missingChunks.toLong())
            }
            it.cameraId to UdpCameraProcessor(CompiledUdpInputProfile.compile(it, observer), diagnostics)
        }
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
                val processor = processors.getValue(profile.cameraId)
                val cameraDiagnostics = udpDiagnostics.getValue(profile.cameraId)
                val receiver = UdpReceiver(profile, object : UdpReceiverObserver {
                    override fun onReceived(size: Int, receivedAtMonotonicNanos: Long) = cameraDiagnostics.datagramReceived(size, receivedAtMonotonicNanos)
                    override fun onSourceFiltered(datagram: ReceivedDatagram) = cameraDiagnostics.sourceFiltered()
                    override fun onDropped(reason: UdpReceiverDropReason) = cameraDiagnostics.datagramTooLargeOrTruncated()
                }) { datagram ->
                    val result = datagram.bytes.withBytes { bytes, size ->
                        processor.process(
                            OwnedUdpPacket(bytes, size, datagram.source, datagram.receivedAtMonotonicNanos, datagram.receivedAtEpochMillis),
                        )
                    }
                    if (result is UdpCameraProcessResult.Accepted) offerInput(result.input)
                }
                receiver.start()
                receivers[profile.cameraId] = receiver
                started += receiver
            }
            activeProducerCount = 1
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
}
