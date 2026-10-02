package com.filamentvision.backend.input.transport

import java.net.InetSocketAddress

/** A receive-buffer lease that is valid only during the receiver callback. */
class BorrowedDatagramBytes internal constructor(
    private val backing: ByteArray,
    val size: Int,
) {
    @Volatile
    private var active = true

    init {
        require(size in 0..backing.size)
    }

    fun copyOwned(): ByteArray {
        check(active) { "UDP receive-buffer lease has expired" }
        return backing.copyOf(size)
    }

    fun <T> withBytes(block: (bytes: ByteArray, size: Int) -> T): T {
        check(active) { "UDP receive-buffer lease has expired" }
        return block(backing, size)
    }

    internal fun invalidate() {
        active = false
    }
}

class ReceivedDatagram internal constructor(
    val source: InetSocketAddress,
    val receivedAtMonotonicNanos: Long,
    val receivedAtEpochMillis: Long,
    val bytes: BorrowedDatagramBytes,
) {
    val size: Int get() = bytes.size
}
