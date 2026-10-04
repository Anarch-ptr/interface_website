package com.filamentvision.backend.input.profile

import com.filamentvision.backend.input.network.EffectiveUdpNetworkPlan
import com.filamentvision.backend.input.network.NetworkConsistencyStatus

data class ProfileRevision(val revision: Long, val profile: RaspberryPiUdpInputProfile)

data class RaspberryPiUdpProfileState(
    val saved: ProfileRevision,
    val applied: ProfileRevision,
    val appliedPlan: EffectiveUdpNetworkPlan? = null,
    val resolutionGeneration: Long = appliedPlan?.resolutionGeneration ?: 0,
    val networkConsistencyStatus: NetworkConsistencyStatus = when {
        appliedPlan != null -> NetworkConsistencyStatus.HEALTHY
        applied.profile.enabled -> NetworkConsistencyStatus.LEGACY_PLAN_REAPPLY_REQUIRED
        else -> NetworkConsistencyStatus.HEALTHY
    },
)

interface RaspberryPiUdpInputProfileRepository {
    suspend fun save(expectedSavedRevision: Long, profile: RaspberryPiUdpInputProfile): ProfileRevision
    /** Atomically commits the applied pointer, immutable plan and next resolution generation. */
    suspend fun markApplied(
        expectedAppliedRevision: Long,
        expectedResolutionGeneration: Long,
        revision: Long,
        plan: EffectiveUdpNetworkPlan,
    ): Boolean = throw UnsupportedOperationException("Effective-plan CAS is not implemented")

    /** Compatibility boundary retained until the runtime is migrated in Task 11. */
    suspend fun markApplied(expectedAppliedRevision: Long, revision: Long): Boolean {
        val current = state()
        val generation = current.resolutionGeneration + 1
        val compatibilityPlan = EffectiveUdpNetworkPlan.inactive(System.currentTimeMillis())
            .applied(System.currentTimeMillis(), generation)
        return markApplied(expectedAppliedRevision, current.resolutionGeneration, revision, compatibilityPlan)
    }
    suspend fun state(): RaspberryPiUdpProfileState
}
