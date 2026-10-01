package com.filamentvision.backend.persistence.sqlite

import com.filamentvision.core.alarm.*
import com.filamentvision.core.config.MonitoringConfiguration
import com.filamentvision.core.frame.CameraId
import com.filamentvision.core.fusion.FusionReason
import com.filamentvision.core.model.*
import com.filamentvision.core.repository.*
import com.filamentvision.core.runtime.*
import com.filamentvision.core.session.SessionStatistics
import com.filamentvision.core.vision.CameraMeasurement
import com.filamentvision.core.vision.DetectionError
import java.sql.Connection
import java.sql.ResultSet

class SqliteRepositories(private val database: SqliteDatabase) : SessionRepository, MeasurementRepository,
    AlarmEventRepository, ConfigurationRepository, SnapshotRepository, ErrorRepository {
    override fun createSession(session: RuntimeSession) {
        database.access(true) { it.update("INSERT INTO sessions(id,generation,started_at) VALUES (?,?,?)",
            session.id, session.generation, session.startedAtEpochMillis) }
    }
    override fun finalizeSession(finalization: SessionFinalization) {
        database.access(true) { connection ->
            val existing = connection.session(finalization.session.id)
                ?: fail(RepositoryFailure.NOT_FOUND, "Session not found")
            if (existing.session != finalization.session || existing.finalization != null)
                fail(RepositoryFailure.CONFLICT, "Session already finalized or identity changed")
            with(finalization.statistics) {
                finite(averageDiameter, minimumDiameter, maximumDiameter, standardDeviation, averageConfidence)
                connection.update("""UPDATE sessions SET ended_at=?,outcome=?,error=?,sample_count=?,average_diameter=?,
                    minimum_diameter=?,maximum_diameter=?,standard_deviation=?,average_confidence=?,abnormal_event_count=? WHERE id=?""",
                    finalization.endedAtEpochMillis, finalization.reason.name, finalization.error, sampleCount, averageDiameter,
                    minimumDiameter, maximumDiameter, standardDeviation, averageConfidence, abnormalEventCount, finalization.session.id)
            }
        }
    }
    override fun session(id: String): SessionRecord? = database.access { it.session(id) }
    private fun Connection.session(id: String): SessionRecord? = query("SELECT * FROM sessions WHERE id=?", listOf(id)) {
        val session = RuntimeSession(it.getString("id"), it.getLong("generation"), it.getLong("started_at"))
        val ended = it.longOrNull("ended_at")
        SessionRecord(session, ended?.let { end ->
            SessionFinalization(session, end, FinalizationReason.valueOf(it.getString("outcome")), SessionStatistics(
                it.getLong("sample_count"), it.getDouble("average_diameter"), it.getDouble("minimum_diameter"),
                it.getDouble("maximum_diameter"), it.getDouble("standard_deviation"), it.getDouble("average_confidence"),
                it.getInt("abnormal_event_count")), it.getString("error"))
        })
    }.singleOrNull()

    override fun appendMeasurements(batch: List<MeasurementRecord>) {
        database.access(true) { connection ->
            connection.prepareStatement("INSERT INTO measurements (" + measurementColumns.joinToString(",") +
                ") VALUES (" + measurementColumns.joinToString(",") { "?" } + ")").use { statement ->
                batch.forEach { record ->
                    val m = record.measurement
                    finite(m.fusedDiameter, m.shapeDifference, m.confidence)
                    val values = listOf(record.id, record.sessionId, m.timestamp, record.appliedRevision,
                        m.fusedDiameter, m.shapeDifference, m.confidence, m.status.name, m.fusionReason.name) +
                        cameraValues(m.cameraA) + cameraValues(m.cameraB)
                    values.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
                    statement.executeUpdate()
                }
            }
        }
    }
    override fun history(sessionId: String, after: HistoryCursor?, limit: Int, from: Long?, through: Long?): MeasurementPage {
        if (limit !in 1..1000 || (from != null && through != null && from > through))
            fail(RepositoryFailure.INVALID, "Invalid history range or limit")
        return database.access { connection ->
            val predicates = mutableListOf("session_id=?")
            val values = mutableListOf<Any?>(sessionId)
            if (after != null) {
                predicates += "(timestamp > ? OR (timestamp = ? AND id > ?))"
                values.addAll(listOf(after.timestamp, after.timestamp, after.id))
            }
            if (from != null) { predicates += "timestamp >= ?"; values += from }
            if (through != null) { predicates += "timestamp <= ?"; values += through }
            values += limit + 1
            val found = connection.query("SELECT * FROM measurements WHERE " + predicates.joinToString(" AND ") +
                " ORDER BY timestamp,id LIMIT ?", values) { row ->
                MeasurementRecord(row.getString("id"), row.getString("session_id"), OfficialMeasurement(
                    row.camera("a"), row.camera("b"), row.getDouble("fused_diameter"), row.getDouble("shape_difference"),
                    row.getDouble("confidence"), MeasurementStatus.valueOf(row.getString("status")), row.getLong("timestamp"),
                    FusionReason.valueOf(row.getString("fusion_reason"))), row.longOrNull("applied_revision"))
            }
            val page = found.take(limit)
            MeasurementPage(page, if (found.size > limit) page.last().let { HistoryCursor(it.measurement.timestamp, it.id) } else null)
        }
    }
    override fun appendAlarms(batch: List<AlarmEventRecord>) {
        database.access(true) { connection ->
            batch.forEach { record ->
                with(record.transition) {
                    finite(fusedDiameter, from.deviationPercent, to.deviationPercent)
                    connection.update("""INSERT INTO alarms(id,session_id,timestamp,fused_diameter,from_level,from_direction,
                        from_deviation,to_level,to_direction,to_deviation) VALUES (?,?,?,?,?,?,?,?,?,?)""",
                        record.id, record.sessionId, timestamp, fusedDiameter, from.level.name, from.direction.name,
                        from.deviationPercent, to.level.name, to.direction.name, to.deviationPercent)
                }
            }
        }
    }
    override fun alarms(sessionId: String): List<AlarmEventRecord> = database.access { connection ->
        connection.query("SELECT * FROM alarms WHERE session_id=? ORDER BY timestamp,id", listOf(sessionId)) {
            AlarmEventRecord(it.getString("id"), it.getString("session_id"),
                AlarmTransition(it.alarmState("from"), it.alarmState("to"), it.getLong("timestamp"), it.getDouble("fused_diameter")))
        }
    }
    override fun saveSnapshot(snapshot: SnapshotRecord) {
        database.access(true) { it.update("INSERT INTO snapshots(id,session_id,timestamp,camera_id,object_key,source_sequence) VALUES (?,?,?,?,?,?)",
            snapshot.id, snapshot.sessionId, snapshot.timestamp, snapshot.cameraId, snapshot.objectKey, snapshot.sourceSequence) }
    }
    override fun snapshots(sessionId: String): List<SnapshotRecord> = database.access { connection ->
        connection.query("SELECT * FROM snapshots WHERE session_id=? ORDER BY timestamp,id", listOf(sessionId)) {
            SnapshotRecord(it.getString("id"), it.getString("session_id"), it.getLong("timestamp"), it.getString("camera_id"), it.getString("object_key"), it.longOrNull("source_sequence"))
        }
    }
    override fun saveError(error: ErrorRecord) {
        database.access(true) { it.update("INSERT INTO errors(id,session_id,timestamp,code,message) VALUES (?,?,?,?,?)",
            error.id, error.sessionId, error.timestamp, error.code, error.message) }
    }
    override fun errors(sessionId: String?): List<ErrorRecord> = database.access { connection ->
        connection.query("SELECT * FROM errors WHERE session_id IS ? ORDER BY timestamp,id", listOf(sessionId)) {
            ErrorRecord(it.getString("id"), it.getString("session_id"), it.getLong("timestamp"), it.getString("code"), it.getString("message"))
        }
    }
    override fun saveConfiguration(configuration: MonitoringConfiguration): ConfigurationRevision {
        configuration.validate()
        return database.access(true) { connection ->
            connection.update("INSERT INTO configuration_revisions(payload) VALUES (?)", ConfigurationCodec.encode(configuration))
            val revision = connection.query("SELECT last_insert_rowid()", emptyList()) { it.getLong(1) }.single()
            ConfigurationRevision(revision, configuration)
        }
    }
    override fun configuration(revision: Long): ConfigurationRevision? = database.access { it.configuration(revision) }
    private fun Connection.configuration(revision: Long) = query("SELECT revision,payload FROM configuration_revisions WHERE revision=?", listOf(revision)) {
        ConfigurationRevision(it.getLong("revision"), ConfigurationCodec.decode(it.getBytes("payload")))
    }.singleOrNull()
    override fun revisions(): ConfigurationRevisions = database.access { connection ->
        connection.query("""SELECT (SELECT MAX(revision) FROM configuration_revisions) AS saved,applied_revision
            FROM configuration_state WHERE singleton=1""", emptyList()) {
            ConfigurationRevisions(it.longOrNull("saved"), it.longOrNull("applied_revision"))
        }.single()
    }
    override fun markApplied(revision: Long, expectedSavedRevision: Long) {
        database.access(true) { connection ->
            if (connection.configuration(revision) == null) fail(RepositoryFailure.NOT_FOUND, "Configuration revision not found")
            val changed = connection.update("""UPDATE configuration_state SET applied_revision=? WHERE singleton=1 AND
                (SELECT MAX(revision) FROM configuration_revisions)=?""", revision, expectedSavedRevision)
            if (changed != 1) fail(RepositoryFailure.CONFLICT, "Saved configuration changed")
        }
    }

    private fun cameraValues(camera: CameraMeasurement?): List<Any?> {
        if (camera == null) return List(cameraFields.size) { null }
        finite(camera.pixelWidth, camera.diameterMm, camera.confidence)
        return with(camera) { listOf(cameraId.value, pixelWidth, diameterMm, confidence, timestamp, sequence, valid,
            roiLeft, roiTop, roiRight, roiBottom, leftEdge, rightEdge, error?.name) }
    }
    private fun ResultSet.camera(prefix: String): CameraMeasurement? {
        val id = getString(prefix + "_id") ?: return null
        fun name(field: String) = prefix + "_" + field
        return CameraMeasurement(CameraId(id), getDouble(name("pixel_width")), getDouble(name("diameter")),
            getDouble(name("confidence")), getLong(name("timestamp")), getLong(name("sequence")), getBoolean(name("valid")),
            getInt(name("roi_left")), getInt(name("roi_top")), getInt(name("roi_right")), getInt(name("roi_bottom")),
            intOrNull(name("left_edge")), intOrNull(name("right_edge")), getString(name("error"))?.let(DetectionError::valueOf))
    }
    private fun ResultSet.alarmState(prefix: String) = AlarmState(AlarmLevel.valueOf(getString(prefix + "_level")),
        AlarmDirection.valueOf(getString(prefix + "_direction")), getDouble(prefix + "_deviation"))
    private fun ResultSet.longOrNull(column: String): Long? = getLong(column).let { if (wasNull()) null else it }
    private fun ResultSet.intOrNull(column: String): Int? = getInt(column).let { if (wasNull()) null else it }
    private fun Connection.update(sql: String, vararg values: Any?): Int = prepareStatement(sql).use { statement ->
        values.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
        statement.executeUpdate()
    }
    private fun <T> Connection.query(sql: String, values: List<Any?>, map: (ResultSet) -> T): List<T> =
        prepareStatement(sql).use { statement ->
            values.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeQuery().use { result -> buildList { while (result.next()) add(map(result)) } }
        }
    private fun finite(vararg values: Double) {
        if (values.any { !it.isFinite() }) fail(RepositoryFailure.INVALID, "Non-finite measurement evidence")
    }
    private fun fail(type: RepositoryFailure, message: String): Nothing = throw RepositoryException(type, message)
    companion object {
        private val cameraFields = listOf("id", "pixel_width", "diameter", "confidence", "timestamp", "sequence", "valid",
            "roi_left", "roi_top", "roi_right", "roi_bottom", "left_edge", "right_edge", "error")
        private val measurementColumns = listOf("id", "session_id", "timestamp", "applied_revision", "fused_diameter",
            "shape_difference", "confidence", "status", "fusion_reason") + cameraFields.map { "a_" + it } + cameraFields.map { "b_" + it }
    }
}
