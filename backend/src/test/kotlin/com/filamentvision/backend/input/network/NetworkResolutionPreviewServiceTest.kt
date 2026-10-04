package com.filamentvision.backend.input.network

import com.filamentvision.backend.input.profile.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkResolutionPreviewServiceTest {
    @Test
    fun disabledPreviewIsInactiveAndNeverResolvesOrMutatesRuntimeObservers() = runBlocking {
        var resolutions = 0
        var runtimeMutations = 0
        val service = service(resolver = Ipv4HostResolver { resolutions++; resolution(it, "10.0.0.2") })

        val preview = service.preview("device", profile(enabled = false), null) { runtimeMutations++ }

        assertEquals(NetworkConsistencyStatus.HEALTHY, preview.consistencyStatus)
        assertEquals(ResolutionStatus.NOT_REQUIRED, preview.destinationStatus)
        assertEquals(ResolutionStatus.NOT_REQUIRED, preview.sourceStatus)
        assertNull(preview.candidatePlan)
        assertEquals(0, resolutions)
        assertEquals(0, runtimeMutations)
    }

    @Test
    fun timestampsRetainIndependentDestinationSourceAndPlanProvenance() = runBlocking {
        var timestamp = 10L
        val service = service(
            resolver = Ipv4HostResolver { host -> timestampedResolution(host, timestamp++, if (host.startsWith("backend")) "10.0.0.2" else "10.0.0.20") },
            clock = { 99L },
        )
        val preview = service.preview("device", profile(), null)
        val candidate = requireNotNull(preview.candidatePlan)
        assertEquals(10L, candidate.destinationResolutionTime)
        assertEquals(11L, candidate.sourceResolutionTime)
        assertEquals(99L, candidate.planCreatedAtEpochMillis)
        assertNull(candidate.appliedAtEpochMillis)
    }

    @Test
    fun comparesCurrentResolutionToAppliedPlanAndRequiresSameRevisionReapplyWhenDnsChanges() = runBlocking {
        var sourceAddress = "10.0.0.20"
        val service = service(resolver = Ipv4HostResolver { host ->
            resolution(host, if (host.startsWith("backend")) "10.0.0.2" else sourceAddress)
        })
        val first = requireNotNull(service.preview("device", profile(), null).candidatePlan)
        val applied = first.applied(appliedAt = 120L, generation = 4)
        val unchanged = service.preview("device", profile(), applied)
        assertEquals(NetworkConsistencyStatus.HEALTHY, unchanged.consistencyStatus)
        assertFalse(unchanged.reapplyRequired)

        sourceAddress = "10.0.0.42"
        val changed = service.preview("device", profile(), applied)
        assertEquals(NetworkConsistencyStatus.REAPPLY_REQUIRED, changed.consistencyStatus)
        assertTrue(changed.reapplyRequired)
        assertTrue("RESOLUTION_CHANGED" in changed.differences)
        assertEquals(4L, changed.appliedResolutionGeneration)
    }

    @Test
    fun ambiguousDestinationProducesTypedPreviewWithoutCandidatePlan() = runBlocking {
        val service = service(resolver = Ipv4HostResolver { host ->
            if (host.startsWith("backend")) resolution(host, "10.0.0.2", "10.0.0.3") else resolution(host, "10.0.0.20")
        })
        val preview = service.preview("device", profile(), null)
        assertNull(preview.candidatePlan)
        assertEquals("DESTINATION_AMBIGUOUS", preview.errorCode)
        assertEquals(ResolutionStatus.AMBIGUOUS, preview.destinationStatus)
    }

    @Test
    fun previewKeepsOneActiveAndOneConflatedQueuedRequestAndOnlyLatestSnapshot() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val service = service(
            resolver = Ipv4HostResolver { host ->
                if (calls.incrementAndGet() == 1) {
                    entered.countDown()
                    release.await(2, TimeUnit.SECONDS)
                }
                resolution(host, if (host.startsWith("backend")) "10.0.0.2" else "10.0.0.${calls.get() + 20}")
            },
            minimumStartIntervalMillis = 0,
        )
        val first = async(Dispatchers.Default) { service.preview("device", profile(), null) }
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        val replaced = async(Dispatchers.Default) { service.preview("device", profile(sourceHost = "old.local"), null) }
        val latest = async(Dispatchers.Default) { service.preview("device", profile(sourceHost = "latest.local"), null) }
        release.countDown()
        first.await()
        val replacedResult = replaced.await()
        val latestResult = latest.await()
        assertTrue(replacedResult.superseded)
        assertFalse(latestResult.superseded)
        assertEquals("latest.local", service.latest("device")?.configuredSource)
        assertTrue(service.maximumActivePreviewCount <= 1)
        assertTrue(service.maximumQueuedPreviewCount <= 1)
    }

    private fun service(
        resolver: Ipv4HostResolver,
        clock: () -> Long = { 50L },
        minimumStartIntervalMillis: Long = 0,
    ) = NetworkResolutionPreviewService(
        resolver,
        EffectiveUdpNetworkPlanner(
            Ipv4NetworkInterfaceProvider { listOf(entry("wifi", "10.0.0.2")) },
            Ipv4RouteProbe { "10.0.0.2" },
        ),
        clock,
        minimumStartIntervalMillis,
    )

    private fun profile(enabled: Boolean = true, sourceHost: String = "pi.local") = PreviewProfile(
        enabled,
        UdpNetworkProfile(
            BindProfile.Auto,
            AdvertisedDestinationProfile.Manual("backend.local"),
            SourceFilterProfile.IpOrHost(sourceHost),
            5_000,
        ),
        savedRevision = 12,
        appliedRevision = 12,
    )

    private fun resolution(host: String, vararg addresses: String) =
        timestampedResolution(host, 1L, *addresses)

    private fun timestampedResolution(host: String, timestamp: Long, vararg addresses: String) =
        HostResolution(host, linkedSetOf(*addresses), HostResolutionStatus.RESOLVED, timestamp)

    private fun entry(id: String, ipv4: String) =
        Ipv4NetworkInterfaceEntry(id, id, ipv4, true, false, false, true, 1)
}
