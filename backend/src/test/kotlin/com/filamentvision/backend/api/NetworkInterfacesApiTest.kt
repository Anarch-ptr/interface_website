package com.filamentvision.backend.api

import com.filamentvision.backend.BackendComposition
import com.filamentvision.backend.ComposedBackendApplication
import com.filamentvision.backend.input.network.Ipv4NetworkInterfaceEntry
import com.filamentvision.backend.input.network.Ipv4NetworkInterfaceProvider
import com.filamentvision.backend.input.runtime.BackendInputSourceFactory
import com.filamentvision.backend.input.runtime.RaspberryPiUdpInputSource
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkInterfacesApiTest {
    @Test
    fun interfaceInventoryIsIndependentFromSavedConfiguration() = testApplication {
        val ethernet = Ipv4NetworkInterfaceEntry(
            "eth", "Ethernet", "192.168.1.20", true, false, false, true, 2,
        )
        val wifi = ethernet.copy(id = "wifi", displayName = "Wi-Fi", ipv4 = "10.0.0.20", interfaceIndex = 4)
        val provider = Ipv4NetworkInterfaceProvider { listOf(wifi, ethernet) }
        val composition = openInventoryComposition(provider)
        val backend = ComposedBackendApplication(composition, provider)
        application { installRestApi(backend) }

        suspend fun inventory() = client.get("/api/v1/network-interfaces").let {
            assertEquals(it.bodyAsText(), HttpStatusCode.OK, it.status)
            parse(it.bodyAsText())["data"]
        }

        try {
            val initial = inventory()
            assertEquals(listOf("wifi", "eth"), initial["interfaces"].map { it["id"].asText() })
            assertTrue(initial["selection"] == null)
            assertTrue(initial["advertisedBackendIpv4"] == null)
            assertTrue(initial["listenPort"] == null)

            val saved = composition.udpProfileRepository.state().saved.profile
            composition.application.saveUdpInput(
                composition.deviceId,
                "save-inventory-independence",
                1,
                saved.copy(network = saved.network.copy(listenPort = 5_500)),
            )

            assertEquals(initial, inventory())
        } finally {
            composition.close()
        }
    }

    @Test
    fun interfaceInventoryNeverIncludesWildcardIpv6OrLegacyEffectiveFields() = testApplication {
        val ethernet = Ipv4NetworkInterfaceEntry(
            "eth", "Ethernet", "192.168.1.20", true, false, false, true, 2,
        )
        val provider = Ipv4NetworkInterfaceProvider {
            listOf(
                ethernet,
                ethernet.copy(id = "wildcard", ipv4 = "0.0.0.0"),
                ethernet.copy(id = "ipv6", ipv4 = "::1"),
                ethernet.copy(id = "down", isUp = false),
                ethernet.copy(id = "loopback", isLoopback = true),
            )
        }
        val composition = openInventoryComposition(provider)
        val backend = ComposedBackendApplication(composition, provider)
        application { installRestApi(backend) }

        try {
            val response = client.get("/api/v1/network-interfaces")
            assertEquals(response.bodyAsText(), HttpStatusCode.OK, response.status)
            val data = parse(response.bodyAsText())["data"]
            assertEquals(1, data["interfaces"].size())
            for (entry in data["interfaces"]) {
                assertTrue(entry["isUp"].asBoolean())
                assertFalse(entry["isLoopback"].asBoolean())
                assertFalse(entry["ipv4"].asText().contains(':'))
                assertFalse(entry["ipv4"].asText() == "0.0.0.0")
            }
            assertTrue(data["selection"] == null)
            assertTrue(data["advertisedBackendIpv4"] == null)
            assertTrue(data["statusDetail"] == null)
        } finally {
            composition.close()
        }
    }
}

private suspend fun openInventoryComposition(provider: Ipv4NetworkInterfaceProvider): BackendComposition {
    val root = Files.createTempDirectory("network-interfaces-api")
    return BackendComposition.open(
        root.resolve("backend.sqlite"),
        root.resolve("snapshots"),
        BackendInputSourceFactory { _, _, _, _, profile -> RaspberryPiUdpInputSource(profile) },
    )
}
