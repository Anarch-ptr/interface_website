package com.filamentvision.core.frame

import java.util.concurrent.atomic.AtomicBoolean

/** A frame's owner closes its lease after all consumers have finished with its pixels. */
class FrameLease(private val onRelease: () -> Unit = {}) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) onRelease()
    }
}
