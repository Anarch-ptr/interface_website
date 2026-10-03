package com.filamentvision.backend.input.runtime

import com.filamentvision.backend.input.profile.*

enum class RaspberryPiUdpApplyOutcome { APPLIED, CONFLICT, INVALID }
data class RaspberryPiUdpApplyResult(val outcome: RaspberryPiUdpApplyOutcome, val revision: Long? = null)

class RaspberryPiUdpInputConfigurationService(
    private val repository: RaspberryPiUdpInputProfileRepository,
    private val router: RaspberryPiUdpInputRouter,
) {
    suspend fun save(expectedSavedRevision: Long, profile: RaspberryPiUdpInputProfile): ProfileRevision = router.serialized {
        val validation = RaspberryPiUdpInputProfileValidator().validate(profile)
        require(validation.isValid) { validation.errors.joinToString { "${it.path}: ${it.message}" } }
        repository.save(expectedSavedRevision, profile)
    }

    suspend fun apply(revision: Long): RaspberryPiUdpApplyResult = router.serialized {
        val state = repository.state()
        check(state.applied.revision == router.appliedRevision && state.applied.profile == router.effectiveProfile) {
            "UDP_APPLIED_STATE_MISMATCH"
        }
        if (revision == state.applied.revision) {
            return@serialized RaspberryPiUdpApplyResult(RaspberryPiUdpApplyOutcome.APPLIED, revision)
        }
        if (revision != state.saved.revision) return@serialized RaspberryPiUdpApplyResult(RaspberryPiUdpApplyOutcome.CONFLICT)
        if (!RaspberryPiUdpInputProfileValidator().validate(state.saved.profile).isValid) {
            return@serialized RaspberryPiUdpApplyResult(RaspberryPiUdpApplyOutcome.INVALID)
        }
        val applied = router.applySerialized(revision, state.saved.profile) {
            repository.markApplied(state.applied.revision, revision)
        }
        RaspberryPiUdpApplyResult(if (applied) RaspberryPiUdpApplyOutcome.APPLIED else RaspberryPiUdpApplyOutcome.CONFLICT,
            revision.takeIf { applied })
    }
}
