package com.filamentvision.backend.api.dto

import com.filamentvision.core.event.*
import com.filamentvision.core.repository.SessionRecord

data class EventDto(val type: String, val sequence: Long, val deviceId: String, val occurredAt: Long, val payload: Any)
data class StateSnapshotDto(val state: StateDto, val reason: String)
data class RuntimeStateEventDto(val monitoring: String, val session: SessionDto?, val activeProducerCount: Int)
data class ProtocolErrorDto(val code: String, val message: String = "Subscription unavailable", val type: String = "protocol.error")
data class PreviewMetadataDto(val deviceId: String, val cameraId: String, val stage: String,
    val width: Int, val height: Int, val timestamp: Long, val sourceSequence: Long,
    val mediaType: String, val byteLength: Int, val configurationRevision: Long? = null,
    val draftRevision: Long? = null, val official: Boolean? = null, val type: String = "preview.frame")

internal fun SequencedEvent.toDto(): EventDto = when (val value = event) {
    is DomainEvent.State -> EventDto("state.changed", sequence, value.deviceId, value.timestamp,
        RuntimeStateEventDto(value.state.lifecycle.name, value.state.activeSession?.toDto(), value.state.activeProducerCount))
    is DomainEvent.Configuration -> EventDto("configuration.changed", sequence, value.deviceId, value.timestamp, value.revisions)
    is DomainEvent.SessionStarted -> EventDto("session.started", sequence, value.deviceId, value.session.startedAtEpochMillis, value.session.toDto())
    is DomainEvent.SessionFinalized -> EventDto("session.finalized", sequence, value.deviceId, value.finalization.endedAtEpochMillis,
        SessionRecord(value.finalization.session, value.finalization).toDto())
    is DomainEvent.Measurement -> EventDto("measurement.sample", sequence, value.deviceId, value.record.measurement.timestamp, value.record.toDto())
    is DomainEvent.Alarm -> EventDto("alarm.transition", sequence, value.deviceId, value.record.transition.timestamp, value.record.toDto())
    is DomainEvent.Error -> EventDto("error.raised", sequence, value.deviceId, value.record.timestamp, value.record.toDto())
}
