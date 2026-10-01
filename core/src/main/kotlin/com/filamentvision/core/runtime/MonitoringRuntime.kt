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
        val result = ownership.transition {
            ownership.cached<StartResult>("start", command.commandId)?.let { return@transition it }
            val current = ownership.current
            val result = when {
                ownership.closed || !job.isActive -> CompletableDeferred(StartResult(StartOutcome.CLOSED))
                current?.finalizing == true -> CompletableDeferred(StartResult(
                    if (current.finished.isCompleted) StartOutcome.FAILED else StartOutcome.INTERRUPTED, current.session,
                ))
                current != null && !current.started.isCompleted -> current.started
                current != null -> CompletableDeferred(StartResult(StartOutcome.ALREADY_RUNNING, current.session))
                else -> {
                    val reservation = RuntimeOwnership.Reservation(RuntimeSession(sessionId(), ++ownership.generation, epochMillis()))
                    ownership.current = reservation
                    pairingStrategy.reset()
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
                    reservation.started
                }
            }
            ownership.remember("start", command.commandId, result)
            publishCacheSize()
            result
        }
        return result.await()
    }

    suspend fun stop(command: StopMonitoringCommand): StopResult = withContext(NonCancellable) {
        val result = ownership.transition {
            ownership.cached<StopResult>("stop", command.commandId)?.let { return@transition it }
            val result = ownership.current?.let {
                beginFinalization(it, FinalizationReason.COMPLETED, null)
                it.finished
            } ?: CompletableDeferred(StopResult())
            ownership.remember("stop", command.commandId, result)
            publishCacheSize()
            result
        }
        result.await()
    }

    suspend fun close() = withContext(NonCancellable) {
        val pending = ownership.transition {
            ownership.closed = true
            ownership.current?.also { beginFinalization(it, FinalizationReason.INTERRUPTED, null) }?.finished
        }
        val result = pending?.await()
        job.cancelAndJoin()
        check(result?.error == null) { "Runtime cleanup incomplete: ${result?.error}" }
    }

    suspend fun realtimeMeasurements(): List<OfficialMeasurement> = ownership.transition { realtime.snapshot() }

    private suspend fun produce(reservation: RuntimeOwnership.Reservation) {
        var failure: Throwable? = null
        try {
            withContext(NonCancellable) {
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
                        ownership.transition {
                            if (reservation.accepting) withContext(NonCancellable) { flushMeasurements(reservation) }
                        }
                    }
                }
                source.frames.collect { accept(reservation, it) }
                error("Image input completed")
            }
        } catch (error: Throwable) {
            failure = error
        } finally {
            withContext(NonCancellable) {
                ownership.transition { beginFinalization(reservation, FinalizationReason.INTERRUPTED, failure) }
            }
        }
    }

    private suspend fun accept(reservation: RuntimeOwnership.Reservation, frame: CameraFrame) {
        try {
            ownership.transition {
                if (ownership.current !== reservation || !reservation.accepting) return@transition
                val configuration = requireNotNull(configurations[frame.cameraId]) { "No configuration for ${frame.cameraId}" }
                val camera = processor.process(frame, configuration).measurement ?: return@transition
                for (pair in pairingStrategy.offer(camera)) {
                    val measurement = fusionEngine.fuseDetailed(pair.cameraA, pair.cameraB, fusionConfiguration)?.measurement ?: continue
                    // Acceptance is atomic with queueing: cancellation cannot split bookkeeping from persistence.
                    withContext(NonCancellable) {
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
                        if (writes.ready) flushMeasurements(reservation)
                    }
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
        reservation.worker.cancel()
        // Independent non-cancellable finalizer also survives cancellation of the supplied parent scope.
        runtimeScope.launch(NonCancellable) { finalize(reservation, reason, failure) }
    }

    private suspend fun finalize(reservation: RuntimeOwnership.Reservation, reason: FinalizationReason, failure: Throwable?) {
        var error = failure?.takeUnless { it is CancellationException }?.let { it.message ?: it.toString() }
        var stopped = false
        try {
            source.stop()
            stopped = source.activeProducerCount == 0
            if (!stopped) error = "Source still active after stop"
        } catch (stopError: Throwable) {
            error = stopError.message ?: "Source stop failed"
        }
        reservation.worker.join()
        var finalized = false
        try {
            if (reservation.created) {
                flushMeasurements(reservation)
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
                activeProducerCount = source.activeProducerCount, error = error,
            )
            reservation.started.complete(StartResult(if (error == null) StartOutcome.INTERRUPTED else StartOutcome.FAILED, reservation.session))
            reservation.finished.complete(StopResult(reservation.session, error))
        }
    }

    private suspend fun flushMeasurements(reservation: RuntimeOwnership.Reservation) =
        writes.flush { persistence.writeMeasurements(reservation.session, it) }

    private fun publishCacheSize() { mutableState.value = state.value.copy(cachedCommandCount = ownership.cachedCount) }
}
