package com.filamentvision.core.preview

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class PreviewHubMetrics(
    val publishedFrames: Long,
    val deliveredFrames: Long,
    val droppedFrames: Long,
    val replacedLatestFrames: Long,
    val retainedFrames: Int,
    val pendingFrames: Int,
    val subscribers: Int,
)

/**
 * One global latest frame per registered device/camera/stage key; one pending frame per
 * subscribed key per collector, plus at most one in-flight frame per collector. Payloads
 * are shared immutable values. Both registry and subscriber count are fixed bounds.
 *
 * Publishing only changes bounded maps and non-blocking conflated wakeups. No user
 * callback runs under the lock. New collectors receive retained latest values; pending
 * keys drain in reverse publication order (latest arrival first, not source-clock order).
 * A continuously busy key may starve older keys; preview is intentionally best effort.
 *
 * deliveredFrames counts frames handed to downstream emit (not client acknowledgements).
 * droppedFrames counts pending subscriber deliveries replaced or discarded on unsubscribe;
 * replacedLatestFrames counts global latest-slot replacements separately. A publication
 * may replace multiple subscriber deliveries. Latest slots survive subscriber cancellation.
 */
class LatestPreviewHub(keys: Set<PreviewKey>, private val maxPayloadBytes: Int, private val maxSubscribers: Int) {
    private class Subscriber(val keys: Set<PreviewKey>) {
        val pending = linkedMapOf<PreviewKey, PreviewFrame>()
        val wakeup = Channel<Unit>(Channel.CONFLATED)
    }

    private val lock = Any()
    private val allowedKeys = keys.toSet()
    private val latest = linkedMapOf<PreviewKey, PreviewFrame>()
    private val subscribers = mutableSetOf<Subscriber>()
    private var published = 0L
    private var delivered = 0L
    private var dropped = 0L
    private var replacedLatest = 0L
    private var closed = false

    init {
        require(allowedKeys.isNotEmpty() && allowedKeys.all { it.deviceId.isNotBlank() })
        require(maxPayloadBytes > 0 && maxSubscribers > 0)
    }

    fun publish(frame: PreviewFrame): Unit = synchronized(lock) {
        check(!closed) { "Preview hub is closed" }
        require(frame.key in allowedKeys) { "Unknown preview key" }
        require(frame.payloadSize <= maxPayloadBytes) { "Preview payload exceeds configured byte limit" }
        published++
        if (latest.remove(frame.key) != null) replacedLatest++
        latest[frame.key] = frame
        for (subscriber in subscribers) {
            if (frame.key in subscriber.keys) {
                if (subscriber.pending.remove(frame.key) != null) dropped++
                subscriber.pending[frame.key] = frame
                subscriber.wakeup.trySend(Unit)
            }
        }
    }

    fun latest(key: PreviewKey): PreviewFrame? = synchronized(lock) {
        require(key in allowedKeys) { "Unknown preview key" }
        latest[key]
    }

    fun frames(keys: Set<PreviewKey>): Flow<PreviewFrame> {
        val requested = keys.toSet()
        require(requested.isNotEmpty() && allowedKeys.containsAll(requested)) { "Unknown or empty preview keys" }
        return flow {
            val subscriber = synchronized(lock) {
                if (closed) return@flow
                check(subscribers.size < maxSubscribers) { "Preview subscriber limit reached" }
                Subscriber(requested).also { subscriber ->
                    latest.filterKeys { it in requested }.forEach { (key, frame) -> subscriber.pending[key] = frame }
                    subscribers.add(subscriber)
                    if (subscriber.pending.isNotEmpty()) subscriber.wakeup.trySend(Unit)
                }
            }
            try {
                while (true) {
                    // Even an Unconfined collector must resume away from trySend's publisher.
                    // Only the wakeup crosses dispatchers: there is no extra frame buffer.
                    if (withContext(Dispatchers.Default) { subscriber.wakeup.receiveCatching() }.isClosed) return@flow
                    while (true) {
                        val frame = synchronized(lock) {
                            subscriber.pending.keys.lastOrNull()?.let { key ->
                                subscriber.pending.remove(key).also { delivered++ }
                            }
                        } ?: break
                        emit(frame)
                    }
                }
            } finally {
                synchronized(lock) {
                    subscribers.remove(subscriber)
                    dropped += subscriber.pending.size
                    subscriber.pending.clear()
                    subscriber.wakeup.close()
                }
            }
        }
    }

    /** Called after the producer stops. In-flight consumer frames remain consumer-owned. */
    fun close(): Unit = synchronized(lock) {
        if (closed) return@synchronized
        closed = true
        latest.clear()
        for (subscriber in subscribers) {
            dropped += subscriber.pending.size
            subscriber.pending.clear()
            subscriber.wakeup.close()
        }
        subscribers.clear()
    }

    val metrics: PreviewHubMetrics get() = synchronized(lock) {
        PreviewHubMetrics(published, delivered, dropped, replacedLatest, latest.size, subscribers.sumOf { it.pending.size }, subscribers.size)
    }
}
