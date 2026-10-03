package com.filamentvision.backend.input.runtime

enum class UdpOperationalStatus {
    DISABLED, UDP_READY, WAITING_FOR_RASPBERRY_PI, RECEIVING,
    NO_PACKETS_RECENTLY, SOURCE_IP_MISMATCH, BIND_FAILED,
}

data class UdpOperationalStatusSnapshot(val status: UdpOperationalStatus, val detail: String? = null)

/** Fixed-size activity state. All observations and snapshots use the same monitor. */
internal class UdpActivity {
    private var running = false
    private var boundAt: Long? = null
    private var acceptedAt: Long? = null
    private var rejectedAt: Long? = null
    private var newestRejected = false
    private var failure: String? = null

    @Synchronized fun reset(enabled: Boolean) {
        running = enabled; boundAt = null; acceptedAt = null; rejectedAt = null
        newestRejected = false; failure = null
    }
    @Synchronized fun bound(now: Long) { boundAt = now }
    @Synchronized fun accepted(now: Long) { acceptedAt = now; newestRejected = false }
    @Synchronized fun rejected(now: Long) { rejectedAt = now; newestRejected = true }
    @Synchronized fun failed(code: String) { failure = code }

    @Synchronized fun snapshot(now: Long): UdpOperationalStatusSnapshot {
        failure?.let { return UdpOperationalStatusSnapshot(UdpOperationalStatus.BIND_FAILED, it) }
        val bound = boundAt
        val status = when {
            !running || bound == null -> UdpOperationalStatus.DISABLED
            newestRejected && rejectedAt?.let { now - it < RECENT_NANOS } == true -> UdpOperationalStatus.SOURCE_IP_MISMATCH
            acceptedAt?.let { now - it < RECENT_NANOS } == true -> UdpOperationalStatus.RECEIVING
            acceptedAt != null -> UdpOperationalStatus.NO_PACKETS_RECENTLY
            now - bound < GRACE_NANOS -> UdpOperationalStatus.UDP_READY
            else -> UdpOperationalStatus.WAITING_FOR_RASPBERRY_PI
        }
        return UdpOperationalStatusSnapshot(status)
    }

    private companion object {
        const val GRACE_NANOS = 2_000_000_000L
        const val RECENT_NANOS = 3_000_000_000L
    }
}
