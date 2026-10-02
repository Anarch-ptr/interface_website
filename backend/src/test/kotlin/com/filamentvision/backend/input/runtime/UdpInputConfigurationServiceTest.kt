package com.filamentvision.backend.input.runtime

import com.filamentvision.backend.input.profile.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class UdpInputConfigurationServiceTest {
    @Test
    fun saveAndApplyKeepSavedAndAppliedRevisionsIndependent() = runBlocking {
        val initial = profiles()
        val repository = InMemoryUdpInputProfileRepository(initial)
        val router = UdpInputRouter(1, initial, UdpCameraRuntimeSwapper { _, _, _, _ -> })
        val service = UdpInputConfigurationService(repository, router)
        val revision2 = service.save(initial.copy(cameraA = initial.cameraA.copy(payload = PayloadProfile.Gray8(2, 2))))
        assertEquals(2, revision2.revision)
        assertEquals(1, repository.appliedRevision())
        assertEquals(UdpApplyOutcome.APPLIED, service.apply(2, 2).outcome)
        assertEquals(2, repository.appliedRevision())
        assertEquals(2, router.appliedRevision)
    }

    @Test
    fun casFailureAfterRuntimeSwapCompensatesToOldTriple() = runBlocking {
        val initial = profiles()
        val repository = InMemoryUdpInputProfileRepository(initial)
        val swaps = mutableListOf<Int>()
        val router = UdpInputRouter(1, initial, UdpCameraRuntimeSwapper { _, _, next, _ -> swaps += next.listenPort })
        val service = UdpInputConfigurationService(repository, router)
        service.save(initial.copy(cameraA = initial.cameraA.copy(listenPort = 46_010)))
        repository.forceMarkAppliedResult = false
        assertEquals(UdpApplyOutcome.CONFLICT, service.apply(2, 2).outcome)
        assertEquals(1, router.appliedRevision)
        assertEquals(initial, router.receiverEffectiveProfiles)
        assertEquals(1, repository.appliedRevision())
        assertEquals(listOf(46_010, 46_001), swaps)
    }

    @Test
    fun persistenceExceptionAfterSwapAlsoRollsBackRuntimeAndReceiver() = runBlocking {
        val initial = profiles()
        val repository = InMemoryUdpInputProfileRepository(initial)
        val router = UdpInputRouter(1, initial, UdpCameraRuntimeSwapper { _, _, _, _ -> })
        val service = UdpInputConfigurationService(repository, router)
        service.save(initial.copy(cameraA = initial.cameraA.copy(listenPort = 46_010)))
        repository.markAppliedFailure = IllegalStateException("disk failed")
        assertThrows(IllegalStateException::class.java) { runBlocking { service.apply(2, 2) } }
        assertEquals(1, router.appliedRevision)
        assertEquals(initial, router.receiverEffectiveProfiles)
        assertEquals(1, repository.appliedRevision())
    }

    @Test
    fun concurrentSavesAndApplyUseOneSerializationBoundary() = runBlocking {
        val initial = profiles()
        val repository = InMemoryUdpInputProfileRepository(initial)
        val router = UdpInputRouter(1, initial, UdpCameraRuntimeSwapper { _, _, _, _ -> })
        val service = UdpInputConfigurationService(repository, router)
        val revisions = (1..10).map { n -> async { service.save(initial.copy(cameraA = initial.cameraA.copy(listenPort = 47_000 + n))).revision } }.awaitAll()
        assertEquals((2L..11L).toSet(), revisions.toSet())
        assertEquals(1, repository.appliedRevision())
    }
}
