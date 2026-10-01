package com.filamentvision.backend.persistence.sqlite

import com.filamentvision.core.config.MonitoringConfiguration
import com.filamentvision.core.repository.*
import com.filamentvision.core.runtime.*

class InMemoryRepositoryContractTest : RepositoryConformance() {
    override fun openRepositories() = repositorySet(InMemoryRepositories())
}

/** Independent test oracle: staged copies model all-or-none writes. No SQL/codec implementation reuse. */
private class InMemoryRepositories : SessionRepository, MeasurementRepository, AlarmEventRepository, ConfigurationRepository, SnapshotRepository, ErrorRepository {
    private val sessions = linkedMapOf<String, SessionRecord>()
    private val measurements = linkedMapOf<String, MeasurementRecord>()
    private val alarms = linkedMapOf<String, AlarmEventRecord>()
    private val snapshots = linkedMapOf<String, SnapshotRecord>()
    private val errors = linkedMapOf<String, ErrorRecord>()
    private val configurations = linkedMapOf<Long, ConfigurationRevision>()
    private var applied: Long? = null
    private fun fail(type: RepositoryFailure): Nothing = throw RepositoryException(type, type.name)
    private fun requireSession(id: String) { if (id !in sessions) fail(RepositoryFailure.INVALID) }
    override fun createSession(session: RuntimeSession) {
        if (session.id in sessions) fail(RepositoryFailure.CONFLICT)
        sessions[session.id] = SessionRecord(session)
    }
    override fun finalizeSession(finalization: SessionFinalization) {
        if (finalization.session.id !in sessions) fail(RepositoryFailure.NOT_FOUND)
        sessions[finalization.session.id] = SessionRecord(finalization.session, finalization)
    }
    override fun session(id: String) = sessions[id]
    override fun appendMeasurements(batch: List<MeasurementRecord>) {
        val staged = measurements.toMutableMap()
        batch.forEach {
            requireSession(it.sessionId)
            if (it.id in staged) fail(RepositoryFailure.CONFLICT)
            if (!it.measurement.confidence.isFinite()) fail(RepositoryFailure.INVALID)
            staged[it.id] = it
        }
        measurements.clear(); measurements.putAll(staged)
    }
    override fun history(sessionId: String, after: HistoryCursor?, limit: Int, from: Long?, through: Long?): MeasurementPage {
        if (limit !in 1..1000) fail(RepositoryFailure.INVALID)
        val rows = measurements.values.filter { it.sessionId == sessionId &&
            (after == null || it.measurement.timestamp > after.timestamp || it.measurement.timestamp == after.timestamp && it.id > after.id) &&
            (from == null || it.measurement.timestamp >= from) && (through == null || it.measurement.timestamp <= through)
        }.sortedWith(compareBy({ it.measurement.timestamp }, { it.id }))
        val page = rows.take(limit)
        return MeasurementPage(page, if (rows.size > limit) page.last().let { HistoryCursor(it.measurement.timestamp, it.id) } else null)
    }
    override fun appendAlarms(batch: List<AlarmEventRecord>) {
        val staged = alarms.toMutableMap()
        batch.forEach {
            requireSession(it.sessionId)
            if (it.id in staged) fail(RepositoryFailure.CONFLICT)
            staged[it.id] = it
        }
        alarms.clear(); alarms.putAll(staged)
    }
    override fun alarms(sessionId: String) = alarms.values.filter { it.sessionId == sessionId }.sortedWith(compareBy({ it.transition.timestamp }, { it.id }))
    override fun saveSnapshot(snapshot: SnapshotRecord) {
        requireSession(snapshot.sessionId)
        if (snapshot.id in snapshots) fail(RepositoryFailure.CONFLICT)
        snapshots[snapshot.id] = snapshot
    }
    override fun snapshots(sessionId: String) = snapshots.values.filter { it.sessionId == sessionId }
    override fun saveError(error: ErrorRecord) {
        error.sessionId?.let(::requireSession)
        if (error.id in errors) fail(RepositoryFailure.CONFLICT)
        errors[error.id] = error
    }
    override fun errors(sessionId: String?) = errors.values.filter { it.sessionId == sessionId }
    override fun saveConfiguration(configuration: MonitoringConfiguration): ConfigurationRevision {
        val saved = ConfigurationRevision((configurations.keys.lastOrNull() ?: 0) + 1, configuration)
        configurations[saved.revision] = saved
        return saved
    }
    override fun configuration(revision: Long) = configurations[revision]
    override fun revisions() = ConfigurationRevisions(configurations.keys.lastOrNull(), applied)
    override fun markApplied(revision: Long, expectedSavedRevision: Long) {
        if (revision !in configurations) fail(RepositoryFailure.NOT_FOUND)
        if (configurations.keys.last() != expectedSavedRevision) fail(RepositoryFailure.CONFLICT)
        applied = revision
    }
}
