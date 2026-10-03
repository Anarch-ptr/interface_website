package com.filamentvision.backend.persistence.sqlite

import com.filamentvision.core.alarm.*
import com.filamentvision.core.config.*
import com.filamentvision.core.frame.CameraId
import com.filamentvision.core.model.*
import com.filamentvision.core.repository.*
import com.filamentvision.core.runtime.*
import com.filamentvision.core.session.SessionStatistics
import com.filamentvision.core.vision.CameraMeasurement
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

data class RepositorySet(val sessions: SessionRepository, val measurements: MeasurementRepository, val alarms: AlarmEventRepository,
    val configurations: ConfigurationRepository, val snapshots: SnapshotRepository, val errors: ErrorRepository)
fun repositorySet(value: Any) = RepositorySet(value as SessionRepository, value as MeasurementRepository, value as AlarmEventRepository,
    value as ConfigurationRepository, value as SnapshotRepository, value as ErrorRepository)

abstract class RepositoryConformance {
    protected lateinit var r: RepositorySet
    protected abstract fun openRepositories(): RepositorySet
    @Before fun open() { r = openRepositories() }

    @Test fun sessionsFinalizeWithExactStatisticsAndError() {
        r.sessions.createSession(session)
        assertEquals(SessionRecord(session), r.sessions.session("s"))
        val final = SessionFinalization(session, 200, FinalizationReason.INTERRUPTED, SessionStatistics(3, 1.8, 1.7, 1.9, .08, .91, 2), "input lost")
        r.sessions.finalizeSession(final)
        assertEquals(SessionRecord(session, final), r.sessions.session("s"))
        assertNull(r.sessions.session("missing"))
    }
    @Test fun missingSessionAndDuplicateSessionAreTypedFailures() {
        fails(RepositoryFailure.NOT_FOUND) { r.sessions.finalizeSession(SessionFinalization(session, 20, FinalizationReason.COMPLETED, SessionStatistics())) }
        r.sessions.createSession(session)
        fails(RepositoryFailure.CONFLICT) { r.sessions.createSession(session) }
    }
    @Test fun measurementsRetainOneEvidenceAndEveryVisionField() {
        r.sessions.createSession(session)
        val sample = measurement("m", 10)
        r.measurements.appendMeasurements(listOf(sample))
        assertEquals(listOf(sample), r.measurements.history("s").items)
        assertNull(r.measurements.history("s").items.single().measurement.legacyFusion)
    }
    @Test fun batchWithInvalidForeignKeyRollsBackEarlierValidRows() {
        r.sessions.createSession(session)
        fails(RepositoryFailure.INVALID) { r.measurements.appendMeasurements(listOf(measurement("ok", 1), measurement("bad", 2).copy(sessionId = "absent"))) }
        assertTrue(r.measurements.history("s").items.isEmpty())
    }
    @Test fun duplicateInBatchRollsBackAndDoesNotOverwriteExistingEvidence() {
        r.sessions.createSession(session)
        r.measurements.appendMeasurements(listOf(measurement("old", 1)))
        fails(RepositoryFailure.CONFLICT) { r.measurements.appendMeasurements(listOf(measurement("new", 2), measurement("old", 3))) }
        assertEquals(listOf(measurement("old", 1)), r.measurements.history("s").items)
    }
    @Test fun equalTimestampsUseStableIdKeysetWithoutSkipsOrDuplicates() {
        r.sessions.createSession(session)
        r.measurements.appendMeasurements(listOf(measurement("d", 12), measurement("c", 10), measurement("a", 10), measurement("b", 10), measurement("e", 15)))
        val first = r.measurements.history("s", limit = 2)
        assertEquals(listOf("a", "b"), first.items.map { it.id })
        assertEquals(HistoryCursor(10, "b"), first.nextCursor)
        val second = r.measurements.history("s", first.nextCursor, 2)
        assertEquals(listOf("c", "d"), second.items.map { it.id })
        assertEquals(HistoryCursor(12, "d"), second.nextCursor)
        val third = r.measurements.history("s", second.nextCursor, 2)
        assertEquals(listOf("e"), third.items.map { it.id })
        assertNull(third.nextCursor)
        assertEquals(listOf("a", "b", "c"), r.measurements.history("s", from = 10, through = 10).items.map { it.id })
        assertEquals(second, r.measurements.history("s", first.nextCursor, 2))
        fails(RepositoryFailure.INVALID) { r.measurements.history("s", limit = 0) }
    }
    @Test fun exactMeasurementAndAlarmRetriesAreIdempotentButChangedEvidenceConflicts() {
        r.sessions.createSession(session)
        r.configurations.saveConfiguration(configuration)
        val original = measurement("m", 10).copy(appliedRevision = 1)
        r.measurements.appendMeasurements(listOf(original))
        r.measurements.appendMeasurements(listOf(original, original))
        assertEquals(listOf(original), r.measurements.history("s").items)
        val changes = listOf(original.copy(appliedRevision = 2), original.copy(sessionId = "other"),
            original.copy(measurement = original.measurement.copy(evidence = listOf(original.measurement.evidence.single().copy(sequence = 99)))),
            original.copy(measurement = original.measurement.copy(evidence = original.measurement.evidence, confidence = .5)))
        changes.forEach { changed -> fails(RepositoryFailure.CONFLICT) {
            r.measurements.appendMeasurements(listOf(measurement("new", 20), changed))
        } }
        assertEquals(listOf(original), r.measurements.history("s").items)
        r.alarms.appendAlarms(listOf(alarm))
        r.alarms.appendAlarms(listOf(alarm, alarm))
        assertEquals(listOf(alarm), r.alarms.alarms("s"))
        for (changed in listOf(alarm.copy(sessionId = "other"), alarm.copy(transition = alarm.transition.copy(fusedDiameter = 1.9)),
            alarm.copy(transition = alarm.transition.copy(to = alarm.transition.to.copy(deviationPercent = 9.0))))) {
            fails(RepositoryFailure.CONFLICT) { r.alarms.appendAlarms(listOf(alarm.copy(id = "new"), changed)) }
        }
        assertEquals(listOf(alarm), r.alarms.alarms("s"))
    }
    @Test fun alarmsEnforceSessionForeignKeyAndRetainTransitionEvidence() {
        fails(RepositoryFailure.INVALID) { r.alarms.appendAlarms(listOf(alarm)) }
        r.sessions.createSession(session)
        r.alarms.appendAlarms(listOf(alarm))
        assertEquals(listOf(alarm), r.alarms.alarms("s"))
        fails(RepositoryFailure.INVALID) { r.alarms.appendAlarms(listOf(alarm.copy(id = "new"), alarm.copy(id = "bad", sessionId = "absent"))) }
        assertEquals(listOf(alarm), r.alarms.alarms("s"))
    }
    @Test fun snapshotAndErrorRecordsRetainDomainMetadataAndForeignKeys() {
        val snapshot = SnapshotRecord("p", "s", 12, "B", "objects/p", 123)
        val error = ErrorRecord("e", "s", 13, "INPUT", "source failed")
        fails(RepositoryFailure.INVALID) { r.snapshots.saveSnapshot(snapshot) }
        fails(RepositoryFailure.INVALID) { r.errors.saveError(error) }
        r.sessions.createSession(session)
        r.snapshots.saveSnapshot(snapshot)
        r.errors.saveError(error)
        r.errors.saveError(error.copy(id = "global", sessionId = null))
        assertEquals(listOf(snapshot), r.snapshots.snapshots("s"))
        assertEquals(listOf(error), r.errors.errors("s"))
        assertEquals(listOf(error.copy(id = "global", sessionId = null)), r.errors.errors(null))
    }
    @Test fun configurationRevisionsPersistSeparatelyAndCompareAndSet() {
        val first = r.configurations.saveConfiguration(configuration)
        val second = r.configurations.saveConfiguration(configuration.copy(targetDiameter = 2.0))
        assertEquals(1L, first.revision)
        assertEquals(2L, second.revision)
        assertEquals(ConfigurationRevisions(2, null), r.configurations.revisions())
        fails(RepositoryFailure.CONFLICT) { r.configurations.markApplied(1, 1) }
        r.configurations.markApplied(1, 2)
        assertEquals(ConfigurationRevisions(2, 1), r.configurations.revisions())
        assertEquals(configuration, r.configurations.configuration(1)!!.configuration)
        fails(RepositoryFailure.NOT_FOUND) { r.configurations.markApplied(99, 2) }
    }
    @Test fun nonFiniteEvidenceIsRejectedInsteadOfConvertingToNull() {
        r.sessions.createSession(session)
        fails(RepositoryFailure.INVALID) { r.measurements.appendMeasurements(listOf(measurement("ok", 1), measurement("bad", 2).let { it.copy(measurement = it.measurement.copy(evidence = it.measurement.evidence, confidence = Double.NaN)) })) }
        assertTrue(r.measurements.history("s").items.isEmpty())
    }
    protected fun fails(kind: RepositoryFailure, action: () -> Unit) {
        assertEquals(kind, assertThrows(RepositoryException::class.java, action).failure)
    }
    companion object {
        val session = RuntimeSession("s", 7, 1)
        val alarm = AlarmEventRecord("alarm", "s", AlarmTransition(AlarmState(), AlarmState(AlarmLevel.CRITICAL, AlarmDirection.HIGH, 6.0), 10, 1.86))
        val configuration = MonitoringConfiguration(
            vision = VisionConfiguration(CalibrationProfile(1, 2, 30, 40, GrayscaleMethod.AVERAGE, ThresholdMode.AUTOMATIC, 57, 12, 3, 27, .021, .83), ImageGeometry(90, true, false)),
            targetDiameter = 1.8, writeBatchSize = 12, flushIntervalMillis = 750,
            realtimeCapacity = 90, previewFramesPerSecond = 3, publicationIntervalMillis = 333,
        )
        fun measurement(id: String, time: Long) = MeasurementRecord(id, "s", OfficialMeasurement(
            listOf(CameraMeasurement(CameraId("camera"), 83.0, 1.77, .92, 101, 23, true, 1, 2, 30, 40, 3, 29)),
            1.77, .92, MeasurementStatus.NORMAL, time))
    }
}
