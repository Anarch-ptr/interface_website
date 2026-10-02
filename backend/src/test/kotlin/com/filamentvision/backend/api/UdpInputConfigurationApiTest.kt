package com.filamentvision.backend.api

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import org.junit.Assert.*
import org.junit.Test

class UdpInputConfigurationApiTest {
    private val base = "/api/v1/devices/device1/udp-input-configuration"

    @Test fun getAndSaveMapCompleteIndependentProfiles() = testApplication {
        val backend = FakeBackend(); application { installRestApi(backend) }
        val get = client.get(base)
        assertEquals(HttpStatusCode.OK, get.status)
        val getData = parse(get.bodyAsText())["data"]
        assertEquals(3, getData["savedRevision"].asInt())
        assertEquals("STRUCTURED_CHUNKED", getData["configuration"]["cameraA"]["transportMode"].asText())
        assertEquals("DIAMETER", getData["configuration"]["cameraB"]["payload"]["format"].asText())

        val save = client.put(base) { contentType(ContentType.Application.Json); setBody(saveJson("udp-save")) }
        assertEquals(save.bodyAsText(), HttpStatusCode.OK, save.status)
        val call = backend.calls.last { it.first == "saveUdpInput" }
        assertEquals("udp-save", call.second[1])
        val response = parse(save.bodyAsText())["data"]
        assertEquals(3, response["savedRevision"].asInt())
        assertEquals(2, response["appliedRevision"].asInt())
    }

    @Test fun applyMapsRevisionCasAndCommandReplay() = testApplication {
        val backend = FakeBackend(); application { installRestApi(backend) }
        val body = """{"commandId":"apply-1","revision":3,"expectedSavedRevision":3}"""
        repeat(2) {
            val response = client.post("$base/apply") { contentType(ContentType.Application.Json); setBody(body) }
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("APPLIED", parse(response.bodyAsText())["data"]["outcome"].asText())
        }
        assertEquals(2, backend.calls.count { it.first == "applyUdpInput" })
    }

    @Test fun authoritativeValidationReturnsFieldPathsAndDoesNotDelegate() = testApplication {
        val backend = FakeBackend(); application { installRestApi(backend) }
        val invalid = saveJson("bad").replace("\"listenPort\":5101", "\"listenPort\":0")
        val response = client.put(base) { contentType(ContentType.Application.Json); setBody(invalid) }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val root = parse(response.bodyAsText())
        assertEquals("INVALID_REQUEST", root["code"].asText())
        assertTrue(root["details"]["fieldErrors"].any { it["path"].asText() == "cameraA.listenPort" })
        assertFalse(backend.calls.any { it.first == "saveUdpInput" })
    }

    @Test fun rejectsUnknownDeviceBadCasAndMalformedCommandsWithoutLeakingInternals() = testApplication {
        val backend = FakeBackend(); application { installRestApi(backend) }
        backend.failure = com.filamentvision.backend.application.ApplicationFailure(
            com.filamentvision.backend.application.ApplicationFailureCode.NOT_FOUND)
        val missing = client.get("/api/v1/devices/missing/udp-input-configuration")
        assertEquals(HttpStatusCode.NotFound, missing.status)
        assertFalse(missing.bodyAsText().contains("Exception"))
        backend.failure = null
        for (body in listOf("{}", """{"commandId":" ","revision":3,"expectedSavedRevision":3}""",
            """{"commandId":"x","revision":0,"expectedSavedRevision":3}""")) {
            assertEquals(HttpStatusCode.BadRequest, client.post("$base/apply") {
                contentType(ContentType.Application.Json); setBody(body)
            }.status)
        }
    }

    private fun saveJson(command: String) = """{
      "commandId":"$command","configuration":{
        "cameraA":{"enabled":true,"sourceIp":"127.0.0.1","listenPort":5101,"transportMode":"STRUCTURED_CHUNKED",
          "payload":{"format":"GRAY8","width":32,"height":16},
          "applicationFraming":{"headerEnabled":true,"headerFormat":"HEX","headerBytes":"AA 55","headerScope":"FRAME","stripHeader":true,
            "footerEnabled":false,"footerFormat":"ASCII","footerBytes":"","footerScope":"DATAGRAM","stripFooter":true},
          "transportLimits":{"maxInFlightFrames":3,"maxFrameBytes":100000,"frameTimeoutMs":750,"maxChunkCount":100,"maxChunkPayloadBytes":1300}},
        "cameraB":{"enabled":true,"sourceIp":null,"listenPort":5102,"transportMode":"SINGLE_DATAGRAM",
          "payload":{"format":"DIAMETER","valueEncoding":"FLOAT32","byteOrder":"LITTLE_ENDIAN","scale":0.5,"offset":0.1,"unit":"MM","directDiameterFusionConfidence":0.8},
          "applicationFraming":{},"transportLimits":{}}
      }}"""
}
