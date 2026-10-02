package com.filamentvision.backend.e2e

import com.filamentvision.backend.*
import com.filamentvision.backend.api.apiJson
import com.filamentvision.backend.simulator.SimulationScenario
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BackendTransportTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun actualRestAndWebsocketsShareOneProducerAndObserveDurableHistory() = testApplication {
        val c = BackendComposition.open(temporary.root.toPath().resolve("http.db"), temporary.root.toPath().resolve("objects"),
            inputSourceFactory = com.filamentvision.backend.input.runtime.SimulatorBackendInputSourceFactory)
        application { installBackend(c) }
        val ws = createClient { install(WebSockets) }
        suspend fun post(action: String, id: String) = client.post("/api/v1/devices/simulator-1/monitoring/$action") {
            contentType(ContentType.Application.Json); setBody("{\"commandId\":\"$id\"}")
        }
        try {
            assertEquals(HttpStatusCode.OK, client.get("/api/v1/devices/simulator-1/state").status)
            val responses = coroutineScope { (1..8).map { async { post("start", "same-start") } }.awaitAll() }
            assertTrue(responses.all { it.status == HttpStatusCode.OK })
            val session = apiJson.readTree(responses.first().bodyAsText())["data"]["session"]["id"].asText()
            c.source.setScenario(SimulationScenario.HIGH_DIAMETER)
            ws.webSocket("/api/v1/events?afterSequence=0") {
                val stream = this
                ws.webSocket("/api/v1/previews?cameraId=A&stage=RAW") {
                    val metadata = withTimeout(10000) { incoming.receive() } as Frame.Text
                    assertEquals("A", apiJson.readTree(metadata.readText())["cameraId"].asText())
                    val bytes = (withTimeout(10000) { incoming.receive() } as Frame.Binary).readBytes()
                    assertEquals(0x89.toByte(), bytes.first()); close()
                }
                withTimeout(10000) { while (c.runtime.state.value.statistics.sampleCount < 12) delay(10) }
                val latest = apiJson.readTree(client.get("/api/v1/devices/simulator-1/measurements/latest").bodyAsText())["data"]["measurement"]
                assertTrue(latest["diameterA"].asDouble() >= 1.8)
                val stop = (1..8).map { async { post("stop", "same-stop") } }.awaitAll()
                assertTrue(stop.all { it.status == HttpStatusCode.OK })
                var previous = 0L; val types = mutableSetOf<String>()
                withTimeout(10000) {
                    while ("session.finalized" !in types) {
                        val event = apiJson.readTree((stream.incoming.receive() as Frame.Text).readText())
                        val sequence = event["sequence"].asLong()
                        assertEquals(previous + 1, sequence); previous = sequence; types += event["type"].asText()
                    }
                }
                assertTrue(types.containsAll(setOf("session.started", "measurement.sample", "alarm.transition")))
                val history = apiJson.readTree(client.get("/api/v1/sessions/$session/measurements").bodyAsText())["data"]["items"]
                assertEquals(c.runtime.state.value.statistics.sampleCount.toInt(), history.size())
                assertEquals(1L, c.source.totalProducerStarts); assertEquals(1, c.source.maximumActiveProducerCount)
                assertEquals(0, c.source.activeProducerCount)
                close()
            }
        } finally { c.close() }
    }
    @Test fun executableOptionsAreBoundedAndDefaultToLoopbackProjectBuild() {
        val options = parseBackendOptions(emptyArray())
        assertEquals("127.0.0.1", options.host); assertEquals(8080, options.port)
        assertTrue(options.database.toString().contains("build"))
        for (args in listOf(arrayOf("--port", "0"), arrayOf("--port", "99999"), arrayOf("--unknown", "x"), arrayOf("--port", "8080", "--port", "8081")))
            assertThrows(IllegalArgumentException::class.java) { parseBackendOptions(args) }
    }
}
