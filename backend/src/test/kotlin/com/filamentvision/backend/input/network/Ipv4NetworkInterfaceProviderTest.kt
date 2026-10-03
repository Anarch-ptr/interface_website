package com.filamentvision.backend.input.network

import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test

class Ipv4NetworkInterfaceProviderTest {
    @Test
    fun includesUpEthernetWifiAndVpnWithTheirLabelsAndCapabilities() {
        val provider = provider(
            adapter("wifi", "Wi-Fi", 8, listOf("192.168.1.20"), multicast = true),
            adapter("vpn", "Work VPN", 2, listOf("10.8.0.2"), virtual = true),
            adapter("eth", "Ethernet", 3, listOf("192.168.1.10")),
        )
        val entries = provider.listEligible().associateBy { it.id }
        assertEquals(setOf("wifi", "vpn", "eth"), entries.keys)
        assertEquals("Wi-Fi", entries.getValue("wifi").displayName)
        assertEquals("192.168.1.20", entries.getValue("wifi").ipv4)
        assertEquals(8, entries.getValue("wifi").interfaceIndex)
        assertTrue(entries.getValue("wifi").isUp)
        assertFalse(entries.getValue("wifi").isLoopback)
        assertTrue(entries.getValue("wifi").supportsMulticast)
        assertEquals("Work VPN", entries.getValue("vpn").displayName)
        assertTrue(entries.getValue("vpn").isVirtual)
        assertFalse(entries.getValue("vpn").supportsMulticast)
        assertEquals("Ethernet", entries.getValue("eth").displayName)
    }

    @Test
    fun excludesDownLoopbackIpv6AndUnspecifiedAddresses() {
        val entries = provider(
            adapter("down", "Disconnected", 1, listOf("192.168.1.1"), up = false),
            adapter("lo", "Loopback", 2, listOf("127.0.0.1"), loopback = true),
            adapter("wrong-loopback-flag", "Loopback alias", 3, listOf("127.0.0.2")),
            adapter("any", "Wildcard", 4, listOf("0.0.0.0")),
            adapter("v6", "IPv6 only", 5, listOf("2001:db8::1", "::1")),
            adapter("mixed", "Dual stack", 6, listOf("2001:db8::2", "192.168.2.10")),
        ).listEligible()
        assertEquals(listOf("192.168.2.10"), entries.map { it.ipv4 })
        assertEquals("mixed", entries.single().id)
    }

    @Test
    fun preservesEachOwnedIpv4AddressAndReturnsAnImmutableSnapshot() {
        val adapters = mutableListOf(adapter("eth", "Ethernet", 3, listOf("192.168.1.2", "192.168.1.10")))
        val provider = JavaIpv4NetworkInterfaceProvider(NetworkInterfacePlatform { adapters })
        val entries = provider.listEligible()
        adapters.clear()
        assertEquals(setOf("192.168.1.2", "192.168.1.10"), entries.map { it.ipv4 }.toSet())
        assertThrows(UnsupportedOperationException::class.java) {
            (entries as MutableList<Ipv4NetworkInterfaceEntry>).clear()
        }
        assertTrue(provider.listEligible().isEmpty())
    }

    @Test
    fun noHostInterfaceProducesAnEmptyList() {
        assertTrue(provider().listEligible().isEmpty())
    }

    private fun provider(vararg interfaces: PlatformNetworkInterface) =
        JavaIpv4NetworkInterfaceProvider(NetworkInterfacePlatform { interfaces.toList() })

    private fun adapter(
        id: String, displayName: String, index: Int, addresses: List<String>,
        up: Boolean = true, loopback: Boolean = false, virtual: Boolean = false, multicast: Boolean = false,
    ) = PlatformNetworkInterface(id, displayName, index, up, loopback, virtual, multicast,
        addresses.map(InetAddress::getByName))
}
