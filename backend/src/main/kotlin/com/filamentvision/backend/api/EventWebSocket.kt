package com.filamentvision.backend.api

import com.filamentvision.backend.application.BackendApplication
import com.filamentvision.backend.api.dto.*
import com.filamentvision.core.event.*
import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import kotlinx.coroutines.delay

/** state() must capture a coherent state/eventSequence from this hub's lifetime.
 * Polling the exclusive cursor removes any replay-to-subscription registration gap.
 * Each client holds at most one replay window; publication never invokes or waits for it.
 */
fun Application.installEventWebSocket(backend: BackendApplication, hub: SequencedEventHub,
    deviceIds: Set<String>, epochMillis: () -> Long = System::currentTimeMillis) {
    val devices = deviceIds.toSet()
    require(devices.isNotEmpty())
    devices.forEach { hub.metrics(it) }
    ensureWebSockets()
    routing {
        webSocket("/api/v1/events") {
            readOnlyStream {
                val query = call.request.queryParameters
                subscriptionValid(query.names().all { it in setOf("deviceId", "afterSequence") })
                val device = query.singleQuery("deviceId") ?: devices.singleOrNull()
                subscriptionValid(device != null && device in devices)
                val rawCursor = query.singleQuery("afterSequence")
                val after = rawCursor?.toLongOrNull()
                subscriptionValid(rawCursor == null || after != null && after >= 0 && rawCursor.matches(Regex("[0-9]+")))
                streamEvents(backend, hub, device!!, after, epochMillis, ::sendJson)
            }
        }
    }
}

internal suspend fun streamEvents(backend: BackendApplication, hub: SequencedEventHub, device: String,
    after: Long?, epochMillis: () -> Long, send: suspend (EventDto) -> Unit) {
    suspend fun snapshot(reason: String): Long {
        val state = backend.state(device)
        check(state.deviceId == device && state.eventSequence >= 0 && state.eventSequence <= hub.metrics(device).published)
        send(EventDto("state.snapshot", state.eventSequence, device, epochMillis(), StateSnapshotDto(state.toDto(), reason)))
        return state.eventSequence
    }
    var cursor = after ?: snapshot("INITIAL")
    var delivered = false
    while (true) {
        when (val replay = hub.replayAfter(device, cursor)) {
            is EventReplay.Available -> {
                for (event in replay.events) {
                    send(event.toDto()); cursor = event.sequence; delivered = true
                }
                if (replay.events.isEmpty()) delay(10)
            }
            is EventReplay.SnapshotRequired -> {
                val next = snapshot(if (delivered) "SLOW_CONSUMER" else "REPLAY_EXPIRED")
                // A permanently stale application snapshot must not cause a busy fallback loop.
                check(next > cursor); cursor = next
            }
            is EventReplay.FutureCursor -> throw SubscriptionFailure("FUTURE_CURSOR")
        }
    }
}
