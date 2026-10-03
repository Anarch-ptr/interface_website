package com.filamentvision.backend.input.network

import com.filamentvision.backend.input.profile.NetworkInterfaceSelection
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import org.junit.Assert.*
import org.junit.Test

class BackendIpv4ResolverTest {
    @Test
    fun autoPrefersNonVirtualThenInterfaceIndexThenNumericIpv4RegardlessOfEnumerationOrder() {
        val candidates = listOf(
            entry("vpn", "10.0.0.1", index = 1, virtual = true),
            entry("eth", "192.168.0.1", index = 8),
            entry("wifi", "192.168.1.10", index = 3),
            entry("wifi", "192.168.1.2", index = 3),
        )
        for (order in listOf(candidates, candidates.reversed())) {
            val result = resolver(order).resolve(NetworkInterfaceSelection.Auto, null)
            assertEquals("wifi", result.selectedInterface!!.id)
            assertEquals("192.168.1.2", result.advertisedBackendIpv4)
            assertTrue(result.hasMultipleCandidates)
            assertNull(result.statusDetail)
        }
    }

    @Test
    fun autoAllowsVpnWhenItIsTheOnlyEligibleInterface() {
        val result = resolver(listOf(entry("vpn", "10.8.0.2", virtual = true)))
            .resolve(NetworkInterfaceSelection.Auto, null)
        assertEquals("10.8.0.2", result.advertisedBackendIpv4)
        assertTrue(result.selectedInterface!!.isVirtual)
        assertFalse(result.hasMultipleCandidates)
    }

    @Test
    fun autoUsesTheOwnedRouteToThePiEvenWhenDeterministicOrderWouldChooseAnotherAdapter() {
        val result = resolver(
            listOf(entry("eth", "192.168.1.2"), entry("vpn", "10.8.0.2", virtual = true)),
            Ipv4RouteProbe { pi -> if (pi == "10.8.0.99") "10.8.0.2" else "192.168.1.2" },
        ).resolve(NetworkInterfaceSelection.Auto, "10.8.0.99")
        assertEquals("vpn", result.selectedInterface!!.id)
        assertEquals("10.8.0.2", result.advertisedBackendIpv4)
        assertTrue(result.hasMultipleCandidates)
    }

    @Test
    fun absentPiAndSpecificSelectionDoNotNeedARouteProbe() {
        val resolver = resolver(listOf(entry("wifi", "192.168.1.2")), Ipv4RouteProbe { error("Unexpected probe") })
        assertEquals("192.168.1.2", resolver.resolve(NetworkInterfaceSelection.Auto, null).advertisedBackendIpv4)
        assertEquals("192.168.1.2", resolver.resolve(NetworkInterfaceSelection.Specific("wifi", "192.168.1.2"), "192.168.1.99").advertisedBackendIpv4)
    }

    @Test
    fun routeFailureOrNonEligibleRouteFallsBackWithoutAdvertisingWildcardOrIpv6() {
        val entries = listOf(entry("wifi", "192.168.1.2"))
        for (route in listOf<String?>(null, "0.0.0.0", "::1", "127.0.0.1", "192.168.1.250")) {
            assertEquals("192.168.1.2", resolver(entries, Ipv4RouteProbe { route })
                .resolve(NetworkInterfaceSelection.Auto, "192.168.1.99").advertisedBackendIpv4)
        }
        assertEquals("192.168.1.2", resolver(entries, Ipv4RouteProbe { throw IOException("No route") })
            .resolve(NetworkInterfaceSelection.Auto, "192.168.1.99").advertisedBackendIpv4)
    }

    @Test
    fun specificMustStillMatchBothInterfaceOwnershipAndUpAddressOnEveryResolution() {
        var entries = listOf(entry("wifi", "192.168.1.2"), entry("eth", "192.168.1.3"))
        val resolver = BackendIpv4Resolver(Ipv4NetworkInterfaceProvider { entries })
        val selection = NetworkInterfaceSelection.Specific("wifi", "192.168.1.2")
        assertEquals("192.168.1.2", resolver.resolve(selection, null).advertisedBackendIpv4)
        assertFalse(resolver.resolve(selection, null).hasMultipleCandidates)
        entries = listOf(entry("eth", "192.168.1.2"))
        assertUnavailable(resolver.resolve(selection, null))
        entries = listOf(entry("wifi", "192.168.1.2").copy(isUp = false))
        assertUnavailable(resolver.resolve(selection, null))
        entries = listOf(entry("wifi", "192.168.1.3"))
        assertUnavailable(resolver.resolve(selection, null))
    }

    @Test
    fun noUsableInterfaceHasAnExplicitResultAndNoAdvertisedTarget() {
        val result = resolver(emptyList(), Ipv4RouteProbe { error("No eligible interface") })
            .resolve(NetworkInterfaceSelection.Auto, "192.168.1.99")
        assertNull(result.selectedInterface)
        assertNull(result.advertisedBackendIpv4)
        assertFalse(result.hasMultipleCandidates)
        assertEquals("NO_USABLE_IPV4_INTERFACE", result.statusDetail)
    }

    @Test
    fun rejectsIpv6AndHostnamesForThePiBeforeProbing() {
        val resolver = resolver(listOf(entry("wifi", "192.168.1.2")), Ipv4RouteProbe { error("Invalid input reached probe") })
        for (pi in listOf("::1", "2001:db8::1", "::ffff:192.168.1.2", "pi.local", "192.168.001.2", "", "256.1.2.3")) {
            assertThrows(pi, IllegalArgumentException::class.java) { resolver.resolve(NetworkInterfaceSelection.Auto, pi) }
            assertThrows(pi, IllegalArgumentException::class.java) { JavaIpv4RouteProbe().localIpv4(pi) }
        }
    }

    @Test
    fun resolverDefendsAgainstIneligibleEntriesFromAProvider() {
        val invalid = listOf(
            entry("any", "0.0.0.0"), entry("v6", "2001:db8::1"),
            entry("lo", "127.0.0.1"), entry("down", "192.168.1.1").copy(isUp = false),
            entry("lo-flag", "192.168.1.3").copy(isLoopback = true),
        )
        assertEquals("NO_USABLE_IPV4_INTERFACE", resolver(invalid).resolve(NetworkInterfaceSelection.Auto, null).statusDetail)
        for (candidate in invalid) {
            assertUnavailable(resolver(invalid).resolve(NetworkInterfaceSelection.Specific(candidate.id, candidate.ipv4), null))
        }
    }

    @Test
    fun realIpv4RouteProbeConnectsWithoutSendingADatagram() {
        DatagramSocket(0, InetAddress.getByName("127.0.0.1")).use { receiver ->
            receiver.soTimeout = 150
            val probe = JavaIpv4RouteProbe(receiver.localPort)
            assertEquals("127.0.0.1", probe.localIpv4("127.0.0.1"))
            assertThrows(SocketTimeoutException::class.java) {
                receiver.receive(DatagramPacket(ByteArray(32), 32))
            }
        }
    }

    private fun resolver(entries: List<Ipv4NetworkInterfaceEntry>, probe: Ipv4RouteProbe = Ipv4RouteProbe { null }) =
        BackendIpv4Resolver(Ipv4NetworkInterfaceProvider { entries }, probe)

    private fun entry(id: String, ipv4: String, index: Int = 1, virtual: Boolean = false) =
        Ipv4NetworkInterfaceEntry(id, id, ipv4, true, false, virtual, true, index)

    private fun assertUnavailable(result: BackendIpv4Resolution) {
        assertNull(result.selectedInterface)
        assertNull(result.advertisedBackendIpv4)
        assertEquals("SELECTED_IPV4_INTERFACE_UNAVAILABLE", result.statusDetail)
        assertFalse(result.hasMultipleCandidates)
    }
}
