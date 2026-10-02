package com.filamentvision.backend

import com.filamentvision.backend.application.*
import com.filamentvision.core.config.*
import com.filamentvision.core.repository.*
import com.filamentvision.core.runtime.*
import com.filamentvision.backend.input.profile.UdpInputProfileSet
import com.filamentvision.backend.input.runtime.*
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.UUID

internal class ComposedBackendApplication(private val c: BackendComposition) : BackendApplication {
    private val results = linkedMapOf<Triple<String, String, String>, Any>()
    private val drafts = linkedMapOf<String, DraftView>()
    val cachedCommandCount get() = results.size
    private val service = ConfigurationService(c.repository, object : ConfigurationRuntime {
        // Every service invocation occurs while composition.commands is held, including start/stop.
        override fun <T> withConfigurationGuard(action: (ConfigurationRuntimeState) -> T): T = action(object : ConfigurationRuntimeState {
            override val monitoringActive get() = c.runtime.state.value.activeSession != null
            // This composition has no per-sample live-revision barrier. No configuration
            // is advertised as live-compatible while an authoritative reservation exists.
            override val configuration get() = c.applied.takeUnless { monitoringActive }
            override fun publishConfiguration(configuration: MonitoringConfiguration) { c.applied = configuration }
        })
    })
    private val udpService = UdpInputConfigurationService(c.udpProfileRepository, c.udpInputRouter)
    private fun device(id: String) { if (id != c.deviceId) missing() }
    private fun missing(): Nothing = throw ApplicationFailure(ApplicationFailureCode.NOT_FOUND)
    private fun conflict(): Nothing = throw ApplicationFailure(ApplicationFailureCode.CONFLICT)
    private suspend fun <T : Any> dedup(kind: String, id: String, action: suspend () -> T): T = withContext(NonCancellable) { c.commands.withLock {
        check(!c.closed)
        val key = Triple(c.deviceId, kind, id)
        @Suppress("UNCHECKED_CAST")
        (results[key] as T?)?.let { return@withLock it }
        action().also { results[key] = it; while (results.size > 128) results.remove(results.keys.first()) }
    } }
    override suspend fun state(deviceId: String): DeviceView {
        device(deviceId)
        val value = c.checkpoint
        return DeviceView(deviceId, if (value.monitoring.lifecycle == MonitoringLifecycle.ERROR) ConnectionState.ERROR else ConnectionState.CONNECTED,
            value.monitoring, value.cameras, value.revisions, value.sequence)
    }
    override suspend fun start(deviceId: String, commandId: String, targetDiameter: Double?, appliedRevision: Long?): StartResult {
        device(deviceId)
        return dedup("start", commandId) {
            if (targetDiameter != null && targetDiameter != c.applied.targetDiameter || appliedRevision != null && appliedRevision != c.revisions.appliedRevision) conflict()
            c.runtime.start(StartMonitoringCommand(commandId))
        }
    }
    override suspend fun stop(deviceId: String, commandId: String): StopResult = c.commands.withLock {
        device(deviceId); c.runtime.stop(StopMonitoringCommand(commandId))
    }
    override suspend fun latest(deviceId: String): MeasurementRecord? {
        device(deviceId)
        val provenance = c.sessionProvenance
        val s = c.runtime.state.value
        val m = s.latestMeasurement ?: return null
        val session = s.activeSession?.id ?: return c.latestPersisted
        if (session != provenance?.sessionId) return c.latestPersisted
        return MeasurementRecord(c.recordId(session, "m", s.statistics.sampleCount - 1), session, m, provenance.appliedRevision)
    }
    override suspend fun sessions(query: PageQuery): SessionPage = c.io {
        val rows = c.repository.sessions(query.after, query.limit + 1, query.from, query.through)
        val page = rows.take(query.limit)
        SessionPage(page, if (rows.size > query.limit) page.last().session.let { HistoryCursor(it.startedAtEpochMillis, it.id) } else null)
    }
    override suspend fun session(sessionId: String): SessionRecord = c.io { c.repository.session(sessionId) ?: missing() }
    override suspend fun history(sessionId: String, query: PageQuery): MeasurementPage = c.io {
        c.repository.session(sessionId) ?: missing(); c.repository.history(sessionId, query.after, query.limit, query.from, query.through)
    }
    override suspend fun alarms(sessionId: String): List<AlarmEventRecord> = c.io { c.repository.session(sessionId) ?: missing(); c.repository.alarms(sessionId) }
    override suspend fun configuration(deviceId: String): ConfigurationView { device(deviceId); return c.io { view() } }
    private fun view(outcome: ConfigurationApplyOutcome? = null): ConfigurationView {
        val r = c.repository.revisions(); return ConfigurationView(r, r.savedRevision?.let(c.repository::configuration), outcome)
    }
    private suspend fun configurationChanged(result: ConfigurationApplyResult?): ConfigurationView {
        if (result?.outcome == ConfigurationApplyOutcome.APPLIED && c.runtime.state.value.activeSession == null)
            check(c.runtime.configureWhenStopped(c.applied, c.a, c.b))
        val resultView = c.io { view(result?.outcome) }
        c.publishConfiguration(resultView.revisions)
        return resultView
    }
    override suspend fun save(deviceId: String, commandId: String, configuration: MonitoringConfiguration): ConfigurationView {
        device(deviceId); return dedup("save", commandId) { c.io { service.save(configuration) }; configurationChanged(null) }
    }
    override suspend fun apply(deviceId: String, commandId: String, revision: Long, expectedSavedRevision: Long): ConfigurationView {
        device(deviceId); return dedup("apply", commandId) { configurationChanged(c.io { service.apply(revision, expectedSavedRevision) }) }
    }
    override suspend fun saveAndApply(deviceId: String, commandId: String, configuration: MonitoringConfiguration): ConfigurationView {
        device(deviceId); return dedup("saveAndApply", commandId) { configurationChanged(c.io { service.saveAndApply(configuration) }) }
    }
    private suspend fun udpView(outcome: UdpApplyOutcome? = null): UdpInputConfigurationView = c.io {
        val saved = c.udpProfileRepository.latest()
        UdpInputConfigurationView(saved.revision, c.udpProfileRepository.appliedRevision(), saved.profiles, outcome)
    }
    override suspend fun udpInputConfiguration(deviceId: String): UdpInputConfigurationView {
        device(deviceId); return udpView()
    }
    override suspend fun saveUdpInput(deviceId: String, commandId: String, profiles: UdpInputProfileSet): UdpInputConfigurationView {
        device(deviceId); return dedup("saveUdpInput", commandId) { c.io { udpService.save(profiles) }; udpView() }
    }
    override suspend fun applyUdpInput(deviceId: String, commandId: String, revision: Long,
        expectedSavedRevision: Long): UdpInputConfigurationView {
        device(deviceId); return dedup("applyUdpInput", commandId) {
            when (val result = c.io { udpService.apply(revision, expectedSavedRevision) }) {
                is UdpApplyResult -> when (result.outcome) {
                    UdpApplyOutcome.APPLIED -> udpView(result.outcome)
                    UdpApplyOutcome.CONFLICT -> throw ApplicationFailure(ApplicationFailureCode.CONFLICT)
                    UdpApplyOutcome.NOT_FOUND -> missing()
                    UdpApplyOutcome.INVALID -> throw ApplicationFailure(ApplicationFailureCode.INVALID)
                }
            }
        }
    }
    override suspend fun udpDiagnostics(deviceId: String, cameraId: String) = c.udpDiagnostics[when (cameraId) {
        "A" -> com.filamentvision.backend.input.profile.UdpCameraId.A
        "B" -> com.filamentvision.backend.input.profile.UdpCameraId.B
        else -> { device(deviceId); missing() }
    }]!!.snapshot().also { device(deviceId) }
    override suspend fun createDraft(deviceId: String, commandId: String, cameraId: String, configuration: VisionConfiguration): DraftView {
        device(deviceId); if (cameraId !in setOf("A", "B")) missing()
        return dedup("createDraft", commandId) {
            MonitoringConfiguration(cameraA = configuration, cameraB = configuration).validate()
            if (drafts.size >= 2) conflict()
            DraftView(UUID.randomUUID().toString(), cameraId, 1, configuration).also { drafts[it.id] = it }
        }
    }
    override suspend fun updateDraft(deviceId: String, draftId: String, commandId: String, expectedRevision: Long, configuration: VisionConfiguration): DraftView {
        device(deviceId); return dedup("updateDraft:$draftId", commandId) {
            val old = drafts[draftId] ?: missing(); if (old.revision != expectedRevision) conflict()
            MonitoringConfiguration(cameraA = configuration, cameraB = configuration).validate()
            old.copy(revision = old.revision + 1, configuration = configuration).also { drafts[draftId] = it }
        }
    }
    override suspend fun deleteDraft(deviceId: String, draftId: String, commandId: String) {
        device(deviceId); dedup("deleteDraft:$draftId", commandId) { drafts.remove(draftId) ?: missing(); Unit }
    }
    override suspend fun cameras(deviceId: String): List<CameraView> {
        device(deviceId)
        return c.cameraViews()
    }
    override suspend fun diagnostics(deviceId: String): DiagnosticsView {
        device(deviceId); val cache = c.snapshotCache.state()
        return DiagnosticsView(c.metrics.snapshot(), c.runtime.queuedCounts().first, cache.entries, cache.totalBytes, "phase2")
    }
    override suspend fun errors(sessionId: String?): List<ErrorRecord> = c.io {
        if (sessionId == null) c.repository.allErrors() else { c.repository.session(sessionId) ?: missing(); c.repository.errors(sessionId) }
    }
    override suspend fun snapshots(errorId: String): List<SnapshotRecord> = c.io {
        val error = c.repository.error(errorId) ?: missing(); error.sessionId?.let(c.repository::snapshots) ?: emptyList()
    }
    override suspend fun snapshot(snapshotId: String): SnapshotContent = c.io {
        val record = c.repository.snapshot(snapshotId) ?: missing()
        SnapshotContent(c.store.read(record.objectKey), "image/png", "snapshot.png")
    }
}
