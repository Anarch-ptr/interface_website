package com.filamentvision.backend.api

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import org.junit.Assert.*
import org.junit.Test

class PacketInspectorApiTest {
    private val base = "/api/v1/devices/device1/cameras/A/packet-inspector"

    @Test fun createsPollsAndReleasesLeaseWithBoundedUppercaseHex() = testApplication {
        val backend = FakeBackend(); application { installRestApi(backend) { "inspect" } }
        val created = client.post("$base/lease") { contentType(ContentType.Application.Json); setBody("""{"previewBytes":128}""") }
        assertEquals(HttpStatusCode.Created, created.status)
        val lease = parse(created.bodyAsText())["data"]["leaseId"].asText()
        val poll = client.get("$base?leaseId=$lease&previewBytes=128")
        assertEquals(HttpStatusCode.OK, poll.status)
        assertEquals("\"7\"", poll.headers[HttpHeaders.ETag])
        val data = parse(poll.bodyAsText())["data"]
        assertEquals("AA 0F FF", data["rawDatagram"]["hexPreview"].asText())
        assertEquals("SOURCE_IP_MISMATCH", data["rawDatagram"]["status"].asText())
        assertEquals(42, data["rawDatagram"]["transport"]["frameId"].asLong())
        assertEquals("GRAY8", data["parsedFrame"]["payloadFormat"].asText())
        assertFalse(poll.bodyAsText().contains("bytes"))
        assertEquals(HttpStatusCode.OK, client.delete("$base/lease/$lease").status)
    }

    @Test fun unchangedSequenceReturns304AndInvalidLeaseCameraOrSizeIsRejected() = testApplication {
        val backend = FakeBackend(); application { installRestApi(backend) }
        val unchanged = client.get("$base?leaseId=lease-A&previewBytes=64") { header(HttpHeaders.IfNoneMatch, "\"7\"") }
        assertEquals(HttpStatusCode.NotModified, unchanged.status)
        assertEquals(HttpStatusCode.BadRequest, client.get("$base?leaseId=lease-A&previewBytes=513").status)
        backend.inspectorAvailable = false
        assertEquals(HttpStatusCode.NotFound, client.get("$base?leaseId=expired&previewBytes=64").status)
        assertEquals(HttpStatusCode.NotFound,
            client.delete("/api/v1/devices/device1/cameras/B/packet-inspector/lease/lease-A").status)
    }
}
