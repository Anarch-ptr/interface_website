package com.filamentvision.core.runtime

import com.filamentvision.core.alarm.AlarmState
import com.filamentvision.core.alarm.AlarmTransition
import com.filamentvision.core.model.OfficialMeasurement
import com.filamentvision.core.session.SessionStatistics
import com.filamentvision.core.vision.DetectionError

enum class MonitoringLifecycle { IDLE, STARTING, RUNNING, STOPPING, ERROR }
data class RuntimeSession(val id: String, val generation: Long, val startedAtEpochMillis: Long)
enum class FinalizationReason { COMPLETED, INTERRUPTED }
data class SessionFinalization(
    val session: RuntimeSession,
    val endedAtEpochMillis: Long,
    val reason: FinalizationReason,
    val statistics: SessionStatistics,
    val error: String? = null,
)

/** Session-local image diagnostics; invalid Vision results never enter official statistics. */
data class RuntimeVisionDiagnostics(
    val processedFrames: Long = 0,
    val rejectedFrames: Long = 0,
    val lastError: DetectionError? = null,
)

data class MonitoringState(
    val lifecycle: MonitoringLifecycle = MonitoringLifecycle.IDLE,
    val activeSession: RuntimeSession? = null,
    val latestMeasurement: OfficialMeasurement? = null,
    val latestAlarm: AlarmState = AlarmState(),
    val statistics: SessionStatistics = SessionStatistics(),
    val activeProducerCount: Int = 0,
    val maximumActiveProducerCount: Int = 0,
    val cachedCommandCount: Int = 0,
    val error: String? = null,
    val visionDiagnostics: RuntimeVisionDiagnostics = RuntimeVisionDiagnostics(),
)

/** Minimal runtime ports. Durable repository schemas and adapters belong to Task 8.
 * Calls are serialized; a successful return acknowledges the complete supplied batch.
 */
interface RuntimePersistence {
    suspend fun createSession(session: RuntimeSession)
    suspend fun writeMeasurements(session: RuntimeSession, batch: List<OfficialMeasurement>)
    suspend fun writeAlarms(session: RuntimeSession, batch: List<AlarmTransition>)
    suspend fun finalizeSession(finalization: SessionFinalization)
}
fun interface RuntimeEventSink {
    suspend fun sessionFinalized(finalization: SessionFinalization)
}
