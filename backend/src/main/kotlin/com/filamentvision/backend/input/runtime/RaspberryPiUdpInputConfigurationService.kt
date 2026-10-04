package com.filamentvision.backend.input.runtime

import com.filamentvision.backend.input.profile.*
import com.filamentvision.backend.input.network.*

enum class RaspberryPiUdpApplyOutcome { APPLIED, CONFLICT, INVALID }
data class RaspberryPiUdpApplyResult(
    val outcome: RaspberryPiUdpApplyOutcome,
    val revision: Long? = null,
    val resolutionGeneration: Long? = null,
    val errorCode: String? = null,
)

class RaspberryPiUdpInputConfigurationService(
    private val repository: RaspberryPiUdpInputProfileRepository,
    private val router: RaspberryPiUdpInputRouter,
    private val resolver: Ipv4HostResolver = HostResolver(),
    private val planner: EffectiveUdpNetworkPlanner = EffectiveUdpNetworkPlanner(),
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {
    private val previewService = NetworkResolutionPreviewService(resolver, planner, clockMillis)
    suspend fun save(expectedSavedRevision: Long, profile: RaspberryPiUdpInputProfile): ProfileRevision = router.serializedSave {
        val validation = RaspberryPiUdpInputProfileValidator().validate(profile)
        require(validation.isValid) { validation.errors.joinToString { "${it.path}: ${it.message}" } }
        repository.save(expectedSavedRevision, profile)
    }

    suspend fun apply(
        revision: Long,
        forceReapply: Boolean = false,
        expectedAppliedRevision: Long? = null,
        expectedResolutionGeneration: Long? = null,
    ): RaspberryPiUdpApplyResult = router.serialized {
        val state = repository.state()
        check(state.applied.revision == router.appliedRevision && state.applied.profile == router.effectiveProfile) {
            "UDP_APPLIED_STATE_MISMATCH"
        }
        if (router.effectiveNetworkPlan != null) {
            check(state.resolutionGeneration == router.resolutionGeneration) { "UDP_APPLIED_GENERATION_MISMATCH" }
        }
        if (expectedAppliedRevision != null && expectedAppliedRevision != state.applied.revision ||
            expectedResolutionGeneration != null && expectedResolutionGeneration != state.resolutionGeneration
        ) {
            return@serialized RaspberryPiUdpApplyResult(RaspberryPiUdpApplyOutcome.CONFLICT)
        }
        if (revision == state.applied.revision && !forceReapply) {
            return@serialized RaspberryPiUdpApplyResult(
                RaspberryPiUdpApplyOutcome.APPLIED,
                revision,
                state.resolutionGeneration,
            )
        }
        if (revision != state.saved.revision) return@serialized RaspberryPiUdpApplyResult(RaspberryPiUdpApplyOutcome.CONFLICT)
        if (!RaspberryPiUdpInputProfileValidator().validate(state.saved.profile).isValid) {
            return@serialized RaspberryPiUdpApplyResult(RaspberryPiUdpApplyOutcome.INVALID)
        }
        // Compatibility is temporary: startup wiring migrates to persisted plans in Task 13.
        if (router.effectiveNetworkPlan == null && !forceReapply) {
            val applied = router.applySerialized(revision, state.saved.profile) {
                repository.markApplied(state.applied.revision, revision)
            }
            return@serialized RaspberryPiUdpApplyResult(
                if (applied) RaspberryPiUdpApplyOutcome.APPLIED else RaspberryPiUdpApplyOutcome.CONFLICT,
                revision.takeIf { applied },
                repository.state().resolutionGeneration.takeIf { applied },
            )
        }

        val generation = state.resolutionGeneration + 1
        val plan = buildPlan(state.saved.profile, generation)
            ?: return@serialized RaspberryPiUdpApplyResult(
                RaspberryPiUdpApplyOutcome.INVALID,
                errorCode = lastPlanError,
            )
        val applied = router.applyResolvedSerialized(revision, state.saved.profile, plan) {
            repository.markApplied(state.applied.revision, state.resolutionGeneration, revision, plan)
        }
        RaspberryPiUdpApplyResult(
            if (applied) RaspberryPiUdpApplyOutcome.APPLIED else RaspberryPiUdpApplyOutcome.CONFLICT,
            revision.takeIf { applied },
            generation.takeIf { applied },
        )
    }

    @Volatile private var lastPlanError: String? = null

    private suspend fun buildPlan(profile: RaspberryPiUdpInputProfile, generation: Long): EffectiveUdpNetworkPlan? {
        val now = clockMillis()
        if (!profile.enabled) {
            lastPlanError = null
            return EffectiveUdpNetworkPlan.inactive(now).applied(now, generation)
        }
        val destination = when (val value = profile.network.destination) {
            AdvertisedDestinationProfile.Auto -> null
            is AdvertisedDestinationProfile.Manual -> resolver.resolveIpv4(value.hostOrIpv4)
        }
        val source = when (val value = profile.network.sourceFilter) {
            SourceFilterProfile.Any -> null
            is SourceFilterProfile.IpOrHost -> resolver.resolveIpv4(value.hostOrIpv4)
        }
        return when (val result = planner.plan(profile.network, destination, source)) {
            is NetworkPlanResult.Success -> {
                lastPlanError = null
                EffectiveUdpNetworkPlan.candidate(result.candidate, now).applied(now, generation)
            }
            is NetworkPlanResult.Failure -> {
                lastPlanError = result.code
                null
            }
        }
    }

    suspend fun preview(deviceId: String, profile: RaspberryPiUdpInputProfile, state: RaspberryPiUdpProfileState) =
        previewService.preview(deviceId,
            PreviewProfile(profile.enabled, profile.network, state.saved.revision, state.applied.revision),
            state.appliedPlan)
}
