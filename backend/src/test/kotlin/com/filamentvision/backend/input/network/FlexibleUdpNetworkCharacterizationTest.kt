package com.filamentvision.backend.input.network

import com.filamentvision.backend.input.profile.NetworkInterfaceSelection
import com.filamentvision.backend.input.profile.RaspberryPiUdpInputProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** Locks down the pre-delta network behaviour before the flexible model replaces it. */
class FlexibleUdpNetworkCharacterizationTest {
    @Test
    fun defaultsRemainDisabledOnAutoWithTheExistingEditablePortAndAnySource() {
        val profile = RaspberryPiUdpInputProfile.defaults()

        assertFalse(profile.enabled)
        assertSame(NetworkInterfaceSelection.Auto, profile.networkInterface)
        assertEquals(5_000, profile.listenPort)
        assertNull(profile.raspberryPiIpv4)
    }

    @Test
    fun autoUsesTheRouteOwnedAddressWhenTheSourceGivesAUniqueRoute() {
        val resolver = resolver(
            entries = listOf(entry("ethernet", "192.168.20.7"), entry("vpn", "10.9.0.4", virtual = true)),
            routedIpv4 = "10.9.0.4",
        )

        val result = resolver.resolve(NetworkInterfaceSelection.Auto, "10.9.0.42")

        assertEquals("vpn", result.selectedInterface?.id)
        assertEquals("10.9.0.4", result.advertisedBackendIpv4)
    }

    @Test
    fun specificSelectionRequiresTheSavedAddressToStillBelongToTheSameInterface() {
        var entries = listOf(entry("wifi", "192.168.30.8"))
        val resolver = BackendIpv4Resolver(Ipv4NetworkInterfaceProvider { entries }, Ipv4RouteProbe { null })
        val selection = NetworkInterfaceSelection.Specific("wifi", "192.168.30.8")

        assertEquals("192.168.30.8", resolver.resolve(selection, null).advertisedBackendIpv4)
        entries = listOf(entry("wifi", "192.168.30.9"))

        val changed = resolver.resolve(selection, null)
        assertNull(changed.advertisedBackendIpv4)
        assertEquals("SELECTED_IPV4_INTERFACE_UNAVAILABLE", changed.statusDetail)
    }

    private fun resolver(
        entries: List<Ipv4NetworkInterfaceEntry>,
        routedIpv4: String?,
    ) = BackendIpv4Resolver(
        Ipv4NetworkInterfaceProvider { entries },
        Ipv4RouteProbe { routedIpv4 },
    )

    private fun entry(id: String, ipv4: String, virtual: Boolean = false) =
        Ipv4NetworkInterfaceEntry(id, id, ipv4, true, false, virtual, true, 1)
}
