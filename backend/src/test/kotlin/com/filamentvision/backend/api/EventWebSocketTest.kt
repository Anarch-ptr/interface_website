package com.filamentvision.backend.api

import com.filamentvision.backend.application.*
import com.filamentvision.core.event.*
import com.filamentvision.core.repository.*
import io.ktor.client.plugins.websocket.*
import io.ktor.server.testing.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Test

class EventWebSocketTest {
    @Test fun slowReliableTransportExpiresExplicitlyWhileFastConsumerReceivesEveryEvent() = runBlocking {
        val hub = SequencedEventHub(setOf("device"), 3)
        hub.publish(error())
        val backend = eventBackend(hub)
        val blocked = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val slowOutput = Channel<com.filamentvision.backend.api.dto.EventDto>(4)
        val fastOutput = Channel<com.filamentvision.backend.api.dto.EventDto>(4)
        val slow = launch {
            streamEvents(backend, hub, "device", 0, { 1 }) {
                if (it.sequence == 1L) { blocked.complete(Unit); release.await() }
                slowOutput.send(it)
            }
        }
        val fast = launch { streamEvents(backend, hub, "device", 0, { 1 }) { fastOutput.send(it) } }
        try {
            withTimeout(3000) { blocked.await() }
            assertEquals(1L, withTimeout(3000) { fastOutput.receive() }.sequence)
            repeat(100) {
                hub.publish(error())
                assertEquals(it + 2L, withTimeout(3000) { fastOutput.receive() }.sequence)
            }
            assertEquals(3, hub.metrics("device").retained)
            release.complete(Unit)
            assertEquals(1L, withTimeout(3000) { slowOutput.receive() }.sequence)
            val fallback = withTimeout(3000) { slowOutput.receive() }
            assertEquals("state.snapshot", fallback.type); assertEquals(101L, fallback.sequence)
            assertEquals("SLOW_CONSUMER", (fallback.payload as com.filamentvision.backend.api.dto.StateSnapshotDto).reason)
            hub.publish(error())
            assertEquals(102L, withTimeout(3000) { slowOutput.receive() }.sequence)
        } finally { slow.cancelAndJoin(); fast.cancelAndJoin() }
    }

    @Test fun initialSnapshotThenOrderedSafeDomainEnvelopes() = testApplication {
        val hub = SequencedEventHub(setOf("device"), 20)
        val backend = eventBackend(hub)
        application { installEventWebSocket(backend, hub, setOf("device"), epochMillis = { 900 }) }
        val ws = createClient { install(WebSockets) }
        ws.webSocket("/api/v1/events") {
            val snapshot = nextJson()
            assertEquals("state.snapshot", snapshot["type"].asText())
            assertEquals(0, snapshot["sequence"].asInt())
            assertEquals(900, snapshot["occurredAt"].asInt())
            assertEquals(1, snapshot["payload"]["state"]["activeProducerCount"].asInt())
            listOf(DomainEvent.SessionStarted("device", session),
                DomainEvent.Measurement("device", MeasurementRecord("m1", "s1", sample, 2)),
                DomainEvent.Alarm("device", backend.alarms("s1").single()),
                error(), DomainEvent.SessionFinalized("device", sessionRecord.finalization!!)).forEach(hub::publish)
            val expectedTypes = listOf("session.started", "measurement.sample", "alarm.transition", "error.raised", "session.finalized")
            val expectedTimes = listOf(1000L, 1001L, 1001L, 1200L, 2000L)
            repeat(5) { index ->
                val event = nextJson()
                assertEquals(expectedTypes[index], event["type"].asText())
                assertEquals(index + 1L, event["sequence"].asLong())
                assertEquals(expectedTimes[index], event["occurredAt"].asLong())
                assertEquals("device", event["deviceId"].asText())
                assertFalse(event.toString().contains("secret"))
                if (index == 1) assertEquals(7, event["payload"]["measurement"]["cameraA"]["sequence"].asInt())
            }
            close()
        }
    }

    @Test fun resumeExactSuffixAndExpiryExplicitSnapshot() = testApplication {
        val hub = SequencedEventHub(setOf("device"), 3)
        repeat(5) { hub.publish(error()) }
        application { installEventWebSocket(eventBackend(hub), hub, setOf("device")) }
        val ws = createClient { install(WebSockets) }
        ws.webSocket("/api/v1/events?deviceId=device&afterSequence=2") {
            assertEquals(listOf(3L, 4L, 5L), List(3) { nextJson()["sequence"].asLong() }); close()
        }
        ws.webSocket("/api/v1/events?afterSequence=1") {
            val snapshot = nextJson()
            assertEquals("state.snapshot", snapshot["type"].asText())
            assertEquals("REPLAY_EXPIRED", snapshot["payload"]["reason"].asText())
            assertEquals(5L, snapshot["sequence"].asLong())
            hub.publish(error()); assertEquals(6L, nextJson()["sequence"].asLong()); close()
        }
    }

    @Test fun publicationDuringSnapshotAndReplaySendHasNoGapOrDuplicate() = testApplication {
        val hub = SequencedEventHub(setOf("device"), 20)
        val delegate = eventBackend(hub)
        val backend = object : BackendApplication by delegate {
            override suspend fun state(deviceId: String): DeviceView {
                val boundary = delegate.state(deviceId)
                hub.publish(error()) // after state capture, before snapshot transmission
                return boundary
            }
        }
        application { installEventWebSocket(backend, hub, setOf("device")) }
        val ws = createClient { install(WebSockets) }
        ws.webSocket("/api/v1/events") {
            assertEquals(0L, nextJson()["sequence"].asLong())
            assertEquals(1L, nextJson()["sequence"].asLong())
            hub.publish(error()); hub.publish(error())
            assertEquals(listOf(2L, 3L), List(2) { nextJson()["sequence"].asLong() }); close()
        }
        ws.webSocket("/api/v1/events?afterSequence=1") {
            assertEquals(2L, nextJson()["sequence"].asLong()); hub.publish(error())
            assertEquals(listOf(3L, 4L), List(2) { nextJson()["sequence"].asLong() }); close()
        }
    }

    @Test fun invalidAndFutureCursorsCloseWithSafeStableError() = testApplication {
        val hub = SequencedEventHub(setOf("device"), 2)
        application { installEventWebSocket(eventBackend(hub), hub, setOf("device")) }
        val ws = createClient { install(WebSockets) }
        for (query in listOf("afterSequence=-1", "afterSequence=abc", "afterSequence=9223372036854775808",
            "afterSequence=0&afterSequence=1", "deviceId=unknown", "deviceId=", "afterSequence=1")) {
            ws.webSocket("/api/v1/events?$query") {
                val failure = nextJson()
                assertEquals("protocol.error", failure["type"].asText())
                assertEquals(if (query == "afterSequence=1") "FUTURE_CURSOR" else "INVALID_SUBSCRIPTION", failure["code"].asText())
                assertEquals(1008.toShort(), withTimeout(3000) { closeReason.await() }!!.code)
            }
        }
    }
}

internal fun error() = DomainEvent.Error("device", ErrorRecord("e1", "s1", 1200, "SOURCE_FAILURE", "secret filesystem path"))
internal fun eventBackend(hub: SequencedEventHub, fake: FakeBackend = FakeBackend()): BackendApplication = object : BackendApplication by fake {
    override suspend fun state(deviceId: String) = fake.state(deviceId).copy(eventSequence = hub.metrics(deviceId).published)
}
internal suspend fun DefaultClientWebSocketSession.nextJson() = parse(withTimeout(3000) { (incoming.receive() as Frame.Text).readText() })
