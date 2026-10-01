package com.filamentvision.core.event

import com.filamentvision.core.repository.AlarmEventRecord
import com.filamentvision.core.repository.ErrorRecord
import com.filamentvision.core.repository.MeasurementRecord
import com.filamentvision.core.runtime.RuntimeSession
import com.filamentvision.core.runtime.SessionFinalization

sealed interface DomainEvent {
    val deviceId: String
    data class Measurement(override val deviceId: String, val record: MeasurementRecord) : DomainEvent
    data class Alarm(override val deviceId: String, val record: AlarmEventRecord) : DomainEvent
    data class SessionStarted(override val deviceId: String, val session: RuntimeSession) : DomainEvent
    data class SessionFinalized(override val deviceId: String, val finalization: SessionFinalization) : DomainEvent
    data class Error(override val deviceId: String, val record: ErrorRecord) : DomainEvent
}

data class SequencedEvent(val sequence: Long, val event: DomainEvent)

sealed interface EventReplay {
    data class Available(val events: List<SequencedEvent>, val latestSequence: Long) : EventReplay
    data class SnapshotRequired(val oldestAvailableSequence: Long, val latestSequence: Long) : EventReplay
    data class FutureCursor(val latestSequence: Long) : EventReplay
}

data class EventHubMetrics(val published: Long, val retained: Int, val replayCapacity: Int)
