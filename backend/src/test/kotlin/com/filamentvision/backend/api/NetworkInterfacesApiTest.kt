package com.filamentvision.backend.api

import com.filamentvision.backend.ComposedBackendApplication
import com.filamentvision.backend.BackendComposition
import com.filamentvision.backend.input.network.*
import com.filamentvision.backend.input.profile.*
import com.filamentvision.backend.input.runtime.BackendInputSourceFactory
import com.filamentvision.backend.input.runtime.RaspberryPiUdpInputSource
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class NetworkInterfacesApiTest {
    @Test fun deterministicAutoRouteSpecificAndNoUsableInventory() = testApplication {
        val ethernet = Ipv4NetworkInterfaceEntry("eth", "Ethernet", "192.168.1.20", true, false, false, true, 2)
        val wifi = ethernet.copy(id = "wifi", displayName = "Wi-Fi", ipv4 = "10.0.0.20", interfaceIndex = 4)
        val vpn = ethernet.copy(id = "vpn", displayName = "VPN", ipv4 = "172.16.1.20", isVirtual = true, interfaceIndex = 1)
        var entries = listOf(vpn, wifi, ethernet, ethernet.copy(ipv4 = "0.0.0.0"), ethernet.copy(ipv4 = "::1"),
            ethernet.copy(isUp = false), ethernet.copy(isLoopback = true))
        val c = openInventoryComposition(Ipv4NetworkInterfaceProvider { entries })
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
            assertEquals("192.168.1.20", data()["advertisedBackendIpv4"].asText())
            assertEquals(5000, data()["listenPort"].asInt())
            c.application.applyUdpInput(c.deviceId, "apply-routed", 2, 2)
            assertEquals("10.0.0.20", data()["advertisedBackendIpv4"].asText())
            assertEquals(5500, data()["listenPort"].asInt())
            c.application.saveUdpInput(c.deviceId, "specific", 2,
                routed.copy(networkInterface = NetworkInterfaceSelection.Specific("eth", "192.168.1.20")))
            assertEquals("AUTO", data()["selection"]["mode"].asText())
            c.application.applyUdpInput(c.deviceId, "apply-specific", 3, 3)
            val specific = data()
            assertEquals("SPECIFIC", specific["selection"]["mode"].asText())
            assertEquals("eth", specific["selection"]["interfaceId"].asText())
            assertEquals("192.168.1.20", specific["advertisedBackendIpv4"].asText())
            entries = emptyList()
            assertEquals("SELECTED_IPV4_INTERFACE_UNAVAILABLE", data()["statusDetail"].asText())
            assertTrue(data()["advertisedBackendIpv4"].isNull)
            c.application.saveUdpInput(c.deviceId, "auto", 3, routed)
            c.application.applyUdpInput(c.deviceId, "apply-auto", 4, 4)
            val empty = data()
            assertEquals("NO_USABLE_IPV4_INTERFACE", empty["statusDetail"].asText())
            assertTrue(empty["advertisedBackendIpv4"].isNull)
            assertFalse(empty["hasMultipleCandidates"].asBoolean())
        } finally { c.close() }
    }
    @Test fun saveThenReloadKeepsAdvertisedDestinationEffectiveUntilApply() = testApplication {
        val ethernet = Ipv4NetworkInterfaceEntry("eth", "Ethernet", "192.168.1.20", true, false, false, true, 2)
        val wifi = ethernet.copy(id = "wifi", displayName = "Wi-Fi", ipv4 = "10.0.0.20", interfaceIndex = 4)
        val provider = Ipv4NetworkInterfaceProvider { listOf(ethernet, wifi) }
        val c = openInventoryComposition(provider)
        val backend = ComposedBackendApplication(c, provider, Ipv4RouteProbe { "10.0.0.20" })
        val source = c.inputSource as RaspberryPiUdpInputSource
        val base = "/api/v1/devices/${c.deviceId}/udp-input-configuration"
        application { installRestApi(backend) }
        suspend fun getData(path: String) = client.get(path).let {
            assertEquals(it.bodyAsText(), HttpStatusCode.OK, it.status)
            parse(it.bodyAsText())["data"]
        }
        try {
            val initial = getData("/api/v1/network-interfaces")
            assertEquals("192.168.1.20", initial["advertisedBackendIpv4"].asText())
            assertEquals(5000, initial["listenPort"].asInt())
            val saved = client.put(base) {
                contentType(ContentType.Application.Json)
                setBody(udpSaveJson("save-destination", 1)
                    .replace("\"mode\":\"AUTO\"", "\"mode\":\"SPECIFIC\",\"interfaceId\":\"wifi\",\"ipv4\":\"10.0.0.20\"")
                    .replace("\"listenPort\":5000", "\"listenPort\":5500")
                    .replace("\"raspberryPiIpv4\":null", "\"raspberryPiIpv4\":\"10.0.0.30\""))
            }
            assertEquals(saved.bodyAsText(), HttpStatusCode.OK, saved.status)
            // Settings Reload fetches both the saved draft and current receiver destination.
            val reloaded = getData(base)
            assertEquals(2L, reloaded["savedRevision"].asLong())
            assertEquals(1L, reloaded["appliedRevision"].asLong())
            assertEquals("SPECIFIC", reloaded["configuration"]["networkInterface"]["mode"].asText())
            assertEquals(5500, reloaded["configuration"]["listenPort"].asInt())
            assertEquals("10.0.0.30", reloaded["configuration"]["raspberryPiIpv4"].asText())
            assertEquals("AUTO", reloaded["appliedConfiguration"]["networkInterface"]["mode"].asText())
            assertEquals(5000, reloaded["appliedConfiguration"]["listenPort"].asInt())
            assertTrue(reloaded["appliedConfiguration"]["raspberryPiIpv4"].isNull)
            assertEquals(RaspberryPiUdpInputProfile.defaults(), source.effectiveProfile)
            assertEquals(initial, getData("/api/v1/network-interfaces"))

            val applied = client.post("$base/apply") {
                contentType(ContentType.Application.Json)
                setBody(udpApplyJson("apply-destination", 2, 2))
            }
            assertEquals(applied.bodyAsText(), HttpStatusCode.OK, applied.status)
            assertEquals(2L, getData(base)["appliedRevision"].asLong())
            val destination = getData("/api/v1/network-interfaces")
            assertEquals("SPECIFIC", destination["selection"]["mode"].asText())
            assertEquals("wifi", destination["selection"]["interfaceId"].asText())
            assertEquals("10.0.0.20", destination["advertisedBackendIpv4"].asText())
            assertEquals(5500, destination["listenPort"].asInt())
            assertEquals(NetworkInterfaceSelection.Specific("wifi", "10.0.0.20"), source.effectiveProfile.networkInterface)
            assertEquals(5500, source.effectiveProfile.listenPort)
            assertEquals("10.0.0.30", source.effectiveProfile.raspberryPiIpv4)
            assertEquals(c.udpProfileRepository.state().applied.profile, source.effectiveProfile)
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

private suspend fun openInventoryComposition(provider: Ipv4NetworkInterfaceProvider): BackendComposition {
    val root = Files.createTempDirectory("network-interfaces-api")
    return BackendComposition.open(root.resolve("backend.sqlite"), root.resolve("snapshots"),
        BackendInputSourceFactory { _, _, _, _, profile ->
            RaspberryPiUdpInputSource(profile, ipv4Resolver = BackendIpv4Resolver(provider))
        })
}
