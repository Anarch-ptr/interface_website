package com.filamentvision.backend.api

import com.filamentvision.backend.application.*
import com.filamentvision.backend.preview.PngPreviewEncoder
import com.filamentvision.backend.simulator.fixtures.SimulatorImageInputSource
import com.filamentvision.core.alarm.AlarmTransition
import com.filamentvision.core.config.*
import com.filamentvision.core.event.SequencedEventHub
import com.filamentvision.core.frame.CameraId
import com.filamentvision.core.fusion.*
import com.filamentvision.core.model.OfficialMeasurement
import com.filamentvision.core.model.FusionConfiguration
import com.filamentvision.core.preview.LatestPreviewHub
import com.filamentvision.core.runtime.*
import io.ktor.client.plugins.websocket.*
import io.ktor.server.testing.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class WebSocketOwnershipTest {
    @Test fun severalClientsAndReconnectLeaveAlreadyRunningRuntimeAndSourceUntouched() = testApplication {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val source = SimulatorImageInputSource(scope)
        val persistence = object : RuntimePersistence {
            override suspend fun createSession(session: RuntimeSession) = Unit
            override suspend fun writeMeasurements(session: RuntimeSession, batch: List<OfficialMeasurement>) = Unit
            override suspend fun writeAlarms(session: RuntimeSession, batch: List<AlarmTransition>) = Unit
            override suspend fun finalizeSession(finalization: SessionFinalization) = Unit
        }
        val runtime = MonitoringRuntime(scope, source,
            mapOf(CameraId("A") to VisionConfiguration(), CameraId("B") to VisionConfiguration()),
            LatestCompatiblePairingStrategy(CameraId("A"), CameraId("B")), FusionConfiguration(.5, .5, .7, 100), 1.75,
            persistence, RuntimeEventSink { })
        val events = SequencedEventHub(setOf("device"), 20)
        val fake = FakeBackend()
        val backend = object : BackendApplication by fake {
            override suspend fun state(deviceId: String) = fake.state(deviceId).copy(monitoring = runtime.state.value, eventSequence = events.metrics(deviceId).published)
        }
        val key = previewKey(); val previews = LatestPreviewHub(setOf(key), 100, 5)
        application { installEventWebSocket(backend, events, setOf("device")); installPreviewWebSocket(previews, setOf(key), PngPreviewEncoder()) }
        val ws = createClient { install(WebSockets) }
        assertEquals(StartOutcome.STARTED, runtime.start(StartMonitoringCommand("owner-start")).outcome)
        try {
            previews.publish(previewFrame(key, 1))
            ws.webSocket("/api/v1/events") {
                val eventOne = this
                assertEquals(1, nextJson()["payload"]["state"]["activeProducerCount"].asInt())
                ws.webSocket("/api/v1/events") {
                    val eventTwo = this
                    assertEquals(1, nextJson()["payload"]["state"]["activeProducerCount"].asInt())
                    ws.webSocket("/api/v1/previews?stage=RAW") {
                        assertImage(1, "camera", "RAW")
                        ws.webSocket("/api/v1/previews?stage=RAW") {
                            assertImage(1, "camera", "RAW")
                            events.publish(error())
                            assertEquals(1L, eventOne.nextJson()["sequence"].asLong())
                            assertEquals(1L, eventTwo.nextJson()["sequence"].asLong())
                            assertEquals(1, runtime.state.value.activeProducerCount)
                            assertEquals(1, source.activeProducerCount)
                            close()
                        }
                        close()
                    }
                    close()
                }
                close()
            }
            ws.webSocket("/api/v1/events?afterSequence=0") { assertEquals(1L, nextJson()["sequence"].asLong()); close() }
            assertEquals(1L, source.totalProducerStarts)
            assertEquals(1, source.activeProducerCount)
            assertEquals(1, runtime.state.value.activeProducerCount)
            assertEquals(MonitoringLifecycle.RUNNING, runtime.state.value.lifecycle)
            assertFalse(fake.calls.any { it.first == "start" || it.first == "stop" })
            withTimeout(3000) { while (previews.metrics.subscribers != 0) delay(5) }
            assertEquals(0, previews.metrics.pendingFrames)
        } finally { runtime.close(); scope.cancel() }
    }
}
