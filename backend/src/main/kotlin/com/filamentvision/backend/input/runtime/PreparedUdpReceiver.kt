package com.filamentvision.backend.input.runtime

import com.filamentvision.backend.input.transport.UdpReceiver
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A bound candidate socket whose receive loop is invisible until [commit]. */
class PreparedUdpReceiver private constructor(
    internal val receiver: UdpReceiver,
    private val onReleased: () -> Unit,
) : AutoCloseable {
    private val lifecycle = Mutex()
    private var closed = false
    private var committed = false

    val isCommitted: Boolean get() = committed

    suspend fun commit() = lifecycle.withLock {
        check(!closed) { "Prepared UDP receiver is closed" }
        if (committed) return@withLock
        receiver.commit()
        committed = true
        onReleased()
    }

    suspend fun closePrepared() = lifecycle.withLock {
        if (closed) return@withLock
        closed = true
        receiver.stop()
        onReleased()
    }

    override fun close() {
        kotlinx.coroutines.runBlocking { closePrepared() }
    }

    companion object {
        suspend fun prepare(receiver: UdpReceiver, onReleased: () -> Unit = {}): PreparedUdpReceiver {
            receiver.prepare()
            return PreparedUdpReceiver(receiver, onReleased)
        }
    }
}
