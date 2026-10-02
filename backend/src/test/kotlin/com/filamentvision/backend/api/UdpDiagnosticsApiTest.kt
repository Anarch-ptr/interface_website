package com.filamentvision.backend.api

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.Assert.*
import org.junit.Test

class UdpDiagnosticsApiTest {
    @Test fun returnsStablePerCameraDiagnosticsAndRejectsUnknownCamera() = testApplication {
        val backend = FakeBackend(); application { installRestApi(backend) { "udp-diag" } }
        val response = client.get("/api/v1/devices/device1/cameras/A/udp-diagnostics")
        assertEquals(HttpStatusCode.OK, response.status)
        val data = parse(response.bodyAsText())["data"]
        assertEquals("A", data["cameraId"].asText())
        assertEquals("STRUCTURED_CHUNKED", data["transportMode"].asText())
        assertEquals(4, data["datagramsReceived"].asLong())
        assertEquals(1, backend.calls.count { it.first == "udpDiagnostics" })
        assertEquals(HttpStatusCode.NotFound,
            client.get("/api/v1/devices/device1/cameras/C/udp-diagnostics").status)
    }
}
