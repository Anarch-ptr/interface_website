package com.filamentvision.backend

import com.filamentvision.backend.simulator.SimulationScenario
import com.filamentvision.backend.api.*
import com.filamentvision.backend.preview.PngPreviewEncoder
import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.*
import java.nio.file.Path

data class BackendOptions(val database: Path, val snapshotRoot: Path, val host: String, val port: Int, val scenario: SimulationScenario)
fun parseBackendOptions(args: Array<String>): BackendOptions {
    require(args.size % 2 == 0 && args.size <= 12)
    val values = linkedMapOf<String, String>()
    args.toList().chunked(2).forEach { (key, value) ->
        require(key in setOf("--database", "--snapshot-root", "--host", "--port", "--scenario"))
        require(value.isNotBlank() && value.length <= 4096 && key !in values)
        values[key] = value
    }
    val port = values["--port"]?.toIntOrNull() ?: if ("--port" in values) error("Invalid port") else 8080
    require(port in 1..65535)
    val host = values["--host"] ?: "127.0.0.1"
    require(host.length <= 253 && host.matches(Regex("[A-Za-z0-9.:-]+")))
    return BackendOptions(Path.of(values["--database"] ?: "build/phase2.db").toAbsolutePath().normalize(),
        Path.of(values["--snapshot-root"] ?: "build/snapshots").toAbsolutePath().normalize(), host, port,
        values["--scenario"]?.let(SimulationScenario::valueOf) ?: SimulationScenario.NORMAL)
}
fun Application.installBackend(composition: BackendComposition) {
    installRestApi(composition.application)
    installEventWebSocket(composition.application, composition.events, setOf(composition.deviceId), composition.epochMillis,
        pollIntervalMillis = { composition.applied.publicationIntervalMillis })
    installPreviewWebSocket(composition.previews, composition.previewKeys, PngPreviewEncoder(),
        maximumFramesPerSecond = { composition.applied.previewFramesPerSecond })
}
fun main(args: Array<String>) = runBlocking {
    val options = parseBackendOptions(args)
    val composition = BackendComposition.open(options.database, options.snapshotRoot)
    val engine = embeddedServer(Netty, host = options.host, port = options.port) { installBackend(composition) }
    val stop = CompletableDeferred<Unit>()
    val finished = CompletableDeferred<Unit>()
    val hook = Thread({ stop.complete(Unit); runBlocking { finished.await() } }, "backend-shutdown")
    Runtime.getRuntime().addShutdownHook(hook)
    try {
        engine.start(wait = false)
        println("READY http://${options.host}:${options.port} database=${options.database} producer=0 input=udp")
        Thread({
            while (true) {
                val line = System.`in`.bufferedReader().readLine() ?: break
                if (line.trim() == "shutdown") { stop.complete(Unit); break }
            }
        }, "backend-console").apply { isDaemon = true; start() }
        stop.await()
    } finally {
        try { engine.stop(250, 5000); composition.close(); println("STOPPED producer=${composition.inputSource.activeProducerCount}") }
        finally { finished.complete(Unit); runCatching { Runtime.getRuntime().removeShutdownHook(hook) } }
    }
}
