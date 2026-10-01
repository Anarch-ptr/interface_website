package com.filamentvision.backend.api

import com.filamentvision.backend.preview.PreviewEncoder
import com.filamentvision.backend.api.dto.PreviewMetadataDto
import com.filamentvision.core.preview.*
import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import java.lang.ref.WeakReference

fun Application.installPreviewWebSocket(hub: LatestPreviewHub, keys: Set<PreviewKey>, encoder: PreviewEncoder,
    maximumFramesPerSecond: () -> Int = { 30 }) {
    val allowed = keys.toSet()
    require(allowed.isNotEmpty())
    allowed.forEach { hub.latest(it) }
    ensureWebSockets()
    routing {
        webSocket("/api/v1/previews") {
            readOnlyStream {
                val query = call.request.queryParameters
                subscriptionValid(query.names().all { it in setOf("deviceId", "cameraId", "stage", "maxFps") })
                val device = query.singleQuery("deviceId") ?: allowed.map { it.deviceId }.distinct().singleOrNull()
                val camera = query.singleQuery("cameraId")
                val stage = query.singleQuery("stage")
                val key = allowed.singleOrNull { it.deviceId == device && it.cameraId.value == camera && it.stage.name == stage }
                subscriptionValid(key != null)
                val rawFps = query.singleQuery("maxFps")
                val fps = rawFps?.toIntOrNull() ?: if (rawFps == null) 5 else 0
                subscriptionValid(fps in 1..30)
                streamPreviews(hub, key!!, encoder, fps, maximumFramesPerSecond = maximumFramesPerSecond) { metadata, bytes ->
                    withTimeout(5000) {
                        send(Frame.Text(apiJson.writeValueAsString(metadata)))
                        send(Frame.Binary(true, bytes)); flush()
                    }
                }
            }
        }
    }
}

/** No Flow buffer or extra pending queue: suspension leaves at most one pending in the hub.
 * Rate waits happen before encoding; after waiting use the newest retained value for this key.
 */
internal suspend fun streamPreviews(hub: LatestPreviewHub, key: PreviewKey, encoder: PreviewEncoder, fps: Int,
    nanos: () -> Long = System::nanoTime, pause: suspend (Long) -> Unit = { delay(it) },
    maximumFramesPerSecond: () -> Int = { fps },
    send: suspend (PreviewMetadataDto, ByteArray) -> Unit) {
    require(fps in 1..30)
    var lastSent: Long? = null
    // Identity deduplication must not keep a previously sent raw payload alive.
    var delivered = WeakReference<PreviewFrame>(null)
    hub.frames(setOf(key)).collect { received ->
        if (received === delivered.get()) return@collect
        lastSent?.let { last ->
            val rate = minOf(fps, maximumFramesPerSecond().coerceAtLeast(1))
            val interval = (1_000_000_000L + rate - 1) / rate
            val remaining = interval - (nanos() - last)
            if (remaining > 0) pause((remaining + 999_999) / 1_000_000)
        }
        val frame = hub.latest(key) ?: received
        if (frame === delivered.get()) return@collect
        val encoded = encoder.encode(frame)
        check(encoded.mediaType in setOf("image/png", "image/jpeg") && encoded.bytes.isNotEmpty())
        send(PreviewMetadataDto(key.deviceId, key.cameraId.value, key.stage.name, frame.width, frame.height,
            frame.timestamp, frame.sourceSequence, encoded.mediaType, encoded.bytes.size), encoded.bytes)
        delivered = WeakReference(frame)
        lastSent = nanos()
    }
}
