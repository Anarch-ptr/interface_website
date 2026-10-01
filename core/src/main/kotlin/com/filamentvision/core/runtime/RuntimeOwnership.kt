package com.filamentvision.core.runtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** All commands, acceptance and timer writes share this single ownership lock.
 * The reservation survives STOPPING until source termination and durable finalization finish.
 */
internal class RuntimeOwnership(private val cacheCapacity: Int) {
    init { require(cacheCapacity > 0) }
    private val mutex = Mutex()
    private val results = linkedMapOf<Pair<String, String>, Any>()
    var generation = 0L
    var current: Reservation? = null
    var closed = false
    val cachedCount get() = results.size

    suspend fun <T> transition(block: suspend () -> T): T = mutex.withLock { block() }

    @Suppress("UNCHECKED_CAST")
    fun <T> cached(kind: String, id: String): CompletableDeferred<T>? =
        results[kind to id] as CompletableDeferred<T>?

    fun <T> remember(kind: String, id: String, result: CompletableDeferred<T>) {
        results[kind to id] = result
        while (results.size > cacheCapacity) results.remove(results.keys.first())
    }

    class Reservation(val session: RuntimeSession) {
        var accepting = false
        var finalizing = false
        var created = false
        lateinit var worker: Job
        val started = CompletableDeferred<StartResult>()
        val finished = CompletableDeferred<StopResult>()
    }
}
