package com.filamentvision.backend.application

import com.filamentvision.core.config.*
import com.filamentvision.core.metrics.MetricsSnapshot
import com.filamentvision.core.repository.*
import com.filamentvision.core.runtime.*

/** Application boundary only. Composition owns scheduling, resource lookup, command deduplication,
 * preconditions and runtime/repository coordination; HTTP never owns a producer or a file path.
 * Missing resources throw ApplicationFailure(NOT_FOUND). Mutations are idempotent by device,
 * operation and commandId, including drafts/configuration. Implementations must not expose secrets.
 */
interface BackendApplication {
    suspend fun state(deviceId: String): DeviceView
    suspend fun start(deviceId: String, commandId: String, targetDiameter: Double?, appliedRevision: Long?): StartResult
    suspend fun stop(deviceId: String, commandId: String): StopResult
    suspend fun latest(deviceId: String): MeasurementRecord?
    suspend fun sessions(query: PageQuery): SessionPage
    suspend fun session(sessionId: String): SessionRecord
    suspend fun history(sessionId: String, query: PageQuery): MeasurementPage
    suspend fun alarms(sessionId: String): List<AlarmEventRecord>
    suspend fun configuration(deviceId: String): ConfigurationView
    suspend fun save(deviceId: String, commandId: String, configuration: MonitoringConfiguration): ConfigurationView
    suspend fun apply(deviceId: String, commandId: String, revision: Long, expectedSavedRevision: Long): ConfigurationView
    suspend fun saveAndApply(deviceId: String, commandId: String, configuration: MonitoringConfiguration): ConfigurationView
    suspend fun createDraft(deviceId: String, commandId: String, cameraId: String, configuration: VisionConfiguration): DraftView
    suspend fun updateDraft(deviceId: String, draftId: String, commandId: String, expectedRevision: Long, configuration: VisionConfiguration): DraftView
    suspend fun deleteDraft(deviceId: String, draftId: String, commandId: String): Unit
    suspend fun cameras(deviceId: String): List<CameraView>
    suspend fun diagnostics(deviceId: String): DiagnosticsView
    suspend fun errors(sessionId: String?): List<ErrorRecord>
    suspend fun snapshots(errorId: String): List<SnapshotRecord>
    /** Test-mode encoded bytes only; no client-supplied object key or filesystem path. */
    suspend fun snapshot(snapshotId: String): SnapshotContent
}

enum class ApplicationFailureCode { INVALID, NOT_FOUND, CONFLICT, REQUIRES_STOP, UNAVAILABLE }
class ApplicationFailure(val code: ApplicationFailureCode) : RuntimeException()
data class PageQuery(val after: HistoryCursor? = null, val limit: Int = 100, val from: Long? = null, val through: Long? = null)
data class SessionPage(val items: List<SessionRecord>, val nextCursor: HistoryCursor?)
enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, ERROR }
enum class CameraState { AVAILABLE, UNAVAILABLE, ERROR }
data class CameraView(val cameraId: String, val state: CameraState, val previewSupported: Boolean, val calibrationSupported: Boolean)
data class DeviceView(val deviceId: String, val connection: ConnectionState, val monitoring: MonitoringState,
    val cameras: List<CameraView>, val revisions: ConfigurationRevisions, val eventSequence: Long)
data class ConfigurationView(val revisions: ConfigurationRevisions, val saved: ConfigurationRevision?,
    val outcome: ConfigurationApplyOutcome? = null)
data class DraftView(val id: String, val cameraId: String, val revision: Long, val configuration: VisionConfiguration)
data class DiagnosticsView(val metrics: MetricsSnapshot, val queuedMeasurements: Int, val retainedSnapshots: Int,
    val retainedSnapshotBytes: Long, val backendVersion: String)
data class SnapshotContent(val bytes: ByteArray, val mediaType: String, val filename: String)
