package com.filamentvision.core.runtime

import com.filamentvision.core.alarm.*
import com.filamentvision.core.config.VisionConfiguration
import com.filamentvision.core.frame.*
import com.filamentvision.core.fusion.*
import com.filamentvision.core.input.*
import com.filamentvision.core.model.*
import com.filamentvision.core.session.SessionStatisticsAccumulator
import com.filamentvision.core.vision.VisionProcessor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/** Owns source lifecycle independently of observers. All public lifecycle operations suspend;
 * close waits for cleanup even if its caller has been cancelled. Adapters must make stop await
 * source termination and must acknowledge writes only when the complete batch is durable.
 */
@OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
class MonitoringRuntime(
    scope: CoroutineScope,
    private val source: ImageInputSource,
    configurations: Map<CameraId, VisionConfiguration>,
    private val pairingStrategy: FramePairingStrategy,
    private val fusionConfiguration: FusionConfiguration,
    private val targetDiameter: Double,
    private val persistence: RuntimePersistence,
    private val eventSink: RuntimeEventSink,
    commandCacheCapacity: Int = 128,
    private val epochMillis: () -> Long = System::currentTimeMillis,
    private val sessionId: () -> String = { UUID.randomUUID().toString() },
    private val processor: VisionProcessor = VisionProcessor(),
    private val fusionEngine: FusionEngine = FusionEngine(),
    realtimeCapacity: Int = 300,
    private val writeBatchSize: Int = 8,
    private val flushIntervalMillis: Long = 1000,
) {
    init {
        require(targetDiameter > 0.0)
        require(writeBatchSize > 0)
        require(flushIntervalMillis > 0)
    }
    private val configurations = configurations.toMap()
    private val ownership = RuntimeOwnership(commandCacheCapacity)
    private val processing = Mutex()
    private val writing = Mutex()
    private val job = SupervisorJob(scope.coroutineContext[Job])
    private val runtimeScope = CoroutineScope(scope.coroutineContext + job)
    private val mutableState = MutableStateFlow(MonitoringState())
    val state = mutableState.asStateFlow()
    private val realtime = RealtimeMeasurementBuffer(realtimeCapacity)
    private var writes = MeasurementWriteBuffer(writeBatchSize)
    private var statistics = SessionStatisticsAccumulator()
    private var alarm = AlarmEvaluator(targetDiameter)
    private val pendingAlarms = mutableListOf<AlarmTransition>()
    private var abnormalCount = 0

    suspend fun start(command: StartMonitoringCommand): StartResult {
        val result = withContext(NonCancellable) {
            var candidate: RuntimeSession? = null
            var result: CompletableDeferred<StartResult>? = null
            while (result == null) {
                var kickoff: RuntimeOwnership.Reservation? = null
                result = ownership.transition {
                    ownership.cached<StartResult>("start", command.commandId)?.let { return@transition it }
                    val current = ownership.current
                    val answer = when {
                        ownership.closed || !job.isActive -> CompletableDeferred(StartResult(StartOutcome.CLOSED))
                        current?.finalizing == true -> CompletableDeferred(StartResult(
                            if (current.finished.isCompleted) StartOutcome.FAILED else StartOutcome.INTERRUPTED, current.session,
                        ))
                        current != null && !current.started.isCompleted -> current.started
                        current != null -> CompletableDeferred(StartResult(StartOutcome.ALREADY_RUNNING, current.session))
                        else -> {
                            val session = candidate ?: return@transition null
                            val reservation = RuntimeOwnership.Reservation(session.copy(generation = ++ownership.generation))
                            ownership.current = reservation
                            realtime.clear()
                            writes = MeasurementWriteBuffer(writeBatchSize)
                            statistics = SessionStatisticsAccumulator()
                            alarm = AlarmEvaluator(targetDiameter)
                            pendingAlarms.clear()
                            abnormalCount = 0
                            mutableState.value = MonitoringState(
                                lifecycle = MonitoringLifecycle.STARTING, activeSession = reservation.session,
                                activeProducerCount = 1, maximumActiveProducerCount = 1,
                            )
                            reservation.worker = runtimeScope.launch(start = CoroutineStart.ATOMIC) { produce(reservation) }
                            kickoff = reservation
                            reservation.started
                        }
                    }
                    ownership.remember("start", command.commandId, answer)
                    publishCacheSize()
                    answer
                }
                // No injected callback can run until the reservation transition has released its lock.
                kickoff?.launchGate?.complete(Unit)
                if (result == null) candidate = RuntimeSession(sessionId(), 0, epochMillis())
            }
            result
        }
        return result.await()
    }

    suspend fun stop(command: StopMonitoringCommand): StopResult = withContext(NonCancellable) {
        var finalizer: Job? = null
        val result = ownership.transition {
            ownership.cached<StopResult>("stop", command.commandId)?.let { return@transition it }
            val result = ownership.current?.let {
                beginFinalization(it, FinalizationReason.COMPLETED, null)
                finalizer = it.finalizer
                it.finished
            } ?: CompletableDeferred(StopResult())
            ownership.remember("stop", command.commandId, result)
            publishCacheSize()
            result
        }
        finalizer?.start()
        result.await()
    }

    suspend fun close() = withContext(NonCancellable) {
        var finalizer: Job? = null
        val pending = ownership.transition {
            ownership.closed = true
            ownership.current?.also {
                beginFinalization(it, FinalizationReason.INTERRUPTED, null)
                finalizer = it.finalizer
            }?.finished
        }
        finalizer?.start()
        val result = pending?.await()
        job.cancelAndJoin()
        check(result?.error == null) { "Runtime cleanup incomplete: ${result?.error}" }
    }

    suspend fun realtimeMeasurements(): List<OfficialMeasurement> = ownership.transition { realtime.snapshot() }

    private suspend fun produce(reservation: RuntimeOwnership.Reservation) {
        var failure: Throwable? = null
        try {
            withContext(NonCancellable) {
                reservation.launchGate.await()
                processing.withLock { pairingStrategy.reset() }
                persistence.createSession(reservation.session)
                reservation.created = true
            }
            currentCoroutineContext().ensureActive()
            source.start()
            ownership.transition {
                if (!reservation.finalizing) {
                    reservation.accepting = true
                    mutableState.value = state.value.copy(lifecycle = MonitoringLifecycle.RUNNING)
                    reservation.started.complete(StartResult(StartOutcome.STARTED, reservation.session))
                }
            }
            coroutineScope {
                launch {
                    source.state.first { it == ImageInputState.ERROR }
                    error(source.diagnostics.value.lastError ?: "Image input failed")
                }
                launch {
                    while (isActive) {
                        delay(flushIntervalMillis)
                        flushMeasurements(reservation)
                    }
                }
                source.frames.collect { accept(reservation, it) }
                error("Image input completed")
            }
        } catch (error: Throwable) {
            failure = error
        } finally {
            withContext(NonCancellable) {
                val finalizer = ownership.transition {
                    beginFinalization(reservation, FinalizationReason.INTERRUPTED, failure)
                    reservation.finalizer
                }
                finalizer?.start()
            }
        }
    }

    private suspend fun accept(reservation: RuntimeOwnership.Reservation, frame: CameraFrame) {
        try {
            processing.withLock {
                val accepting = ownership.transition { ownership.current === reservation && reservation.accepting }
                if (!accepting) return@withLock
                val configuration = requireNotNull(configurations[frame.cameraId]) { "No configuration for ${frame.cameraId}" }
                val camera = processor.process(frame, configuration).measurement ?: return@withLock
                for (pair in pairingStrategy.offer(camera)) {
                    val measurement = fusionEngine.fuseDetailed(pair.cameraA, pair.cameraB, fusionConfiguration)?.measurement ?: continue
                    val flush = ownership.transition {
                        // Revalidate after external processing; stop may have closed acceptance meanwhile.
                        if (ownership.current !== reservation || !reservation.accepting) return@transition false
                        writes.add(measurement)
                        realtime.add(measurement)
                        statistics.add(measurement)
                        val evaluated = alarm.evaluate(measurement)
                        evaluated.transition?.let {
                            pendingAlarms += it
                            if (it.to.level != AlarmLevel.NORMAL) abnormalCount++
                        }
                        mutableState.value = state.value.copy(
                            latestMeasurement = measurement, latestAlarm = evaluated.state,
                            statistics = statistics.snapshot(abnormalCount),
                        )
                        writes.ready
                    }
                    if (flush) flushMeasurements(reservation, thresholdOnly = true)
                }
            }
        } finally { frame.close() }
    }

    /** Called only inside the transition lock. Never joins the worker on its own stack. */
    private fun beginFinalization(reservation: RuntimeOwnership.Reservation, reason: FinalizationReason, failure: Throwable?) {
        if (ownership.current !== reservation || reservation.finalizing) return
        reservation.finalizing = true
        reservation.accepting = false
        mutableState.value = state.value.copy(lifecycle = MonitoringLifecycle.STOPPING)
        // Lazy construction cannot execute external cleanup under the ownership lock, even Unconfined.
        reservation.finalizer = runtimeScope.launch(NonCancellable, start = CoroutineStart.LAZY) {
            finalize(reservation, reason, failure)
        }
    }

    private suspend fun finalize(reservation: RuntimeOwnership.Reservation, reason: FinalizationReason, failure: Throwable?) {
        var error = failure?.takeUnless { it is CancellationException }?.let { it.message ?: it.toString() }
        reservation.worker.cancel()
        suspend fun stopSource(): Boolean = try {
            source.stop(); true
        } catch (stopError: Throwable) {
            error = stopError.message ?: "Source stop failed"; false
        }
        var stopped = stopSource()
        reservation.worker.join()
        // A cancellation-insensitive start can activate after the first stop. Reconcile only
        // once the worker can no longer start it, then retain ownership if termination fails.
        if (source.activeProducerCount != 0) stopped = stopSource()
        val producerCount = source.activeProducerCount
        stopped = stopped && producerCount == 0
        if (!stopped && error == null) error = "Source still active after stop"
        var finalized = false
        try {
            if (reservation.created) {
                flushMeasurements(reservation, drain = true)
                if (pendingAlarms.isNotEmpty()) {
                    persistence.writeAlarms(reservation.session, pendingAlarms.toList())
                    pendingAlarms.clear()
                }
                if (stopped) {
                    val finalization = SessionFinalization(reservation.session, epochMillis(), reason, statistics.snapshot(abnormalCount), error)
                    persistence.finalizeSession(finalization)
                    eventSink.sessionFinalized(finalization)
                }
            }
            finalized = stopped
        } catch (writeError: Throwable) {
            error = writeError.message ?: "Finalization failed"
        }
        ownership.transition {
            // Fail closed on adapter cleanup errors: retain pending data and the reservation.
            // Reusing this runtime would otherwise overwrite unpersisted accepted measurements.
            if (finalized) ownership.current = null
            mutableState.value = state.value.copy(
                lifecycle = if (error == null) MonitoringLifecycle.IDLE else MonitoringLifecycle.ERROR,
                activeSession = if (finalized) null else reservation.session,
                activeProducerCount = producerCount, error = error,
            )
            reservation.started.complete(StartResult(if (error == null) StartOutcome.INTERRUPTED else StartOutcome.FAILED, reservation.session))
            reservation.finished.complete(StopResult(reservation.session, error))
        }
    }

    private suspend fun flushMeasurements(
        reservation: RuntimeOwnership.Reservation,
        thresholdOnly: Boolean = false,
        drain: Boolean = false,
    ) = withContext(NonCancellable) {
        // The writer lock serializes claims, not runtime reads or measurement acceptance.
        writing.withLock {
            do {
                val batch = ownership.transition {
                    if (ownership.current !== reservation || (thresholdOnly && !writes.ready)) null else writes.claim()
                } ?: break
                persistence.writeMeasurements(reservation.session, batch.measurements)
                ownership.transition { writes.acknowledge(batch) }
            } while (drain)
        }
    }

    private fun publishCacheSize() { mutableState.value = state.value.copy(cachedCommandCount = ownership.cachedCount) }
}
