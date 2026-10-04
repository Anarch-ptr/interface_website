package com.filamentvision.backend.input.runtime

import com.filamentvision.backend.input.profile.RaspberryPiUdpInputProfile
import com.filamentvision.backend.input.network.EffectiveUdpNetworkPlan
import com.filamentvision.backend.input.network.NetworkConsistencyStatus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Compensation failed: further configuration commands must fail closed. */
class RaspberryPiUdpConsistencyException(cause: Throwable, compensation: Throwable? = null) :
    IllegalStateException("UDP_APPLY_CONSISTENCY_FAILURE", cause) {
    init { compensation?.let(::addSuppressed) }
}

/** One device boundary, shared by every configuration service using this Runtime-owned source. */
class RaspberryPiUdpInputRouter(
    initialRevision: Long,
    private val source: RaspberryPiUdpInputSource,
    initialPlan: EffectiveUdpNetworkPlan? = source.effectiveNetworkPlan,
) {
    private val serialization = Mutex()
    @Volatile var appliedRevision: Long = initialRevision
        private set
    val effectiveProfile: RaspberryPiUdpInputProfile get() = source.effectiveProfile
    @Volatile private var appliedPlan: EffectiveUdpNetworkPlan? = initialPlan?.immutableCopy()
    val effectiveNetworkPlan: EffectiveUdpNetworkPlan? get() = appliedPlan
    val resolutionGeneration: Long get() = appliedPlan?.resolutionGeneration ?: 0
    val networkConsistency: NetworkConsistencyStatus get() = source.networkConsistency

    internal suspend fun <T> serialized(action: suspend () -> T): T = serialization.withLock {
        source.checkConsistency()
        action()
    }

    internal suspend fun <T> serializedSave(action: suspend () -> T): T = serialization.withLock { action() }

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

    internal suspend fun applyResolvedSerialized(
        revision: Long,
        candidate: RaspberryPiUdpInputProfile,
        plan: EffectiveUdpNetworkPlan,
        persist: suspend () -> Boolean,
    ): Boolean {
        val committed = source.applyResolvedConfiguration(candidate, plan, persist)
        if (committed) {
            appliedRevision = revision
            appliedPlan = plan.immutableCopy()
        }
        return committed
    }
}
