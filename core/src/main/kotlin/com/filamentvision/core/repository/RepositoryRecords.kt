package com.filamentvision.core.repository

import com.filamentvision.core.alarm.AlarmTransition
import com.filamentvision.core.model.OfficialMeasurement
import com.filamentvision.core.runtime.RuntimeSession
import com.filamentvision.core.runtime.SessionFinalization

enum class RepositoryFailure { INVALID, CONFLICT, NOT_FOUND, STORAGE }
/** Deliberately carries no platform exception cause across the boundary. */
class RepositoryException(val failure: RepositoryFailure, message: String) : RuntimeException(message)
data class SessionRecord(val session: RuntimeSession, val finalization: SessionFinalization? = null)
data class MeasurementRecord(val id: String, val sessionId: String, val measurement: OfficialMeasurement, val appliedRevision: Long? = null)
data class HistoryCursor(val timestamp: Long, val id: String)
data class MeasurementPage(val items: List<MeasurementRecord>, val nextCursor: HistoryCursor?)
data class AlarmEventRecord(val id: String, val sessionId: String, val transition: AlarmTransition)
/** Null sourceSequence denotes legacy metadata whose source sequence was not recorded. */
data class SnapshotRecord(val id: String, val sessionId: String, val timestamp: Long, val cameraId: String, val objectKey: String,
    val sourceSequence: Long? = null)
data class ErrorRecord(val id: String, val sessionId: String?, val timestamp: Long, val code: String, val message: String)
