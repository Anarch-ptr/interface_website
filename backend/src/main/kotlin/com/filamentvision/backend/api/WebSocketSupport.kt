package com.filamentvision.backend.api

import com.filamentvision.backend.api.dto.ProtocolErrorDto
import io.ktor.http.Parameters
import io.ktor.server.application.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select

internal class SubscriptionFailure(val code: String) : RuntimeException()
internal fun subscriptionValid(condition: Boolean) { if (!condition) throw SubscriptionFailure("INVALID_SUBSCRIPTION") }
internal fun Parameters.singleQuery(name: String): String? = getAll(name)?.let {
    subscriptionValid(it.size == 1 && it.single().isNotBlank()); it.single()
}
internal fun Application.ensureWebSockets() {
    if (pluginOrNull(WebSockets) == null) install(WebSockets) { maxFrameSize = 1024 }
}

/** One writer, no adapter send queue. Flush and timeout bound a stalled transport.
 * The receive job exists only to observe disconnects and reject client data on a read-only stream.
 */
internal suspend fun DefaultWebSocketServerSession.readOnlyStream(block: suspend () -> Unit) {
    try {
        coroutineScope {
            val writer = async { block() }
            val reader = async {
                for (frame in incoming) {
                    if (frame is Frame.Text || frame is Frame.Binary) throw SubscriptionFailure("READ_ONLY_STREAM")
                }
            }
            try { select<Unit> { writer.onAwait { }; reader.onAwait { } } }
            finally { writer.cancelAndJoin(); reader.cancelAndJoin() }
        }
    } catch (_: TimeoutCancellationException) {
        withTimeoutOrNull(1000) { close(CloseReason(1013, "Reconnect and resume")) }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: Exception) {
        val code = (failure as? SubscriptionFailure)?.code ?: "STREAM_UNAVAILABLE"
        withTimeoutOrNull(1000) {
            send(Frame.Text(apiJson.writeValueAsString(ProtocolErrorDto(code))))
            close(CloseReason(if (failure is SubscriptionFailure) 1008 else 1011, code))
        }
    }
}

internal suspend fun DefaultWebSocketServerSession.sendJson(value: Any) = withTimeout(5000) {
    send(Frame.Text(apiJson.writeValueAsString(value))); flush()
}
