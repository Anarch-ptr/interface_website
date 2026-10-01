package com.filamentvision.backend.simulator

import com.filamentvision.core.frame.CameraFrame
import com.filamentvision.core.frame.CameraId
import com.filamentvision.core.input.ImageInputDiagnostics
import com.filamentvision.core.input.ImageInputSource
import com.filamentvision.core.input.ImageInputState
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Explicitly owned producer. [frames] is a work-sharing ingress, not a broadcast:
 * each collected frame transfers ownership to that collector, which must close it.
 * At most one pending frame per camera is retained; observers do not own lifecycle.
 */
@OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
class SimulatorImageInputSource(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val cameraAId: CameraId = CameraId("A"),
    private val cameraBId: CameraId = CameraId("B"),
    private val framePeriodMillis: Long = 125,
    private val epochMillis: () -> Long = System::currentTimeMillis,
    private val monotonicNanos: () -> Long = { System.nanoTime().coerceAtLeast(0) },
) : ImageInputSource {
    init {
        require(framePeriodMillis > 0) { "Frame period must be positive" }
        require(cameraAId != cameraBId) { "Camera identities must differ" }
    }

    private val lifecycle = Mutex()
    private var producer: Job? = null
    private val factory = SimulatorFrameFactory()
    private val mutableState = MutableStateFlow(ImageInputState.IDLE)
    override val state = mutableState.asStateFlow()
    private val mutableDiagnostics = MutableStateFlow(ImageInputDiagnostics())
    override val diagnostics = mutableDiagnostics.asStateFlow()
    private val mutableScenario = MutableStateFlow(SimulationScenario.NORMAL)
    val scenario = mutableScenario.asStateFlow()
    @Volatile override var activeProducerCount = 0
        private set
    @Volatile var totalProducerStarts = 0L
        private set
    @Volatile var maximumActiveProducerCount = 0
        private set
    private val mailboxLock = Any()
    private val pending = linkedMapOf<CameraId, CameraFrame>()
    private val ready = Channel<Unit>(Channel.CONFLATED)
    private val sequences = mutableMapOf(cameraAId to 0L, cameraBId to 0L)
    private var lastEpochMillis = -1L

    override val frames: Flow<CameraFrame> = flow {
        while (currentCoroutineContext().isActive) {
            val next = synchronized(mailboxLock) {
                pending.keys.firstOrNull()?.let { pending.remove(it) }
            }
            if (next == null) ready.receive() else emit(next)
        }
    }

    suspend fun setScenario(value: SimulationScenario) = lifecycle.withLock { mutableScenario.value = value }

    override suspend fun start() = lifecycle.withLock {
        if (producer?.isCompleted == false) return@withLock
        check(scope.isActive) { "Simulator scope is not active" }
        mutableState.value = ImageInputState.STARTING
        activeProducerCount = 1
        maximumActiveProducerCount = maxOf(maximumActiveProducerCount, activeProducerCount)
        totalProducerStarts++
        // ATOMIC ensures the finally block also runs if cancelled before first dispatch.
        producer = scope.launch(dispatcher, start = CoroutineStart.ATOMIC) {
            try {
                var currentScenario = scenario.value
                var scenarioTick = 0L
                while (isActive) {
                    if (currentScenario != scenario.value) {
                        currentScenario = scenario.value
                        scenarioTick = 0
                    }
                    if (currentScenario == SimulationScenario.CONNECTION_FAILURE) {
                        clearPending()
                        mutableDiagnostics.update { it.copy(lastError = "Simulated connection failure") }
                        mutableState.value = ImageInputState.ERROR
                        delay(framePeriodMillis)
                        continue
                    }
                    mutableDiagnostics.update { it.copy(lastError = null) }
                    mutableState.value = ImageInputState.RUNNING
                    val epoch = maxOf(epochMillis(), lastEpochMillis + 1)
                    lastEpochMillis = epoch
                    val received = monotonicNanos().coerceAtLeast(0)
                    for (camera in listOf(cameraAId, cameraBId)) {
                        if (currentScenario == SimulationScenario.CAMERA_A_ONLY && camera == cameraBId) continue
                        if (currentScenario == SimulationScenario.CAMERA_B_ONLY && camera == cameraAId) continue
                        if (currentScenario == SimulationScenario.TEMPORARY_DROPOUT && camera == cameraBId && scenarioTick in 1..3) continue
                        val sequence = sequences.getValue(camera)
                        sequences[camera] = sequence + 1
                        val captured = epoch + if (currentScenario == SimulationScenario.TIMESTAMP_MISMATCH && camera == cameraBId) 500 else 0
                        lastEpochMillis = maxOf(lastEpochMillis, captured)
                        offer(factory.create(camera, captured, received, sequence, currentScenario, scenarioTick, camera == cameraAId))
                    }
                    scenarioTick++
                    delay(framePeriodMillis)
                }
            } finally {
                clearPending()
                activeProducerCount = 0
                mutableState.value = ImageInputState.IDLE
            }
        }
    }

    override suspend fun stop() = lifecycle.withLock {
        withContext(NonCancellable) {
            mutableState.value = ImageInputState.STOPPING
            producer?.cancelAndJoin()
            producer = null
            clearPending()
            mutableState.value = ImageInputState.IDLE
        }
    }

    private fun offer(frame: CameraFrame) = synchronized(mailboxLock) {
        pending.put(frame.cameraId, frame)?.let {
            it.close()
            mutableDiagnostics.update { previous -> previous.copy(droppedFrames = previous.droppedFrames + 1) }
        }
        ready.trySend(Unit)
        Unit
    }

    private fun clearPending() = synchronized(mailboxLock) {
        val discarded = pending.size
        pending.values.forEach { it.close() }
        pending.clear()
        if (discarded > 0) mutableDiagnostics.update { it.copy(droppedFrames = it.droppedFrames + discarded) }
    }
}
