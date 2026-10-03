package com.filamentvision.backend.input.runtime

import java.util.concurrent.atomic.AtomicBoolean

/** One decoded image may be owned across processing and source restarts. */
class DecodedFramePermit {
    private val held = AtomicBoolean(false)

    fun tryAcquire(): DecodedFrameLease? =
        if (held.compareAndSet(false, true)) DecodedFrameLease { held.set(false) } else null
}

/** The owner releases once; a stale close cannot release a subsequent acquisition. */
class DecodedFrameLease internal constructor(private val release: () -> Unit) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) release()
    }
}
