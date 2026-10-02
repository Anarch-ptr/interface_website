package com.filamentvision.backend

import com.filamentvision.backend.application.*
import com.filamentvision.backend.persistence.sqlite.*
import com.filamentvision.backend.preview.PngPreviewEncoder
import com.filamentvision.backend.simulator.*
import com.filamentvision.backend.snapshot.FileSnapshotObjectStore
import com.filamentvision.core.alarm.AlarmTransition
import com.filamentvision.core.config.*
import com.filamentvision.core.event.*
import com.filamentvision.core.frame.*
import com.filamentvision.core.fusion.LatestCompatiblePairingStrategy
import com.filamentvision.core.input.ImageInputSource
import com.filamentvision.core.input.CameraInput
import com.filamentvision.core.metrics.*
import com.filamentvision.core.model.OfficialMeasurement
import com.filamentvision.core.preview.*
import com.filamentvision.core.repository.*
import com.filamentvision.core.runtime.*
import com.filamentvision.core.snapshot.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

internal data class StateCheckpoint(val monitoring: MonitoringState, val cameras: List<CameraView>,
    val revisions: ConfigurationRevisions, val sequence: Long)
internal data class SessionProvenance(val sessionId: String, val appliedRevision: Long?)

/** Owns one source/runtime and their lifetime independently of every client. */
class BackendComposition private constructor(
    private val database: SqliteDatabase,
    internal val repository: SqliteRepositories,
    internal val store: FileSnapshotObjectStore,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    internal val epochMillis: () -> Long,
    private val monotonicMillis: () -> Long,
    initial: ConfigurationRevision,
    initialRevisions: ConfigurationRevisions,
    dispatcher: CoroutineDispatcher,
) {
    val deviceId = "simulator-1"
    internal val a = CameraId("A")
    internal val b = CameraId("B")
    val previewKeys = setOf(a, b).map { PreviewKey(deviceId, it, PreviewStage.RAW) }.toSet()
    val events = SequencedEventHub(setOf(deviceId), 256)
    val previews = LatestPreviewHub(previewKeys, 48 * 8 * 4, 32)
    val snapshotCache = LatestFrameSnapshotCache(2, 1024 * 1024)
    private val snapshotPolicy = SnapshotPolicy(previewKeys, monotonicMillis)
    val metrics = MonitoringMetrics(setOf(a, b).map { MetricsCameraKey(deviceId, it) }.toSet(), monotonicMillis)
    @Volatile internal var applied = initial.configuration
    @Volatile internal var revisions = initialRevisions
    internal val commands = Mutex()
    internal var closed = false
    private val statePublicationLock = Any()
    private var finalizedAwaitingState: String? = null
    val source = SimulatorImageInputSource(scope, dispatcher, epochMillis = epochMillis,
        monotonicNanos = { monotonicMillis() * 1_000_000 })
    private val tap = object : ImageInputSource by source {
        override val frames = flow {
            source.frames.collect { frame ->
                val camera = MetricsCameraKey(deviceId, frame.cameraId)
                metrics.received(camera)
                val payload = ByteArray(frame.width * frame.height * 4)
                for (i in 0 until frame.pixels.count) for (component in 0..3)
                    payload[i * 4 + component] = (frame.pixels[i] ushr (24 - component * 8)).toByte()
                previews.publish(PreviewFrame(PreviewKey(deviceId, frame.cameraId, PreviewStage.RAW), frame.capturedAtEpochMillis,
                    frame.sequence, frame.width, frame.height, PreviewFormat.ARGB32, payload))
                val started = monotonicMillis()
                emit(frame)
                metrics.processed(camera)
                metrics.latency(MetricLatency.PROCESSING, (monotonicMillis() - started).coerceAtLeast(0).toDouble())
            }
        }
        override val inputs = frames.map(CameraInput::Image)
    }
    private var measurementOrdinal = 0L
    private var alarmOrdinal = 0L
    @Volatile internal var latestPersisted: MeasurementRecord? = null
    @Volatile internal var sessionProvenance: SessionProvenance? = null
        private set
    private fun publish(event: DomainEvent) = synchronized(statePublicationLock) { events.publish(event) }
    private val persistence = object : RuntimePersistence {
        override suspend fun createSession(session: RuntimeSession) {
            io { repository.createSession(session) }
            measurementOrdinal = 0; alarmOrdinal = 0; latestPersisted = null
            sessionProvenance = SessionProvenance(session.id, revisions.appliedRevision)
            publish(DomainEvent.SessionStarted(deviceId, session))
        }
        override suspend fun writeMeasurements(session: RuntimeSession, batch: List<OfficialMeasurement>) {
            val provenance = checkNotNull(sessionProvenance).also { check(it.sessionId == session.id) }
            val records = batch.mapIndexed { index, m -> MeasurementRecord(recordId(session.id, "m", measurementOrdinal + index), session.id, m, provenance.appliedRevision) }
            io { repository.appendMeasurements(records) }
            measurementOrdinal += records.size
            latestPersisted = records.last()
            metrics.record(MetricCounter.DATABASE_BATCH)
            metrics.record(MetricCounter.DATABASE_ROW, records.size.toLong())
            metrics.record(MetricCounter.FUSION, records.size.toLong())
            records.forEach { publish(DomainEvent.Measurement(deviceId, it)) }
        }
        override suspend fun writeAlarms(session: RuntimeSession, batch: List<AlarmTransition>) {
            val records = batch.mapIndexed { index, t -> AlarmEventRecord(recordId(session.id, "a", alarmOrdinal + index), session.id, t) }
            io { repository.appendAlarms(records) }
            alarmOrdinal += records.size
            records.forEach { publish(DomainEvent.Alarm(deviceId, it)) }
        }
        override suspend fun finalizeSession(finalization: SessionFinalization) {
            if (finalization.error != null) {
                val error = ErrorRecord(UUID.randomUUID().toString(), finalization.session.id, finalization.endedAtEpochMillis, "INPUT_FAILURE", "Monitoring interrupted")
                io {
                    repository.saveError(error)
                    for (key in previewKeys) previews.latest(key)?.takeIf { snapshotPolicy.tryAcquire(key) }?.let { frame ->
                        val encoded = PngPreviewEncoder().encode(frame)
                        val snapshot = EncodedSnapshot(key, frame.timestamp, frame.sourceSequence, encoded.mediaType, encoded.bytes)
                        snapshotCache.put(snapshot)
                        // Optional image storage cannot prevent durable monitoring finalization.
                        val stored = try { store.write(finalization.session.id, snapshot) } catch (_: java.io.IOException) { null }
                        if (stored != null) repository.saveSnapshot(stored.record)
                    }
                }
                publish(DomainEvent.Error(deviceId, error))
            }
            io { repository.finalizeSession(finalization) }
        }
    }
    val runtime = MonitoringRuntime(scope, tap, mapOf(a to applied.cameraA, b to applied.cameraB),
        LatestCompatiblePairingStrategy(a, b), applied.fusion, applied.targetDiameter, persistence,
        RuntimeEventSink { finalization -> synchronized(statePublicationLock) {
            // Runtime releases its reservation only after this callback. A delayed
            // STOPPING observer must not checkpoint past this finalization event.
            finalizedAwaitingState = finalization.session.id
            events.publish(DomainEvent.SessionFinalized(deviceId, finalization))
        } }, epochMillis = epochMillis,
        realtimeCapacity = applied.realtimeCapacity, writeBatchSize = applied.writeBatchSize, flushIntervalMillis = applied.flushIntervalMillis)
    @Volatile internal var checkpoint = StateCheckpoint(runtime.state.value, cameraViews(), revisions, 0)
        private set
    private fun publishState() = synchronized(statePublicationLock) {
        // Read inside the publication boundary, never replay a collector payload
        // that may have waited while another thread published a newer transition.
        val state = runtime.state.value
        if (finalizedAwaitingState != null && state.activeSession?.id == finalizedAwaitingState) return@synchronized
        val cameras = cameraViews()
        val sequence = events.publish(DomainEvent.State(deviceId, state, epochMillis())).sequence
        checkpoint = StateCheckpoint(state, cameras, revisions, sequence)
        finalizedAwaitingState = null
    }
    internal fun publishConfiguration(revisions: ConfigurationRevisions) = synchronized(statePublicationLock) {
        this.revisions = revisions
        events.publish(DomainEvent.Configuration(deviceId, revisions, epochMillis()))
        publishState()
    }
    internal fun cameraViews() = listOf("A", "B").map { id -> CameraView(id, when {
        source.scenario.value == SimulationScenario.CONNECTION_FAILURE -> CameraState.ERROR
        source.scenario.value == SimulationScenario.CAMERA_A_ONLY && id == "B" || source.scenario.value == SimulationScenario.CAMERA_B_ONLY && id == "A" -> CameraState.UNAVAILABLE
        else -> CameraState.AVAILABLE
    }, true, true) }
    private val stateObserver = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        runtime.state.collect { publishState() }
    }
    private val facade = ComposedBackendApplication(this)
    val application: BackendApplication get() = facade
    val cachedFacadeCommands: Int get() = facade.cachedCommandCount
    internal suspend fun <T> io(block: suspend () -> T): T = withContext(ioDispatcher) { block() }
    internal fun recordId(session: String, kind: String, index: Long) = "$session-$kind-${index.toString().padStart(12, '0')}"

    suspend fun close() = withContext(NonCancellable) {
        commands.withLock {
            if (closed) return@withLock
            closed = true
            try { runtime.close() }
            finally {
                stateObserver.cancelAndJoin()
                scope.coroutineContext[Job]!!.cancelAndJoin()
                io { database.close() }
            }
        }
    }

    companion object {
        fun defaultConfiguration(): MonitoringConfiguration {
            val vision = VisionConfiguration(CalibrationProfile(roiRight = 48, roiBottom = 8, manualThreshold = 40,
                edgeSensitivity = 30, minimumPixelWidth = 1, maximumPixelWidth = 46, mmPerPixel = .05))
            return MonitoringConfiguration(cameraA = vision, cameraB = vision)
        }
        suspend fun open(database: Path, snapshotRoot: Path, dispatcher: CoroutineDispatcher = Dispatchers.Default,
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO, epochMillis: () -> Long = System::currentTimeMillis,
            monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000 }): BackendComposition = withContext(ioDispatcher) {
            Files.createDirectories(database.toAbsolutePath().parent)
            Files.createDirectories(snapshotRoot)
            val db = SqliteDatabase(database)
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            try {
                val r = SqliteRepositories(db)
                val initial = r.revisions().appliedRevision?.let(r::configuration) ?: r.saveConfiguration(defaultConfiguration()).also { r.markApplied(it.revision, it.revision) }
                BackendComposition(db, r, FileSnapshotObjectStore(snapshotRoot), scope, ioDispatcher, epochMillis, monotonicMillis, initial, r.revisions(), dispatcher)
            } catch (error: Throwable) { scope.cancel(); db.close(); throw error }
        }
    }
}
