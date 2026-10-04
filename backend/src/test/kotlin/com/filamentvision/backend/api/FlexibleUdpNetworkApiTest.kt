package com.filamentvision.backend.api

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import org.junit.Assert.*
import org.junit.Test

class FlexibleUdpNetworkApiTest {
    private val base = "/api/v1/devices/filamentvision-1"

    @Test fun nestedNetworkContractPreviewAndSameRevisionReapplyAreAuthoritative() = testApplication {
        val c = openApiComposition(); application { installRestApi(c.application) }
        try {
            val initial = parse(client.get("$base/udp-input-configuration").bodyAsText())["data"]
            assertEquals("AUTO", initial["configuration"]["network"]["bind"]["mode"].asText())
            val initialGeneration = initial["resolutionGeneration"].asLong()

            val oldFlat = udpSaveJson("old", 1).replace(
                "\"network\":{\"bind\":{\"mode\":\"AUTO\"},\n      \"destination\":{\"mode\":\"MANUAL\",\"hostOrIpv4\":\"127.0.0.1\"},\n      \"sourceFilter\":{\"mode\":\"ANY\"},\"listenPort\":5000}",
                "\"networkInterface\":{\"mode\":\"AUTO\"},\"listenPort\":5000",
            )
            assertEquals(HttpStatusCode.BadRequest, client.put("$base/udp-input-configuration") {
                contentType(ContentType.Application.Json); setBody(oldFlat)
            }.status)

            assertEquals(HttpStatusCode.OK, client.put("$base/udp-input-configuration") {
                contentType(ContentType.Application.Json); setBody(udpSaveJson("save", 1))
            }.status)
            val applied = client.post("$base/udp-input-configuration/apply") {
                contentType(ContentType.Application.Json); setBody(udpApplyJson("apply", 2, 2, 1, initialGeneration))
            }
            assertEquals(applied.bodyAsText(), HttpStatusCode.OK, applied.status)
            assertEquals(initialGeneration + 1, parse(applied.bodyAsText())["data"]["resolutionGeneration"].asLong())

            val reapplied = client.post("$base/udp-input-configuration/apply") {
                contentType(ContentType.Application.Json)
                setBody(udpApplyJson("reapply", 2, 2, 2, initialGeneration + 1, forceReapply = true))
            }
            assertEquals(reapplied.bodyAsText(), HttpStatusCode.OK, reapplied.status)
            val data = parse(reapplied.bodyAsText())["data"]
            assertEquals(2L, data["appliedRevision"].asLong())
            assertEquals(initialGeneration + 2, data["resolutionGeneration"].asLong())

            val preview = client.post("$base/udp-network-resolution-preview") {
                contentType(ContentType.Application.Json)
                setBody("""{"network":{"bind":{"mode":"AUTO"},"destination":{"mode":"MANUAL","hostOrIpv4":"changed.invalid"},"sourceFilter":{"mode":"ANY"},"listenPort":5000}}""")
            }
            assertEquals(preview.bodyAsText(), HttpStatusCode.OK, preview.status)
            assertEquals("NOT_REQUIRED", parse(preview.bodyAsText())["data"]["destinationStatus"].asText())
            assertEquals(initialGeneration + 2, c.udpProfileRepository.state().resolutionGeneration)
        } finally { c.close() }
    }
}
