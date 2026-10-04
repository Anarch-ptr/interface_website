package com.filamentvision.backend.input.network

import java.util.Collections
import java.util.LinkedHashSet

enum class ResolutionStatus { NOT_REQUIRED, RESOLVED, FAILED, AMBIGUOUS }

enum class NetworkConsistencyStatus {
    HEALTHY,
    REAPPLY_REQUIRED,
    LEGACY_PLAN_REAPPLY_REQUIRED,
    NETWORK_CONFIGURATION_INCONSISTENT,
}

data class EffectiveUdpNetworkPlan(
    val actualBindIpv4: String,
    val actualBindInterfaceId: String?,
    val configuredDestination: String?,
    val resolvedDestinationIpv4: String?,
    val configuredSource: String?,
    val resolvedSourceIpv4s: Set<String>,
    val destinationStatus: ResolutionStatus,
    val sourceStatus: ResolutionStatus,
    val destinationResolutionTime: Long?,
    val sourceResolutionTime: Long?,
    val planCreatedAtEpochMillis: Long,
    val appliedAtEpochMillis: Long?,
    val resolutionGeneration: Long,
) {
    internal fun immutableCopy() = copy(resolvedSourceIpv4s = immutableIpv4Set(resolvedSourceIpv4s))

    fun applied(appliedAt: Long, generation: Long) = copy(
        resolvedSourceIpv4s = immutableIpv4Set(resolvedSourceIpv4s),
        appliedAtEpochMillis = appliedAt,
        resolutionGeneration = generation,
    )

    fun hasSameResolution(other: EffectiveUdpNetworkPlan): Boolean =
        actualBindIpv4 == other.actualBindIpv4 &&
            actualBindInterfaceId == other.actualBindInterfaceId &&
            configuredDestination == other.configuredDestination &&
            resolvedDestinationIpv4 == other.resolvedDestinationIpv4 &&
            configuredSource == other.configuredSource &&
            resolvedSourceIpv4s == other.resolvedSourceIpv4s &&
            destinationStatus == other.destinationStatus && sourceStatus == other.sourceStatus

    companion object {
        fun candidate(
            candidate: NetworkPlanCandidate,
            createdAtEpochMillis: Long,
            generation: Long = 0,
        ) = EffectiveUdpNetworkPlan(
            candidate.actualBindIpv4,
            candidate.actualBindInterfaceId,
            candidate.configuredDestination,
            candidate.resolvedDestinationIpv4,
            candidate.configuredSource,
            immutableIpv4Set(candidate.resolvedSourceIpv4s),
            ResolutionStatus.RESOLVED,
            if (candidate.configuredSource == null) ResolutionStatus.NOT_REQUIRED else ResolutionStatus.RESOLVED,
            candidate.destinationResolution?.resolvedAtEpochMillis,
            candidate.sourceResolution?.resolvedAtEpochMillis,
            createdAtEpochMillis,
            null,
            generation,
        )

        fun inactive(createdAtEpochMillis: Long, generation: Long = 0) = EffectiveUdpNetworkPlan(
            "0.0.0.0", null, null, null, null, emptySet(),
            ResolutionStatus.NOT_REQUIRED, ResolutionStatus.NOT_REQUIRED,
            null, null, createdAtEpochMillis, null, generation,
        )

        private fun immutableIpv4Set(values: Collection<String>): Set<String> =
            Collections.unmodifiableSet(LinkedHashSet(values))
    }
}

private fun immutableIpv4Set(values: Collection<String>): Set<String> =
    Collections.unmodifiableSet(LinkedHashSet(values))
