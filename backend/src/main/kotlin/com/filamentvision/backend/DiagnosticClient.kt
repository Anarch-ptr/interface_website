package com.filamentvision.backend

import com.filamentvision.backend.api.apiJson
import java.net.URI
import java.net.http.*
import java.time.Duration
import java.util.UUID
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicLong

/** Uses only public HTTP/WebSocket APIs and retains no event history. */
object DiagnosticClient {
    @JvmStatic fun main(args: Array<String>) = run(args)
    fun run(args: Array<String>, output: (String) -> Unit = ::println) {
        val options = linkedMapOf<String, String>()
        var index = 0
        while (index < args.size) {
            val key = args[index++]
            require(key !in options)
            options[key] = when (key) {
                "--start", "--stop", "--history" -> "true"
                "--base-url", "--device", "--observe-seconds" -> requireNotNull(args.getOrNull(index++))
                else -> throw IllegalArgumentException("Unknown option")
            }
        }
        val base = URI(options["--base-url"] ?: "http://127.0.0.1:8080")
        require(base.scheme in setOf("http", "https") && base.host != null && base.userInfo == null && base.query == null && base.fragment == null && base.path in setOf("", "/"))
        val device = options["--device"] ?: "simulator-1"
        require(device.matches(Regex("[A-Za-z0-9_-]{1,100}")))
        val seconds = (options["--observe-seconds"] ?: "3").toLong()
        require(seconds in 1..3600)
        val executor = Executors.newFixedThreadPool(3)
        val client = HttpClient.newBuilder().executor(executor).connectTimeout(Duration.ofSeconds(5)).build()
        var socket: WebSocket? = null
        var session: String? = null
        var stopped = false
        fun request(path: String, command: Boolean = false): com.fasterxml.jackson.databind.JsonNode {
            val builder = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(15))
            if (command) builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{\"commandId\":\"${UUID.randomUUID()}\"}"))
            val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
            check(response.statusCode() == 200) { "HTTP ${response.statusCode()}: ${response.body()}" }
            return apiJson.readTree(response.body())["data"]
        }
        try {
            output("INITIAL ${request("/api/v1/devices/$device/state")}")
            if ("--start" in options) {
                val response = request("/api/v1/devices/$device/monitoring/start", true)
                session = response["session"]["id"].asText(); output("START $response")
            }
            val events = AtomicLong(); val samples = AtomicLong()
            val listener = object : WebSocket.Listener {
                private val pending = StringBuilder()
                override fun onOpen(webSocket: WebSocket) { webSocket.request(1) }
                override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
                    check(pending.length + data.length <= 1024 * 1024)
                    pending.append(data)
                    if (last) {
                        val event = apiJson.readTree(pending.toString()); pending.setLength(0); events.incrementAndGet()
                        if (event["type"].asText() != "measurement.sample" || samples.incrementAndGet() <= 3) output("EVENT $event")
                    }
                    webSocket.request(1); return null
                }
            }
            val ws = URI((if (base.scheme == "https") "wss" else "ws") + "://" + base.rawAuthority + "/api/v1/events?deviceId=$device")
            socket = client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5)).buildAsync(ws, listener).get(10, TimeUnit.SECONDS)
            Thread.sleep(seconds * 1000)
            output("LATEST ${request("/api/v1/devices/$device/measurements/latest")}")
            if ("--stop" in options) {
                output("STOP ${request("/api/v1/devices/$device/monitoring/stop", true)}"); stopped = true
            }
            output("FINAL ${request("/api/v1/devices/$device/state")}")
            if ("--history" in options && session != null) {
                val history = request("/api/v1/sessions/$session/measurements?limit=500")
                output("HISTORY session=$session rows=${history["items"].size()} nextCursor=${history["nextCursor"]}")
                output("SESSION ${request("/api/v1/sessions/$session")}")
            }
            output("OBSERVED events=${events.get()} measurements=${samples.get()}")
        } finally {
            if (session != null && !stopped && "--stop" in options) runCatching { request("/api/v1/devices/$device/monitoring/stop", true) }
            try { socket?.sendClose(WebSocket.NORMAL_CLOSURE, "done")?.get(5, TimeUnit.SECONDS) }
            finally { socket?.abort(); executor.shutdownNow(); executor.awaitTermination(5, TimeUnit.SECONDS) }
        }
    }
}
