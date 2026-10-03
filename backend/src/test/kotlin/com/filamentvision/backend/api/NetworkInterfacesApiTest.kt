package com.filamentvision.backend.api

import com.filamentvision.backend.ComposedBackendApplication
import com.filamentvision.backend.input.network.*
import com.filamentvision.backend.input.profile.*
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.Assert.*
import org.junit.Test

class NetworkInterfacesApiTest {
    @Test fun deterministicAutoRouteSpecificAndNoUsableInventory() = testApplication {
        val c = openApiComposition()
        val ethernet = Ipv4NetworkInterfaceEntry("eth", "Ethernet", "192.168.1.20", true, false, false, true, 2)
        val wifi = ethernet.copy(id = "wifi", displayName = "Wi-Fi", ipv4 = "10.0.0.20", interfaceIndex = 4)
        val vpn = ethernet.copy(id = "vpn", displayName = "VPN", ipv4 = "172.16.1.20", isVirtual = true, interfaceIndex = 1)
        var entries = listOf(vpn, wifi, ethernet, ethernet.copy(ipv4 = "0.0.0.0"), ethernet.copy(ipv4 = "::1"),
            ethernet.copy(isUp = false), ethernet.copy(isLoopback = true))
        val backend = ComposedBackendApplication(c, Ipv4NetworkInterfaceProvider { entries }, Ipv4RouteProbe { "10.0.0.20" })
        application { installRestApi(backend) }
        suspend fun data() = client.get("/api/v1/network-interfaces").let {
            assertEquals(it.bodyAsText(), HttpStatusCode.OK, it.status); parse(it.bodyAsText())["data"]
        }
        try {
            val auto = data()
            assertEquals(3, auto["interfaces"].size())
            assertEquals("192.168.1.20", auto["advertisedBackendIpv4"].asText())
            assertTrue(auto["hasMultipleCandidates"].asBoolean())
            assertTrue(auto["interfaces"].any { it["id"].asText() == "vpn" && it["isVirtual"].asBoolean() })
            val routed = RaspberryPiUdpInputProfile.defaults().copy(raspberryPiIpv4 = "10.0.0.30", listenPort = 5500)
            c.application.saveUdpInput(c.deviceId, "routed", 1, routed)
            assertEquals("10.0.0.20", data()["advertisedBackendIpv4"].asText())
            assertEquals(5500, data()["listenPort"].asInt())
            c.application.saveUdpInput(c.deviceId, "specific", 2,
                routed.copy(networkInterface = NetworkInterfaceSelection.Specific("eth", "192.168.1.20")))
            val specific = data()
            assertEquals("SPECIFIC", specific["selection"]["mode"].asText())
            assertEquals("eth", specific["selection"]["interfaceId"].asText())
            assertEquals("192.168.1.20", specific["advertisedBackendIpv4"].asText())
            entries = emptyList()
            assertEquals("SELECTED_IPV4_INTERFACE_UNAVAILABLE", data()["statusDetail"].asText())
            assertTrue(data()["advertisedBackendIpv4"].isNull)
            c.application.saveUdpInput(c.deviceId, "auto", 3, routed)
            val empty = data()
            assertEquals("NO_USABLE_IPV4_INTERFACE", empty["statusDetail"].asText())
            assertTrue(empty["advertisedBackendIpv4"].isNull)
            assertFalse(empty["hasMultipleCandidates"].asBoolean())
        } finally { c.close() }
    }
    @Test fun interfaceInventoryNeverAdvertisesWildcardOrIpv6() = testApplication {
        val c = openApiComposition(); application { installRestApi(c.application) }
        try {
            val response = client.get("/api/v1/network-interfaces")
            assertEquals(response.bodyAsText(), HttpStatusCode.OK, response.status)
            val data = parse(response.bodyAsText())["data"]
            assertEquals("AUTO", data["selection"]["mode"].asText())
            assertEquals(5000, data["listenPort"].asInt())
            assertFalse(response.bodyAsText().contains("0.0.0.0"))
            for (entry in data["interfaces"]) {
                assertTrue(entry["isUp"].asBoolean()); assertFalse(entry["isLoopback"].asBoolean())
                assertFalse(entry["ipv4"].asText().contains(':'))
            }
            if (data["advertisedBackendIpv4"].isNull) assertEquals("NO_USABLE_IPV4_INTERFACE", data["statusDetail"].asText())
        } finally { c.close() }
    }
}
