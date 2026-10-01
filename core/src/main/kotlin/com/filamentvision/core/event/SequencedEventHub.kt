package com.filamentvision.core.event

/**
 * In-process ordered replay, not durable delivery. Persisted repositories/state snapshots
 * remain authoritative across restart or cursor expiry. Sequence 0 precedes the first event;
 * cursors are exclusive and scoped to a device and this hub lifetime.
 *
 * The fixed device registry bounds total retention to device count * replayCapacity.
 * Capacity must be positive so the current publication is always available. Publication
 * never calls observers; consumers query a suffix and must handle SnapshotRequired.
 */
class SequencedEventHub(deviceIds: Set<String>, private val replayCapacity: Int) {
    private class Window {
        var sequence = 0L
        val events = ArrayDeque<SequencedEvent>()
    }

    private val lock = Any()
    private val windows: Map<String, Window>

    init {
        require(replayCapacity > 0)
        require(deviceIds.isNotEmpty() && deviceIds.all { it.isNotBlank() })
        windows = deviceIds.associateWith { Window() }
    }

    fun publish(event: DomainEvent): SequencedEvent = synchronized(lock) {
        val window = window(event.deviceId)
        check(window.sequence < Long.MAX_VALUE) { "Event sequence exhausted" }
        val sequenced = SequencedEvent(++window.sequence, event)
        if (window.events.size == replayCapacity) window.events.removeFirst()
        window.events.addLast(sequenced)
        sequenced
    }

    fun replayAfter(deviceId: String, sequence: Long): EventReplay = synchronized(lock) {
        require(sequence >= 0)
        val window = window(deviceId)
        when {
            sequence > window.sequence -> EventReplay.FutureCursor(window.sequence)
            window.events.isNotEmpty() && sequence < window.events.first().sequence - 1 ->
                EventReplay.SnapshotRequired(window.events.first().sequence, window.sequence)
            else -> EventReplay.Available(window.events.filter { it.sequence > sequence }, window.sequence)
        }
    }

    fun metrics(deviceId: String): EventHubMetrics = synchronized(lock) {
        val window = window(deviceId)
        EventHubMetrics(window.sequence, window.events.size, replayCapacity)
    }

    private fun window(deviceId: String) = requireNotNull(windows[deviceId]) { "Unknown device: $deviceId" }
}
