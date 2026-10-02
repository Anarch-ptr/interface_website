package com.filamentvision.backend.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.filamentvision.backend.application.*
import com.filamentvision.core.config.*
import com.filamentvision.core.frame.CameraId
import com.filamentvision.core.fusion.FusionReason
import com.filamentvision.core.metrics.*
import com.filamentvision.core.model.*
import com.filamentvision.core.repository.*
import com.filamentvision.core.runtime.*
import com.filamentvision.core.session.SessionStatistics
import com.filamentvision.core.vision.CameraMeasurement
import com.filamentvision.core.alarm.*
import com.filamentvision.backend.input.profile.*
import com.filamentvision.backend.input.runtime.UdpApplyOutcome

internal val json = ObjectMapper()
internal fun parse(body: String) = json.readTree(body)
internal val camera = CameraMeasurement(CameraId("A"), 20.0, 1.8, 0.95, 1001, 7, true)
internal val sample = OfficialMeasurement(camera, null, 1.8, 0.0, 0.95, MeasurementStatus.CAMERA_B_FAILURE, 1001, FusionReason.CAMERA_B_MISSING)
internal val session = RuntimeSession("s1", 1, 1000)
internal val sessionRecord = SessionRecord(session, SessionFinalization(session, 2000, FinalizationReason.COMPLETED,
    SessionStatistics(1, 1.8, 1.8, 1.8, 0.0, 0.95, 1), "secret jdbc stack trace"))
internal val configJson = """{"cameraA":{"calibration":{"mmPerPixel":0.09},"geometry":{"rotationDegrees":90}},"cameraB":{},"fusion":{"cameraAWeight":0.6,"cameraBWeight":0.4,"minimumConfidence":0.8,"maximumTimestampDifferenceMillis":50},"targetDiameter":1.8,"writeBatchSize":12,"flushIntervalMillis":500,"realtimeCapacity":200,"previewFramesPerSecond":4,"publicationIntervalMillis":250}"""

/** Deterministic application fake: records validated boundary arguments and models command replay.
 * Domain processing/producer ownership is deliberately absent from HTTP tests.
 */
internal class FakeBackend : BackendApplication {
    val calls = mutableListOf<Pair<String, List<Any?>>>()
    var failure: Throwable? = null
    var measurement = sample
    var configOutcome: ConfigurationApplyOutcome? = null
    var startOutcome = StartOutcome.STARTED
    var stopError: String? = null
    var snapshotContent = SnapshotContent(byteArrayOf(1, 0, -1, 10), "image/png", "capture.png")
    private fun record(name: String, vararg args: Any?) { calls += name to args.toList(); failure?.let { throw it } }
    private val starts = mutableMapOf<String, StartResult>()
    private val stops = mutableMapOf<String, StopResult>()
    private fun view(c: MonitoringConfiguration = MonitoringConfiguration()) = ConfigurationView(ConfigurationRevisions(3, 2), ConfigurationRevision(3, c), configOutcome)
    private val udpProfiles = UdpInputProfileSet(
        UdpInputProfile(UdpCameraId.A, true, "127.0.0.1", 5101, TransportMode.STRUCTURED_CHUNKED,
            PayloadProfile.Gray8(32, 16), ApplicationFramingProfile(), TransportLimits()),
        UdpInputProfile(UdpCameraId.B, true, null, 5102, TransportMode.SINGLE_DATAGRAM,
            PayloadProfile.Diameter(DiameterValueEncoding.FLOAT32, DiameterByteOrder.LITTLE_ENDIAN),
            ApplicationFramingProfile(), TransportLimits()))
    private fun udpView(outcome: UdpApplyOutcome? = null, profiles: UdpInputProfileSet = udpProfiles) =
        UdpInputConfigurationView(3, 2, profiles, outcome)
    override suspend fun state(deviceId: String): DeviceView { record("state", deviceId); return DeviceView(deviceId, ConnectionState.CONNECTED,
        MonitoringState(MonitoringLifecycle.RUNNING, session, measurement, activeProducerCount = 1, error = "secret jdbc stack trace"),
        listOf(CameraView("A", CameraState.AVAILABLE, true, true)), ConfigurationRevisions(3, 2), 14) }
    override suspend fun start(deviceId: String, commandId: String, targetDiameter: Double?, appliedRevision: Long?): StartResult {
        record("start", deviceId, commandId, targetDiameter, appliedRevision); return starts.getOrPut(commandId) { StartResult(startOutcome, session) } }
    override suspend fun stop(deviceId: String, commandId: String): StopResult { record("stop", deviceId, commandId); return stops.getOrPut(commandId) { StopResult(session, stopError) } }
    override suspend fun latest(deviceId: String): MeasurementRecord { record("latest", deviceId); return MeasurementRecord("m1", "s1", measurement, 2) }
    override suspend fun sessions(query: PageQuery): SessionPage { record("sessions", query); return SessionPage(listOf(sessionRecord), HistoryCursor(1000, "s1")) }
    override suspend fun session(sessionId: String): SessionRecord { record("session", sessionId); return sessionRecord }
    override suspend fun history(sessionId: String, query: PageQuery): MeasurementPage { record("history", sessionId, query); return MeasurementPage(listOf(MeasurementRecord("m1", sessionId, measurement, 2)), HistoryCursor(1001, "m1")) }
    override suspend fun alarms(sessionId: String): List<AlarmEventRecord> { record("alarms", sessionId); return listOf(AlarmEventRecord("alarm1", sessionId, AlarmTransition(AlarmState(), AlarmState(AlarmLevel.WARNING, AlarmDirection.HIGH, 3.0), 1001, 1.8))) }
    override suspend fun configuration(deviceId: String): ConfigurationView { record("configuration", deviceId); return view() }
    override suspend fun save(deviceId: String, commandId: String, configuration: MonitoringConfiguration): ConfigurationView { record("save", deviceId, commandId, configuration); return view(configuration) }
    override suspend fun apply(deviceId: String, commandId: String, revision: Long, expectedSavedRevision: Long): ConfigurationView { record("apply", deviceId, commandId, revision, expectedSavedRevision); return view() }
    override suspend fun saveAndApply(deviceId: String, commandId: String, configuration: MonitoringConfiguration): ConfigurationView { record("saveAndApply", deviceId, commandId, configuration); return view(configuration) }
    override suspend fun udpInputConfiguration(deviceId: String): UdpInputConfigurationView { record("udpInputConfiguration", deviceId); return udpView() }
    override suspend fun saveUdpInput(deviceId: String, commandId: String, profiles: UdpInputProfileSet): UdpInputConfigurationView {
        record("saveUdpInput", deviceId, commandId, profiles); return udpView(profiles = profiles)
    }
    override suspend fun applyUdpInput(deviceId: String, commandId: String, revision: Long, expectedSavedRevision: Long): UdpInputConfigurationView {
        record("applyUdpInput", deviceId, commandId, revision, expectedSavedRevision); return udpView(UdpApplyOutcome.APPLIED)
    }
    override suspend fun createDraft(deviceId: String, commandId: String, cameraId: String, configuration: VisionConfiguration): DraftView { record("createDraft", deviceId, commandId, cameraId, configuration); return DraftView("draft1", cameraId, 1, configuration) }
    override suspend fun updateDraft(deviceId: String, draftId: String, commandId: String, expectedRevision: Long, configuration: VisionConfiguration): DraftView { record("updateDraft", deviceId, draftId, commandId, expectedRevision, configuration); return DraftView(draftId, "A", 2, configuration) }
    override suspend fun deleteDraft(deviceId: String, draftId: String, commandId: String) { record("deleteDraft", deviceId, draftId, commandId) }
    override suspend fun cameras(deviceId: String): List<CameraView> { record("cameras", deviceId); return listOf(CameraView("A", CameraState.AVAILABLE, true, true), CameraView("B", CameraState.UNAVAILABLE, false, true)) }
    override suspend fun diagnostics(deviceId: String): DiagnosticsView { record("diagnostics", deviceId); return DiagnosticsView(MetricsSnapshot(1000,
        mapOf(MetricsCameraKey(deviceId, CameraId("A")) to CameraMetrics(CounterRate(10, 10.0), CounterRate(9, 9.0))),
        mapOf(MetricCounter.FUSION to CounterRate(9, 9.0)), mapOf(MetricLatency.PROCESSING to LatencyMetrics(9, 18.0, 3.0))), 2, 1, 4, "phase2") }
    override suspend fun errors(sessionId: String?): List<ErrorRecord> { record("errors", sessionId); return listOf(ErrorRecord("err1", sessionId, 1001, "CAMERA_UNAVAILABLE", "secret SELECT * FROM errors jdbc stack trace")) }
    override suspend fun snapshots(errorId: String): List<SnapshotRecord> { record("snapshots", errorId); return listOf(SnapshotRecord("snap1", "s1", 1001, "A", "C:/secret/encoded.bin", 7)) }
    override suspend fun snapshot(snapshotId: String): SnapshotContent { record("snapshot", snapshotId); return snapshotContent }
}
