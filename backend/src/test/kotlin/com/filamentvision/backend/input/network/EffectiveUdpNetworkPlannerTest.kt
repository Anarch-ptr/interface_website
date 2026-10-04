package com.filamentvision.backend.input.network

import com.filamentvision.backend.input.profile.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EffectiveUdpNetworkPlannerTest {
    @Test
    fun autoAndAllInterfacesBindWildcardWithoutSelectingAnInterface() {
        for (bind in listOf(BindProfile.Auto, BindProfile.AllInterfaces)) {
            val plan = success(planner(listOf(entry("wifi", "10.0.0.2"))).plan(profile(bind = bind)))
            assertEquals("0.0.0.0", plan.actualBindIpv4)
            assertNull(plan.actualBindInterfaceId)
            assertEquals("10.0.0.2", plan.resolvedDestinationIpv4)
        }
    }

    @Test
    fun specificInterfaceUsesStableIdentityAndReportsEveryOwnershipFailure() {
        val entries = listOf(
            entry("wifi", "10.0.0.2"),
            entry("down", "10.0.0.3", up = false),
        )
        val planner = planner(entries)
        assertEquals("10.0.0.2", success(planner.plan(profile(bind = BindProfile.SpecificInterface("wifi", "10.0.0.2")))).actualBindIpv4)
        assertError("INTERFACE_NOT_FOUND", planner.plan(profile(bind = BindProfile.SpecificInterface("gone", "10.0.0.2"))))
        assertError("INTERFACE_NOT_UP", planner.plan(profile(bind = BindProfile.SpecificInterface("down", "10.0.0.3"))))
        assertError("INTERFACE_ADDRESS_CHANGED", planner.plan(profile(bind = BindProfile.SpecificInterface("wifi", "10.0.0.9"))))
    }

    @Test
    fun manualBindMustStillBeOwnedByAnUpLocalInterface() {
        val planner = planner(listOf(entry("wifi", "10.0.0.2"), entry("down", "10.0.0.3", up = false)))
        val plan = success(planner.plan(profile(bind = BindProfile.ManualIp("10.0.0.2"))))
        assertEquals("wifi", plan.actualBindInterfaceId)
        assertError("MANUAL_BIND_IP_NOT_LOCAL", planner.plan(profile(bind = BindProfile.ManualIp("10.0.0.9"))))
        assertError("MANUAL_BIND_IP_NOT_LOCAL", planner.plan(profile(bind = BindProfile.ManualIp("10.0.0.3"))))
    }

    @Test
    fun autoDestinationUsesOnlyAConvergedRouteAcrossAllResolvedSourceAddresses() {
        val entries = listOf(entry("wifi", "10.0.0.2"), entry("ethernet", "192.168.1.2"), entry("vpn", "100.64.0.2", virtual = true))
        val converged = planner(entries, mapOf("10.0.0.20" to "10.0.0.2", "10.0.0.21" to "10.0.0.2"))
        val sourced = profile(source = SourceFilterProfile.IpOrHost("pi.local"))
        assertEquals("10.0.0.2", success(converged.plan(sourced, source = source("pi.local", "10.0.0.20", "10.0.0.21"))).resolvedDestinationIpv4)
        val disagree = planner(entries, mapOf("10.0.0.20" to "10.0.0.2", "10.0.0.21" to "192.168.1.2"))
        assertError("DESTINATION_AMBIGUOUS", disagree.plan(sourced, source = source("pi.local", "10.0.0.20", "10.0.0.21")))
        val missing = planner(entries, mapOf("10.0.0.20" to "10.0.0.2"))
        assertError("DESTINATION_AMBIGUOUS", missing.plan(sourced, source = source("pi.local", "10.0.0.20", "10.0.0.21")))
    }

    @Test
    fun autoWithoutSourceRequiresExactlyOneEligibleCandidateAndNeverUsesOrderingAsATieBreaker() {
        assertError("NO_DESTINATION_CANDIDATE", planner(emptyList()).plan(profile()))
        assertEquals("10.0.0.2", success(planner(listOf(entry("wifi", "10.0.0.2"))).plan(profile())).resolvedDestinationIpv4)
        val multiple = listOf(entry("vpn", "100.64.0.2", virtual = true), entry("wifi", "10.0.0.2"))
        assertError("DESTINATION_AMBIGUOUS", planner(multiple).plan(profile()))
        assertError("DESTINATION_AMBIGUOUS", planner(multiple.reversed()).plan(profile()))
    }

    @Test
    fun manualDestinationRequiresOneResolutionAndDifferentSubnetIsOnlyAWarning() {
        val planner = planner(listOf(entry("wifi", "10.0.0.2")))
        val profile = profile(
            destination = AdvertisedDestinationProfile.Manual("backend.local"),
            source = SourceFilterProfile.IpOrHost("pi.local"),
        )
        val plan = success(planner.plan(profile, destination = destination("backend.local", "192.168.50.7"), source = source("pi.local", "10.0.0.20")))
        assertEquals("192.168.50.7", plan.resolvedDestinationIpv4)
        assertTrue("DIFFERENT_SUBNET" in plan.warnings)
        assertError(
            "DESTINATION_AMBIGUOUS",
            planner.plan(
                profile,
                destination("backend.local", "10.0.0.2", "10.0.0.3"),
                source("pi.local", "10.0.0.20"),
            ),
        )
    }

    private fun profile(
        bind: BindProfile = BindProfile.Auto,
        destination: AdvertisedDestinationProfile = AdvertisedDestinationProfile.Auto,
        source: SourceFilterProfile = SourceFilterProfile.Any,
    ) = UdpNetworkProfile(bind, destination, source, 5_000)

    private fun source(host: String, vararg addresses: String) = resolution(host, *addresses)
    private fun destination(host: String, vararg addresses: String) = resolution(host, *addresses)
    private fun resolution(host: String, vararg addresses: String) = HostResolution(
        host, linkedSetOf(*addresses), HostResolutionStatus.RESOLVED, 1L,
    )

    private fun planner(entries: List<Ipv4NetworkInterfaceEntry>, routes: Map<String, String?> = emptyMap()) =
        EffectiveUdpNetworkPlanner(Ipv4NetworkInterfaceProvider { entries }, Ipv4RouteProbe { routes[it] })

    private fun entry(id: String, ipv4: String, up: Boolean = true, virtual: Boolean = false) =
        Ipv4NetworkInterfaceEntry(id, id, ipv4, up, false, virtual, true, 1)

    private fun success(result: NetworkPlanResult): NetworkPlanCandidate = (result as NetworkPlanResult.Success).candidate

    private fun assertError(code: String, result: NetworkPlanResult) {
        assertEquals(code, (result as NetworkPlanResult.Failure).code)
    }
}
