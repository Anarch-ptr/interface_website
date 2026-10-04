package com.filamentvision.backend.input.runtime

import com.filamentvision.backend.input.network.*
import com.filamentvision.backend.input.profile.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class FlexibleNetworkApplyRollbackTest {
    @Test fun failedSameRevisionResolutionPreservesPlanReceiverAndGeneration() = runBlocking {
        val f = fixture(resolver = Ipv4HostResolver { host ->
            HostResolution(host, emptySet(), HostResolutionStatus.FAILED, 20, "HOSTNAME_RESOLUTION_FAILED")
        })
        try {
            val result = f.service.apply(12, true, 12, 4)
            assertEquals(RaspberryPiUdpApplyOutcome.INVALID, result.outcome)
            assertEquals("SOURCE_RESOLUTION_FAILED", result.errorCode)
            f.assertOldTruth()
        } finally { f.close() }
    }

    @Test fun staleGenerationAndRepositoryFailureDiscardCandidateWithoutPublication() = runBlocking {
        val f = fixture()
        try {
            assertEquals(RaspberryPiUdpApplyOutcome.CONFLICT, f.service.apply(12, true, 12, 3).outcome)
            f.assertOldTruth()

            val storageFailure = IllegalStateException("injected storage failure")
            f.repository.markFailure = storageFailure
            assertSame(storageFailure, failure { f.service.apply(12, true, 12, 4) })
            f.assertOldTruth()
        } finally { f.close() }
    }

    @Test fun concurrentSameRevisionReapplyUsesGenerationCasWithoutLostUpdate() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val f = fixture()
        try {
            f.repository.beforeMark = { entered.complete(Unit); release.await() }
            val first = async { f.service.apply(12, true, 12, 4) }
            entered.await()
            val second = async { f.service.apply(12, true, 12, 4) }
            assertFalse(second.isCompleted)
            release.complete(Unit)
            assertEquals(RaspberryPiUdpApplyOutcome.APPLIED, first.await().outcome)
            assertEquals(RaspberryPiUdpApplyOutcome.CONFLICT, second.await().outcome)
            assertEquals(5L, f.repository.state().resolutionGeneration)
            assertEquals(5L, f.router.resolutionGeneration)
            f.harness.assertSingleOwner()
        } finally { f.close() }
    }

    @Test fun postCasPublicationFailureLatchesFailClosedButSaveRemainsAvailable() = runBlocking {
        val f = fixture()
        try {
            val saved = f.service.save(12, f.profile.copy(listenPort = 46011))
            f.harness.failNextStop = true
            val fatal = failure { f.service.apply(saved.revision, false, 12, 4) }
            assertTrue(fatal is RaspberryPiUdpConsistencyException)
            assertEquals(NetworkConsistencyStatus.NETWORK_CONFIGURATION_INCONSISTENT, f.router.networkConsistency)
            assertEquals(13L, f.repository.state().applied.revision)
            assertEquals(5L, f.repository.state().resolutionGeneration)
            assertEquals(0, f.harness.source.activeReceiverCount)
            assertTrue(failure { f.service.apply(13, true, 13, 5) } is RaspberryPiUdpConsistencyException)
            assertEquals(14L, f.service.save(13, f.profile).revision)
        } finally { f.close() }
    }

    private suspend fun fixture(
        resolver: Ipv4HostResolver = successfulResolver(),
    ): Fixture {
        val profile = task16Profile().withNetwork(task16Profile().network.copy(
            destination = AdvertisedDestinationProfile.Manual("backend.local"),
            sourceFilter = SourceFilterProfile.IpOrHost("filament-pi.local"),
        ))
        val plan = oldPlan()
        val repository = MemoryRepository(ProfileRevision(12, profile), plan)
        val harness = Task16SourceHarness(profile, appliedNetworkPlan = plan, allowPreparedCandidate = true)
        val router = RaspberryPiUdpInputRouter(12, harness.source, plan)
        val service = RaspberryPiUdpInputConfigurationService(repository, router, resolver, planner(), { 30 })
        harness.source.start()
        return Fixture(profile, plan, repository, harness, router, service)
    }

    private data class Fixture(
        val profile: RaspberryPiUdpInputProfile,
        val oldPlan: EffectiveUdpNetworkPlan,
        val repository: MemoryRepository,
        val harness: Task16SourceHarness,
        val router: RaspberryPiUdpInputRouter,
        val service: RaspberryPiUdpInputConfigurationService,
    ) {
        suspend fun assertOldTruth() {
            assertEquals(12L, repository.state().applied.revision)
            assertEquals(4L, repository.state().resolutionGeneration)
            assertEquals(oldPlan, harness.source.effectiveNetworkPlan)
            assertEquals(oldPlan, router.effectiveNetworkPlan)
            harness.assertSingleOwner()
        }

        suspend fun close() = harness.source.stop()
    }

    private class MemoryRepository(
        initial: ProfileRevision,
        initialPlan: EffectiveUdpNetworkPlan,
    ) : RaspberryPiUdpInputProfileRepository {
        private var saved = initial
        private var applied = initial
        private var plan = initialPlan
        var beforeMark: suspend () -> Unit = {}
        var markFailure: Throwable? = null

        override suspend fun save(expectedSavedRevision: Long, profile: RaspberryPiUdpInputProfile): ProfileRevision {
            check(expectedSavedRevision == saved.revision)
            saved = ProfileRevision(saved.revision + 1, profile)
            return saved
        }

        override suspend fun markApplied(
            expectedAppliedRevision: Long,
            expectedResolutionGeneration: Long,
            revision: Long,
            plan: EffectiveUdpNetworkPlan,
        ): Boolean {
            beforeMark()
            markFailure?.let { throw it }
            if (expectedAppliedRevision != applied.revision || expectedResolutionGeneration != this.plan.resolutionGeneration ||
                revision != saved.revision
            ) return false
            applied = saved
            this.plan = plan
            return true
        }

        override suspend fun state() = RaspberryPiUdpProfileState(saved, applied, plan, plan.resolutionGeneration)
    }

    private companion object {
        fun successfulResolver() = Ipv4HostResolver { host -> HostResolution(
            host,
            setOf(if (host == "filament-pi.local") "10.97.199.42" else "192.0.2.20"),
            HostResolutionStatus.RESOLVED,
            20,
        ) }

        fun planner() = EffectiveUdpNetworkPlanner(
            Ipv4NetworkInterfaceProvider {
                listOf(Ipv4NetworkInterfaceEntry("wifi", "Wi-Fi", "192.0.2.20", true, false, false, true, 1))
            },
            Ipv4RouteProbe { "192.0.2.20" },
        )

        fun oldPlan() = EffectiveUdpNetworkPlan(
            "0.0.0.0", null, "backend.local", "192.0.2.20", "filament-pi.local",
            setOf("10.97.199.28"), ResolutionStatus.RESOLVED, ResolutionStatus.RESOLVED,
            10, 10, 10, 10, 4,
        )

        suspend fun failure(block: suspend () -> Unit): Throwable = try {
            block()
            throw AssertionError("Expected failure")
        } catch (error: Throwable) {
            error
        }
    }
}
