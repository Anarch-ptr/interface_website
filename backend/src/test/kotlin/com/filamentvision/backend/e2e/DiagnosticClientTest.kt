package com.filamentvision.backend.e2e

import com.filamentvision.backend.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Collections

class DiagnosticClientTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun diagnosticUsesActualHttpAndWebsocketAndStopsItsSession() = runBlocking {
        val c = BackendComposition.open(temporary.root.toPath().resolve("diagnostic.db"), temporary.root.toPath().resolve("objects"),
            inputSourceFactory = com.filamentvision.backend.input.runtime.SimulatorBackendInputSourceFactory)
        val engine = embeddedServer(Netty, host = "127.0.0.1", port = 0) { installBackend(c) }.start(false)
        try {
            val port = engine.engine.resolvedConnectors().single().port
            val output = Collections.synchronizedList(mutableListOf<String>())
            withContext(Dispatchers.IO) {
                DiagnosticClient.run(arrayOf("--base-url", "http://127.0.0.1:$port", "--start", "--observe-seconds", "1", "--stop", "--history"), output::add)
            }
            assertTrue(output.any { it.startsWith("HISTORY") })
            assertTrue(output.any { it.startsWith("EVENT") && it.contains("measurement.sample") })
            assertTrue(output.any { it.startsWith("LATEST") && it.contains("1.75") })
            assertTrue(output.any { it.startsWith("FINAL") && it.contains("\"activeProducerCount\":0") })
            assertEquals(0, c.source.activeProducerCount); assertEquals(1L, c.source.totalProducerStarts)
        } finally { engine.stop(100, 5000); c.close() }
    }
}
