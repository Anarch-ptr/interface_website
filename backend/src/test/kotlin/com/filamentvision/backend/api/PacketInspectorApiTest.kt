package com.filamentvision.backend.api

import com.filamentvision.backend.input.profile.*
import com.filamentvision.backend.input.runtime.RaspberryPiUdpInputSource
import com.filamentvision.backend.input.transport.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlinx.coroutines.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import org.junit.Assert.*
import org.junit.Test

class PacketInspectorApiTest {
    private val base = "/api/v1/devices/device1/packet-inspector"

    @Test fun createsPollsAndReleasesLeaseWithBoundedUppercaseHex() = testApplication {
        val backend = FakeBackend(); application { installRestApi(backend) { "inspect" } }
        val created = client.post("$base/lease") { contentType(ContentType.Application.Json); setBody("""{"previewBytes":128}""") }
        assertEquals(HttpStatusCode.Created, created.status)
        val lease = parse(created.bodyAsText())["data"]["leaseId"].asText()
        val poll = client.get("$base?leaseId=$lease&previewBytes=128")
        assertEquals(HttpStatusCode.OK, poll.status)
        assertEquals("\"7\"", poll.headers[HttpHeaders.ETag])
        val data = parse(poll.bodyAsText())["data"]
        assertTrue(data["rawDatagram"]["hexPreview"].asText().startsWith("AA 01 46 56 55 31"))
        assertEquals("01 02", data["rawDatagram"]["transport"]["hexPreview"].asText())
        assertEquals("VALID", data["rawDatagram"]["status"].asText())
        assertEquals("42", data["rawDatagram"]["transport"]["frameId"].asText())
        assertEquals(1, data["rawDatagram"]["transport"]["protocolVersion"].asInt())
        assertEquals(1, data["rawDatagram"]["transport"]["compatibilityStreamId"].asInt())
        assertEquals("GRAY8", data["parsedFrame"]["payloadFormat"].asText())
        assertEquals(2, data["parsedFrame"]["width"].asInt())
        assertEquals(2, data["parsedFrame"]["completedFrameBytes"].asInt())
        assertFalse(data.has("cameraId"))
        assertFalse(data["parsedFrame"].has("applicationHeaderBytes"))
        assertEquals(HttpStatusCode.OK, client.delete("$base/lease/$lease").status)
    }

    @Test fun unchangedSequenceReturns304AndInvalidLeaseSizeOrOldRoutesAreRejected() = testApplication {
        val backend = FakeBackend(); application { installRestApi(backend) }
        val unchanged = client.get("$base?leaseId=lease&previewBytes=64") { header(HttpHeaders.IfNoneMatch, "\"7\"") }
        assertEquals(HttpStatusCode.NotModified, unchanged.status)
        assertEquals(HttpStatusCode.BadRequest, client.get("$base?leaseId=lease&previewBytes=512").status)
        assertEquals(HttpStatusCode.BadRequest, client.post("$base/lease") {
            contentType(ContentType.Application.Json); setBody("""{"previewBytes":512}""")
        }.status)
        for (camera in listOf("A", "B", "camera")) {
            val old = "/api/v1/devices/device1/cameras/$camera/packet-inspector"
            assertEquals(HttpStatusCode.NotFound, client.get("$old?leaseId=lease&previewBytes=64").status)
            assertEquals(HttpStatusCode.NotFound, client.post("$old/lease") {
                contentType(ContentType.Application.Json); setBody("""{"previewBytes":64}""")
            }.status)
            assertEquals(HttpStatusCode.NotFound, client.delete("$old/lease/lease").status)
        }
        backend.inspectorAvailable = false
        assertEquals(HttpStatusCode.NotFound, client.get("$base?leaseId=expired&previewBytes=64").status)
    }

    @Test fun realLeaseObservesFilteredTrafficWithoutEnteringEnvelopeTransportOrDecode() = testApplication {
        val c = openApiComposition(); application { installRestApi(c.application) }
        val source = c.inputSource as RaspberryPiUdpInputSource
        val path = "/api/v1/devices/${c.deviceId}/packet-inspector"
        val port = DatagramSocket(0).use { it.localPort }
        val profile = RaspberryPiUdpInputProfile.defaults().copy(enabled = true, listenPort = port,
            raspberryPiIpv4 = "127.0.0.2", payload = ImagePayloadProfile.Gray8(2, 1))
        try {
            c.application.saveUdpInput(c.deviceId, "enable", 1, profile)
            c.application.applyUdpInput(c.deviceId, "apply", 2, 2)
            c.application.start(c.deviceId, "start", null, null)
            DatagramSocket().use { sender ->
                val bytes = byteArrayOf(0xaa.toByte(), 1) + StructuredTransportCodec.encode(
                    StructuredTransportHeader(1u, 42u, 0, 1, 345, 2, 2), byteArrayOf(7, 8)) + byteArrayOf(13, 10)
                fun send(packet: ByteArray = bytes) = sender.send(DatagramPacket(packet, packet.size, InetAddress.getByName("127.0.0.1"), port))
                send()
                withTimeout(3000) { while (source.udpDiagnostics().sourceFilteredDatagrams < 1) delay(5) }
                assertEquals(0L, source.packetInspector.previewCopies)
                assertEquals(0, source.packetInspector.retainedPreviewBytes)
                val created = client.post("$path/lease") { contentType(ContentType.Application.Json); setBody("""{"previewBytes":64}""") }
                assertEquals(created.bodyAsText(), HttpStatusCode.Created, created.status)
                val lease = parse(created.bodyAsText())["data"]["leaseId"].asText()
                assertFalse(parse(created.bodyAsText())["data"].has("cameraId"))
                assertEquals(HttpStatusCode.NotFound, client.get("$path?leaseId=$lease&previewBytes=64").status)
                send()
                // Do not take the polling lock while awaiting this optional tryLock capture:
                // the observer is allowed to drop the single datagram if the test itself holds it.
                withTimeout(3000) { while (source.packetInspector.previewCopies == 0L) delay(5) }
                val polled = client.get("$path?leaseId=$lease&previewBytes=64")
                assertEquals(HttpStatusCode.OK, polled.status)
                val raw = parse(polled.bodyAsText())["data"]["rawDatagram"]
                assertEquals("SOURCE_IP_MISMATCH", raw["status"].asText())
                assertEquals("127.0.0.1", raw["sourceIpv4"].asText())
                assertEquals(sender.localPort, raw["sourcePort"].asInt())
                assertEquals(port, raw["backendListenPort"].asInt())
                assertTrue(raw["hexPreview"].asText().startsWith("AA 01 46 56 55 31"))
                assertTrue(raw["hexPreview"].asText().endsWith("07 08 0D 0A"))
                assertTrue(raw["headerMatched"].isNull); assertTrue(raw["footerMatched"].isNull)
                assertTrue(raw["transport"].isNull)
                assertEquals(HttpStatusCode.NotModified, client.get("$path?leaseId=$lease&previewBytes=64") {
                    header(HttpHeaders.IfNoneMatch, polled.headers[HttpHeaders.ETag]!!)
                }.status)
                send(ByteArray(40)) // Even a malformed envelope remains a source-filter observation.
                withTimeout(3000) { while (source.udpDiagnostics().sourceFilteredDatagrams < 3) delay(5) }
                assertEquals(0L, source.udpDiagnostics().invalidHeaderCount)
                assertEquals(0L, source.udpDiagnostics().invalidTransportHeaders)
                assertEquals(0L, source.udpDiagnostics().framesStarted)
                assertEquals(0L, source.udpDiagnostics().framesCompleted)
                assertEquals(0L, source.udpDiagnostics().decodeFailures)
                assertEquals(0L, c.runtime.state.value.statistics.sampleCount)
                assertEquals(HttpStatusCode.OK, client.delete("$path/lease/$lease").status)
                assertEquals(0, source.packetInspector.retainedPreviewBytes)
                assertEquals(HttpStatusCode.NotFound, client.get("$path?leaseId=$lease&previewBytes=64").status)
                assertEquals(HttpStatusCode.NotFound, client.delete("$path/lease/$lease").status)
                assertEquals(HttpStatusCode.NotFound, client.post("/api/v1/devices/missing/packet-inspector/lease") {
                    contentType(ContentType.Application.Json); setBody("""{"previewBytes":64}""")
                }.status)
            }
        } finally { c.close() }
    }
}
