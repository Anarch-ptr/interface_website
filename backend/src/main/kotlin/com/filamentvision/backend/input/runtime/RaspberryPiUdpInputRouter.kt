package com.filamentvision.backend.input.runtime

import com.filamentvision.backend.input.profile.RaspberryPiUdpInputProfile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Compensation failed: further configuration commands must fail closed. */
class RaspberryPiUdpConsistencyException(cause: Throwable, compensation: Throwable) :
    IllegalStateException("UDP_APPLY_CONSISTENCY_FAILURE", cause) {
    init { addSuppressed(compensation) }
}

/** One device boundary, shared by every configuration service using this Runtime-owned source. */
class RaspberryPiUdpInputRouter(
    initialRevision: Long,
    private val source: RaspberryPiUdpInputSource,
) {
    private val serialization = Mutex()
    @Volatile var appliedRevision: Long = initialRevision
        private set
    val effectiveProfile: RaspberryPiUdpInputProfile get() = source.effectiveProfile

    internal suspend fun <T> serialized(action: suspend () -> T): T = serialization.withLock {
        source.checkConsistency()
        action()
    }

    suspend fun apply(revision: Long, candidate: RaspberryPiUdpInputProfile, persist: suspend () -> Boolean): Boolean =
        serialized { applySerialized(revision, candidate, persist) }

    internal suspend fun applySerialized(
        revision: Long,
        candidate: RaspberryPiUdpInputProfile,
        persist: suspend () -> Boolean,
    ): Boolean {
        if (revision == appliedRevision && candidate == effectiveProfile) return true
        val previousRevision = appliedRevision
        var committed = false
        try {
            committed = source.reconfigure(candidate) {
                // The entire runtime/receiver swap is complete before the repository CAS.
                appliedRevision = revision
                persist()
            }
            return committed
        } finally {
            if (!committed) appliedRevision = previousRevision
        }
    }
}
