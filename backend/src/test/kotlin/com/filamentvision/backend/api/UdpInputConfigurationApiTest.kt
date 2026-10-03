package com.filamentvision.backend.api

import com.filamentvision.backend.BackendComposition
import com.filamentvision.backend.input.runtime.RaspberryPiUdpInputSource
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.net.DatagramSocket
import java.net.InetSocketAddress
import com.filamentvision.backend.input.profile.*
import kotlinx.coroutines.Dispatchers

class UdpInputConfigurationApiTest {
    private val base = "/api/v1/devices/filamentvision-1/udp-input-configuration"

    @Test fun missingRevisionsAndMalformedOrLegacyCommandsNeverMutateConfiguration() = testApplication {
        val c = openApiComposition(); application { installRestApi(c.application) { "corr1" } }
        try {
            assertError(client.post("$base/apply") { contentType(ContentType.Application.Json); setBody(udpApplyJson("missing", 99, 1)) },
                HttpStatusCode.NotFound, "NOT_FOUND")
            for (body in listOf("{}", udpSaveJson("", 1), udpSaveJson("missing-cas", 1).replace("\"expectedSavedRevision\":1,", ""),
                udpSaveJson("dual", 1).replace("\"configuration\":{", "\"configuration\":{\"cameraA\":{},"),
                udpSaveJson("mode", 1).replace("\"configuration\":{", "\"configuration\":{\"transportMode\":\"SINGLE_DATAGRAM\","))) {
                assertError(client.put(base) { contentType(ContentType.Application.Json); setBody(body) }, HttpStatusCode.BadRequest, "INVALID_REQUEST")
            }
            for (body in listOf("{}", udpApplyJson("", 1, 1), udpApplyJson("bad", 0, 1), udpApplyJson("bad", 1, 0))) {
                assertError(client.post("$base/apply") { contentType(ContentType.Application.Json); setBody(body) }, HttpStatusCode.BadRequest, "INVALID_REQUEST")
            }
            assertError(client.put(base.replace(c.deviceId, "missing")) { contentType(ContentType.Application.Json); setBody(udpSaveJson("missing", 1)) },
                HttpStatusCode.NotFound, "NOT_FOUND")
            assertEquals(1L, c.udpProfileRepository.state().saved.revision)
        } finally { c.close() }
    }

    @Test fun bindAndConsistencyFailuresAreSafeAndDoNotClaimApplied() = testApplication {
        val c = openApiComposition(); application { installRestApi(c.application) { "corr1" } }
        try {
            c.application.start(c.deviceId, "start", null, null)
            DatagramSocket(null).use { occupied ->
                occupied.reuseAddress = false; occupied.bind(InetSocketAddress("0.0.0.0", 0))
                c.application.saveUdpInput(c.deviceId, "busy", 1,
                    RaspberryPiUdpInputProfile.defaults().copy(enabled = true, listenPort = occupied.localPort))
                val failed = client.post("$base/apply") { contentType(ContentType.Application.Json); setBody(udpApplyJson("bind", 2, 2)) }
                assertError(failed, HttpStatusCode.Conflict, "UDP_BIND_FAILED")
                assertEquals(1L, c.udpProfileRepository.state().applied.revision)
                assertFalse((c.inputSource as RaspberryPiUdpInputSource).effectiveProfile.enabled)
                assertEquals(0, c.inputSource.activeReceiverCount)
            }
            c.application.stop(c.deviceId, "stop")
            c.application.saveUdpInput(c.deviceId, "absent-interface", 2, RaspberryPiUdpInputProfile.defaults().copy(
                networkInterface = NetworkInterfaceSelection.Specific("not-a-real-interface", "192.0.2.8")))
            assertError(client.post("$base/apply") { contentType(ContentType.Application.Json); setBody(udpApplyJson("interface", 3, 3)) },
                HttpStatusCode.Conflict, "UDP_BIND_FAILED")
            assertEquals(1L, c.udpProfileRepository.state().applied.revision)
            assertTrue(c.udpProfileRepository.markApplied(1, 3)) // Inject an inconsistent persisted pointer at the repository boundary.
            assertError(client.post("$base/apply") { contentType(ContentType.Application.Json); setBody(udpApplyJson("inconsistent", 3, 3)) },
                HttpStatusCode.ServiceUnavailable, "UDP_APPLY_CONSISTENCY_FAILURE")
        } finally { c.close() }
    }

    @Test fun singleProfileGetExposesOnlyExpectedInputFields() = testApplication {
        val backend = FakeBackend(); application { installRestApi(backend) }
        val response = client.get(base)
        assertEquals(HttpStatusCode.OK, response.status)
        val profile = parse(response.bodyAsText())["data"]["configuration"]
        assertEquals(setOf("enabled", "networkInterface", "listenPort", "raspberryPiIpv4", "payload", "envelope", "transportLimits"), profile.fieldNames().asSequence().toSet())
    }

    @Test fun realSaveApplyIsNormalizedDurableAndIdempotentWithCas() = testApplication {
        val c = openApiComposition(); application { installRestApi(c.application) }
        try {
            val initial = client.get(base)
            assertEquals(initial.bodyAsText(), HttpStatusCode.OK, initial.status)
            val start = parse(initial.bodyAsText())["data"]
            assertEquals(1, start["savedRevision"].asInt())
            assertEquals(1, start["appliedRevision"].asInt())
            assertFalse(start["configuration"]["enabled"].asBoolean())
            assertEquals("JPEG", start["configuration"]["payload"]["format"].asText())
            val body = udpSaveJson("save", 1)
            repeat(2) {
                val response = client.put(base) { contentType(ContentType.Application.Json); setBody(body) }
                assertEquals(response.bodyAsText(), HttpStatusCode.OK, response.status)
                val data = parse(response.bodyAsText())["data"]
                assertEquals(2, data["savedRevision"].asInt()); assertEquals(1, data["appliedRevision"].asInt())
                assertEquals("AA 01", data["configuration"]["envelope"]["headerHex"].asText())
                assertEquals("JPEG", data["appliedConfiguration"]["payload"]["format"].asText())
            }
            assertEquals(HttpStatusCode.Conflict, client.put(base) { contentType(ContentType.Application.Json); setBody(udpSaveJson("stale", 1)) }.status)
            assertEquals(HttpStatusCode.Conflict, client.post("$base/apply") { contentType(ContentType.Application.Json); setBody(udpApplyJson("stale", 2, 1)) }.status)
            repeat(2) {
                val response = client.post("$base/apply") { contentType(ContentType.Application.Json); setBody(udpApplyJson("apply", 2, 2)) }
                assertEquals(response.bodyAsText(), HttpStatusCode.OK, response.status)
                assertEquals("APPLIED", parse(response.bodyAsText())["data"]["outcome"].asText())
            }
            val source = c.inputSource as RaspberryPiUdpInputSource
            assertEquals(c.udpProfileRepository.state().applied.profile, source.effectiveProfile)
            assertEquals(2L, c.udpInputRouter!!.appliedRevision)
            assertEquals(0, source.activeProducerCount)
            assertEquals(HttpStatusCode.NotFound, client.get(base.replace("filamentvision-1", "missing")).status)
        } finally { c.close() }
    }

    @Test fun allFourFormatsRoundTripAndConditionalFieldsAreStrict() = testApplication {
        val c = openApiComposition(); application { installRestApi(c.application) }
        try {
            val formats = listOf(
                """{"format":"JPEG","maxFrameBytes":100000,"expectedWidth":32,"expectedHeight":16}""",
                """{"format":"GRAY8","width":32,"height":16}""",
                """{"format":"BINARY","width":32,"height":16,"packing":"BIT_PACKED"}""",
                """{"format":"BINARY","width":32,"height":16,"packing":"BYTE_PER_PIXEL"}""",
                """{"format":"RGB888","width":32,"height":16,"channelOrder":"RGB"}""",
                """{"format":"RGB888","width":32,"height":16,"channelOrder":"BGR"}""",
            )
            for ((index, payload) in formats.withIndex()) {
                val response = client.put(base) { contentType(ContentType.Application.Json); setBody(udpSaveJson("format-$index", index + 1L, payload)) }
                assertEquals(response.bodyAsText(), HttpStatusCode.OK, response.status)
                assertEquals(parse(payload), parse(response.bodyAsText())["data"]["configuration"]["payload"])
            }
            for (payload in listOf("""{"format":"DIAMETER"}""", """{"format":"GRAY8","height":16}""",
                """{"format":"GRAY8","width":32,"height":16,"channelOrder":"RGB"}""",
                """{"format":"BINARY","width":32,"height":16,"packing":"NOPE"}""")) {
                assertEquals(HttpStatusCode.BadRequest, client.put(base) { contentType(ContentType.Application.Json); setBody(udpSaveJson("invalid", 7, payload)) }.status)
            }
            assertEquals(7L, c.udpProfileRepository.state().saved.revision)
        } finally { c.close() }
    }

    @Test fun authoritativeValidationReturnsFieldPathsBeforeSaving() = testApplication {
        val c = openApiComposition(); application { installRestApi(c.application) }
        try {
            for ((from, to, field) in listOf(Triple("\"listenPort\":5000", "\"listenPort\":0", "listenPort"),
                Triple("aa01", "AA", "envelope.headerHex"), Triple("0d0a", "GG01", "envelope.footerHex"),
                Triple("\"raspberryPiIpv4\":null", "\"raspberryPiIpv4\":\"::1\"", "raspberryPiIpv4"),
                Triple("\"maxChunkCount\":32768", "\"maxChunkCount\":65536", "transportLimits.maxChunkCount"))) {
                val response = client.put(base) { contentType(ContentType.Application.Json); setBody(udpSaveJson("invalid", 1).replace(from, to)) }
                assertEquals(response.bodyAsText(), HttpStatusCode.BadRequest, response.status)
                val root = parse(response.bodyAsText())
                assertEquals("INVALID_REQUEST", root["code"].asText())
                assertTrue(root.toString(), root.path("details").path("fieldErrors").any { it["path"].asText() == field })
            }
            assertEquals(1L, c.udpProfileRepository.state().saved.revision)
        } finally { c.close() }
    }
}

internal suspend fun openApiComposition(monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000 }): BackendComposition {
    val root = Files.createTempDirectory("single-udp-api")
    return BackendComposition.open(root.resolve("backend.sqlite"), root.resolve("snapshots"), Dispatchers.Default,
        monotonicMillis = monotonicMillis)
}
internal fun udpSaveJson(command: String, revision: Long, payload: String = """{"format":"GRAY8","width":32,"height":16}""") = """{
    "commandId":"$command","expectedSavedRevision":$revision,"configuration":{
      "enabled":false,"networkInterface":{"mode":"AUTO"},"listenPort":5000,"raspberryPiIpv4":null,
      "payload":$payload,"envelope":{"headerHex":"aa01","footerHex":"0d0a"},
      "transportLimits":{"maxInFlightFrames":2,"maxFrameBytes":33554432,"frameTimeoutMs":1000,"maxChunkCount":32768,"maxChunkPayloadBytes":1400}}}"""
internal fun udpApplyJson(command: String, revision: Long, expected: Long) =
    """{"commandId":"$command","revision":$revision,"expectedSavedRevision":$expected}"""
