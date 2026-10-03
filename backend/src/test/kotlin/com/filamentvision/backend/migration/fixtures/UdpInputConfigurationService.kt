package com.filamentvision.backend.migration.fixtures

import com.filamentvision.backend.input.runtime.UdpApplyOutcome

import com.filamentvision.backend.input.profile.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class UdpApplyResult(val outcome: UdpApplyOutcome, val revision: Long? = null)

class UdpInputConfigurationService(
    private val repository: UdpInputProfileRepository,
    private val router: UdpInputRouter,
) {
    private val serialization = Mutex()

    suspend fun save(profiles: UdpInputProfileSet): RevisionedUdpInputProfiles = serialization.withLock {
        val validation = UdpInputProfileValidator().validate(profiles)
        require(validation.isValid) { validation.errors.joinToString { "${it.path}: ${it.message}" } }
        repository.save(profiles)
    }

    suspend fun apply(revision: Long, expectedSavedRevision: Long): UdpApplyResult = serialization.withLock {
        val saved = repository.get(revision) ?: return@withLock UdpApplyResult(UdpApplyOutcome.NOT_FOUND)
        val validation = UdpInputProfileValidator().validate(saved.profiles)
        if (!validation.isValid) return@withLock UdpApplyResult(UdpApplyOutcome.INVALID)
        val token = router.prepareAndSwap(revision, saved.profiles)
        try {
            if (!repository.markApplied(revision, expectedSavedRevision)) {
                token.rollback()
                return@withLock UdpApplyResult(UdpApplyOutcome.CONFLICT)
            }
            token.commit()
            UdpApplyResult(UdpApplyOutcome.APPLIED, revision)
        } catch (error: Throwable) {
            token.rollback()
            throw error
        }
    }
}
