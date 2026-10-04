package com.filamentvision.backend.input.runtime

import com.filamentvision.backend.input.transport.UdpReceiver
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A bound candidate socket whose receive loop is invisible until [commit]. */
class PreparedUdpReceiver private constructor(
    private val receiver: UdpReceiver,
    private val onClosed: () -> Unit,
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
    }

    suspend fun closePrepared() = lifecycle.withLock {
        if (closed) return@withLock
        closed = true
        receiver.stop()
        onClosed()
    }

    override fun close() {
        kotlinx.coroutines.runBlocking { closePrepared() }
    }

    companion object {
        suspend fun prepare(receiver: UdpReceiver, onClosed: () -> Unit = {}): PreparedUdpReceiver {
            receiver.prepare()
            return PreparedUdpReceiver(receiver, onClosed)
        }
    }
}
