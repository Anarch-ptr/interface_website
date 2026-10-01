package com.filamentvision.backend.api

import com.filamentvision.backend.application.*
import com.filamentvision.core.config.*
import com.filamentvision.core.repository.*
import com.filamentvision.core.runtime.StartOutcome
import com.filamentvision.core.model.MeasurementStatus
import com.filamentvision.core.fusion.FusionReason
import com.filamentvision.core.frame.CameraId
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import org.junit.Assert.*
import org.junit.Test

class RestContractTest {
    @Test fun stateAndLatestKeepSingleCameraEvidenceAndHideInternalState() = testApplication {
        val backend = FakeBackend()
        application { installRestApi(backend) { "corr1" } }
        for ((path, callName) in listOf("state" to "state", "measurements/latest" to "latest")) {
            val response = client.get("/api/v1/devices/device1/$path")
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(ContentType.Application.Json, response.contentType()?.withoutParameters())
            val text = response.bodyAsText(); val root = parse(text)
            assertEquals("corr1", root["correlationId"].asText())
            val measurement = if (path == "state") root["data"]["latestMeasurement"] else root["data"]["measurement"]
            assertTrue(measurement["cameraB"].isNull); assertTrue(measurement["diameterB"].isNull)
            assertEquals("A", measurement["cameraA"]["cameraId"].asText())
            assertEquals(1001, measurement["cameraA"]["timestamp"].asLong())
            assertEquals(7, measurement["cameraA"]["sequence"].asLong())
            assertEquals(0.95, measurement["cameraA"]["confidence"].asDouble(), 0.0)
            assertTrue(measurement["cameraA"]["valid"].asBoolean())
            assertEquals(1.8, measurement["fusedDiameter"].asDouble(), 0.0)
            assertEquals(0.0, measurement["shapeDifference"].asDouble(), 0.0)
            assertEquals("CAMERA_B_MISSING", measurement["fusionReason"].asText())
            assertEquals("CAMERA_B_FAILURE", measurement["status"].asText())
            assertSafe(text)
            assertEquals(callName to listOf("device1"), backend.calls.last())
        }
        assertEquals(2, backend.calls.size)
    }

    @Test fun startAndStopDelegateReplayAndPreconditionsWithoutOwningLifecycle() = testApplication {
        val backend = FakeBackend(); var correlations = 0
        application { installRestApi(backend) { "c${++correlations}" } }
        for (operation in listOf("start", "stop")) {
            val body = if (operation == "start") """{"commandId":"cmd-$operation","targetDiameter":1.8,"appliedRevision":2}""" else """{"commandId":"cmd-$operation"}"""
            val first = client.post("/api/v1/devices/device1/monitoring/$operation") { contentType(ContentType.Application.Json); setBody(body) }
            val second = client.post("/api/v1/devices/device1/monitoring/$operation") { contentType(ContentType.Application.Json); setBody(body) }
            assertEquals(HttpStatusCode.OK, first.status); assertEquals(HttpStatusCode.OK, second.status)
            val a = parse(first.bodyAsText()); val b = parse(second.bodyAsText())
            assertEquals(a["data"], b["data"]); assertNotEquals(a["correlationId"], b["correlationId"])
            assertEquals("s1", a["data"]["session"]["id"].asText())
        }
        assertEquals(listOf("start", "start", "stop", "stop"), backend.calls.map { it.first })
        assertEquals(listOf("device1", "cmd-start", 1.8, 2L), backend.calls.first().second)
        assertEquals(listOf("device1", "cmd-stop"), backend.calls.last().second)
    }

    @Test fun sessionDetailAndAlarmsExposeStatisticsAndTransitions() = testApplication {
        val backend = FakeBackend(); application { installRestApi(backend) { "corr1" } }
        val detail = client.get("/api/v1/sessions/s1")
        assertEquals(HttpStatusCode.OK, detail.status)
        val data = parse(detail.bodyAsText())["data"]
        assertEquals(1000, data["startedAt"].asLong()); assertEquals(2000, data["endedAt"].asLong())
        assertEquals(1, data["statistics"]["sampleCount"].asLong()); assertSafe(detail.bodyAsText())
        val alarms = client.get("/api/v1/sessions/s1/alarms")
        assertEquals(HttpStatusCode.OK, alarms.status)
        assertEquals("WARNING", parse(alarms.bodyAsText())["data"][0]["to"]["level"].asText())
        assertEquals(listOf("session" to listOf("s1"), "alarms" to listOf("s1")), backend.calls)
    }

    @Test fun configurationCommandsRetainFullSettingsAndRevisionSemantics() = testApplication {
        val backend = FakeBackend(); application { installRestApi(backend) { "corr1" } }
        val base = "/api/v1/devices/device1/configuration"
        val read = client.get(base)
        assertEquals(HttpStatusCode.OK, read.status)
        assertEquals(3, parse(read.bodyAsText())["data"]["savedRevision"].asLong())
        val save = client.put(base) { contentType(ContentType.Application.Json); setBody("""{"commandId":"save1","configuration":$configJson}""") }
        assertEquals(HttpStatusCode.OK, save.status)
        val actual = backend.calls.last().second[2] as MonitoringConfiguration
        assertEquals(0.09, actual.cameraA.calibration.mmPerPixel, 0.0); assertEquals(90, actual.cameraA.geometry.rotationDegrees)
        assertEquals(12, actual.writeBatchSize); assertEquals(500, actual.flushIntervalMillis); assertEquals(200, actual.realtimeCapacity)
        assertEquals(4, actual.previewFramesPerSecond); assertEquals(250, actual.publicationIntervalMillis)
        assertEquals(0.6, actual.fusion.cameraAWeight, 0.0)
        backend.configOutcome = ConfigurationApplyOutcome.APPLIED
        val apply = client.post("$base/apply") { contentType(ContentType.Application.Json); setBody("""{"commandId":"apply1","revision":3,"expectedSavedRevision":3}""") }
        assertEquals(HttpStatusCode.OK, apply.status)
        assertEquals("APPLIED", parse(apply.bodyAsText())["data"]["outcome"].asText())
        assertEquals(listOf("device1", "apply1", 3L, 3L), backend.calls.last().second)
        val combined = client.post("$base/save-and-apply") { contentType(ContentType.Application.Json); setBody("""{"commandId":"combined1","configuration":$configJson}""") }
        assertEquals(HttpStatusCode.OK, combined.status)
        assertEquals(2, parse(combined.bodyAsText())["data"]["appliedRevision"].asLong())
        assertEquals(listOf("configuration", "save", "apply", "saveAndApply"), backend.calls.map { it.first })
        assertSafe(combined.bodyAsText())
    }

    @Test fun requiresStopReportsTheSavedAndAppliedRevisions() = testApplication {
        val backend = FakeBackend().apply { configOutcome = ConfigurationApplyOutcome.REQUIRES_STOP }
        application { installRestApi(backend) { "corr1" } }
        val response = client.post("/api/v1/devices/device1/configuration/apply") { contentType(ContentType.Application.Json); setBody("""{"commandId":"apply1","revision":3,"expectedSavedRevision":3}""") }
        assertError(response, HttpStatusCode.Conflict, "REQUIRES_STOP")
        assertEquals(3, parse(response.bodyAsText())["details"]["savedRevision"].asLong())
        assertEquals(2, parse(response.bodyAsText())["details"]["appliedRevision"].asLong())
    }

    @Test fun calibrationDraftLifecycleDelegatesValidatedSettingsAndRevision() = testApplication {
        val backend = FakeBackend(); application { installRestApi(backend) { "corr1" } }
        val base = "/api/v1/devices/device1/calibration/drafts"
        val created = client.post(base) { contentType(ContentType.Application.Json); setBody("""{"commandId":"create1","cameraId":"A","configuration":{"calibration":{"mmPerPixel":0.09}}}""") }
        assertEquals(HttpStatusCode.Created, created.status)
        assertEquals("draft1", parse(created.bodyAsText())["data"]["id"].asText())
        assertEquals(listOf("device1", "create1", "A"), backend.calls.last().second.take(3))
        assertEquals(0.09, (backend.calls.last().second[3] as VisionConfiguration).calibration.mmPerPixel, 0.0)
        val updated = client.patch("$base/draft1") { contentType(ContentType.Application.Json); setBody("""{"commandId":"update1","expectedRevision":1,"configuration":{"geometry":{"rotationDegrees":90}}}""") }
        assertEquals(HttpStatusCode.OK, updated.status); assertEquals(2, parse(updated.bodyAsText())["data"]["revision"].asLong())
        assertEquals(listOf("device1", "draft1", "update1", 1L), backend.calls.last().second.take(4))
        assertEquals(90, (backend.calls.last().second[4] as VisionConfiguration).geometry.rotationDegrees)
        val deleted = client.delete("$base/draft1") { contentType(ContentType.Application.Json); setBody("""{"commandId":"delete1"}""") }
        assertEquals(HttpStatusCode.OK, deleted.status); assertTrue(parse(deleted.bodyAsText())["data"]["deleted"].asBoolean())
        assertEquals(listOf("device1", "draft1", "delete1"), backend.calls.last().second)
        assertEquals(listOf("createDraft", "updateDraft", "deleteDraft"), backend.calls.map { it.first })
    }

    @Test fun camerasDiagnosticsErrorsAndSnapshotMetadataExposeOnlyPublicValues() = testApplication {
        val backend = FakeBackend(); application { installRestApi(backend) { "corr1" } }
        val cameras = client.get("/api/v1/devices/device1/cameras")
        assertEquals(HttpStatusCode.OK, cameras.status); assertEquals("UNAVAILABLE", parse(cameras.bodyAsText())["data"][1]["state"].asText())
        val diagnostics = client.get("/api/v1/devices/device1/diagnostics")
        assertEquals(HttpStatusCode.OK, diagnostics.status)
        assertEquals(9.0, parse(diagnostics.bodyAsText())["data"]["cameras"][0]["processed"]["perSecond"].asDouble(), 0.0)
        val errors = client.get("/api/v1/errors?sessionId=s1")
        assertEquals(HttpStatusCode.OK, errors.status); assertEquals("CAMERA_UNAVAILABLE", parse(errors.bodyAsText())["data"][0]["code"].asText())
        val snapshots = client.get("/api/v1/errors/err1/snapshots")
        assertEquals(HttpStatusCode.OK, snapshots.status)
        val snapshot = parse(snapshots.bodyAsText())["data"][0]
        assertEquals("/api/v1/snapshots/snap1", snapshot["url"].asText()); assertEquals(7, snapshot["sourceSequence"].asLong())
        assertEquals("A", snapshot["cameraId"].asText()); assertEquals(1001, snapshot["timestamp"].asLong())
        listOf(cameras, diagnostics, errors, snapshots).forEach { assertSafe(it.bodyAsText()) }
        assertEquals(listOf("cameras" to listOf("device1"), "diagnostics" to listOf("device1"), "errors" to listOf("s1"), "snapshots" to listOf("err1")), backend.calls)
    }

    @Test fun snapshotReturnsOnlyEncodedBytesAndSafeHeaders() = testApplication {
        val backend = FakeBackend(); application { installRestApi(backend) { "corr1" } }
        val response = client.get("/api/v1/snapshots/snap1")
        assertEquals(HttpStatusCode.OK, response.status); assertEquals(ContentType.Image.PNG, response.contentType())
        assertArrayEquals(byteArrayOf(1, 0, -1, 10), response.readRawBytes())
        assertEquals("corr1", response.headers["X-Correlation-Id"])
        assertEquals("nosniff", response.headers["X-Content-Type-Options"])
        assertTrue(response.headers[HttpHeaders.ContentDisposition]!!.contains("capture.png"))
        assertEquals(listOf("snapshot" to listOf("snap1")), backend.calls)
    }

    @Test fun snapshotRejectsUnsafeMediaAndFilenameBeforeHeadersAreWritten() = testApplication {
        val backend = FakeBackend(); application { installRestApi(backend) { "corr1" } }
        for (content in listOf(SnapshotContent(byteArrayOf(1), "text/html", "x.html"), SnapshotContent(byteArrayOf(1), "image/png", "../secret\r\nX-Evil: 1"))) {
            backend.snapshotContent = content
            assertError(client.get("/api/v1/snapshots/snap1"), HttpStatusCode.InternalServerError, "INTERNAL_ERROR")
        }
    }

    @Test fun everyMutationRequiresNonblankCommandIdWithoutDelegation() = testApplication {
        val backend = FakeBackend(); application { installRestApi(backend) { "corr1" } }
        val base = "/api/v1/devices/device1"
        val mutations = listOf(HttpMethod.Post to "$base/monitoring/start", HttpMethod.Post to "$base/monitoring/stop",
            HttpMethod.Put to "$base/configuration", HttpMethod.Post to "$base/configuration/apply", HttpMethod.Post to "$base/configuration/save-and-apply",
            HttpMethod.Post to "$base/calibration/drafts", HttpMethod.Patch to "$base/calibration/drafts/draft1", HttpMethod.Delete to "$base/calibration/drafts/draft1")
        for ((verb, path) in mutations) for (body in listOf("{}", """{"commandId":"  "}""")) {
            assertError(client.request(path) { method = verb; contentType(ContentType.Application.Json); setBody(body) }, HttpStatusCode.BadRequest, "INVALID_REQUEST")
        }
        assertTrue(backend.calls.isEmpty())
    }

    @Test fun malformedJsonUnsupportedMediaAndInvalidNumbersHaveStableErrors() = testApplication {
        val backend = FakeBackend(); application { installRestApi(backend) { "corr1" } }
        val start = "/api/v1/devices/device1/monitoring/start"
        for (body in listOf("{", "null", """{"commandId":"x","targetDiameter":-1}""", """{"commandId":"x","targetDiameter":"NaN"}""", """{"commandId":"x","appliedRevision":0}""", """{"commandId":4}""", """{"commandId":"x","unexpected":true}""")) {
            val response = client.post(start) { contentType(ContentType.Application.Json); setBody(body) }
            assertEquals("Request: $body", HttpStatusCode.BadRequest, response.status)
            assertError(response, HttpStatusCode.BadRequest, "INVALID_REQUEST")
        }
        assertError(client.post(start) { contentType(ContentType.Text.Plain); setBody("{}") }, HttpStatusCode.UnsupportedMediaType, "UNSUPPORTED_MEDIA_TYPE")
        assertError(client.put("/api/v1/devices/device1/configuration") { contentType(ContentType.Application.Json); setBody("""{"commandId":"x","configuration":{"targetDiameter":0}}""") }, HttpStatusCode.BadRequest, "INVALID_REQUEST")
        assertError(client.patch("/api/v1/devices/device1/calibration/drafts/draft1") { contentType(ContentType.Application.Json); setBody("""{"commandId":"x","expectedRevision":1,"configuration":{"geometry":{"rotationDegrees":13}}}""") }, HttpStatusCode.BadRequest, "INVALID_REQUEST")
        assertTrue(backend.calls.isEmpty())
    }

    @Test fun unknownResourcesMapToNotFoundWithoutLeakingFailureText() = testApplication {
        val backend = FakeBackend().apply { failure = ApplicationFailure(ApplicationFailureCode.NOT_FOUND) }
        application { installRestApi(backend) { "corr1" } }
        for (path in listOf("devices/missing/state", "sessions/missing", "errors/missing/snapshots", "snapshots/missing"))
            assertError(client.get("/api/v1/$path"), HttpStatusCode.NotFound, "NOT_FOUND")
        assertEquals(4, backend.calls.size)
    }

    @Test fun typedRepositoryAndApplicationFailuresAndUnexpectedFailuresHaveSafeStableMapping() = testApplication {
        val backend = FakeBackend(); application { installRestApi(backend) { "corr1" } }
        val cases = listOf(
            Triple(RepositoryException(RepositoryFailure.INVALID, "secret jdbc"), HttpStatusCode.BadRequest, "INVALID_REQUEST"),
            Triple(RepositoryException(RepositoryFailure.CONFLICT, "secret jdbc"), HttpStatusCode.Conflict, "CONFLICT"),
            Triple(RepositoryException(RepositoryFailure.NOT_FOUND, "secret jdbc"), HttpStatusCode.NotFound, "NOT_FOUND"),
            Triple(RepositoryException(RepositoryFailure.STORAGE, "secret jdbc"), HttpStatusCode.ServiceUnavailable, "STORAGE_UNAVAILABLE"),
            Triple(ApplicationFailure(ApplicationFailureCode.REQUIRES_STOP), HttpStatusCode.Conflict, "REQUIRES_STOP"),
            Triple(ApplicationFailure(ApplicationFailureCode.UNAVAILABLE), HttpStatusCode.ServiceUnavailable, "UNAVAILABLE"),
            Triple(IllegalStateException("secret SELECT * FROM errors"), HttpStatusCode.InternalServerError, "INTERNAL_ERROR"))
        for ((failure, status, code) in cases) { backend.failure = failure; assertError(client.get("/api/v1/devices/device1/state"), status, code) }
    }

    @Test fun runtimeFailedClosedInterruptedAndStopFailureAreNotSuccesses() = testApplication {
        val backend = FakeBackend(); application { installRestApi(backend) { "corr1" } }
        for ((outcome, status, code) in listOf(Triple(StartOutcome.FAILED, HttpStatusCode.ServiceUnavailable, "UNAVAILABLE"), Triple(StartOutcome.CLOSED, HttpStatusCode.ServiceUnavailable, "UNAVAILABLE"), Triple(StartOutcome.INTERRUPTED, HttpStatusCode.Conflict, "CONFLICT"))) {
            backend.startOutcome = outcome
            assertError(client.post("/api/v1/devices/device1/monitoring/start") { contentType(ContentType.Application.Json); setBody("""{"commandId":"$outcome"}""") }, status, code)
        }
        backend.stopError = "secret jdbc exception"
        assertError(client.post("/api/v1/devices/device1/monitoring/stop") { contentType(ContentType.Application.Json); setBody("""{"commandId":"stop-fail"}""") }, HttpStatusCode.ServiceUnavailable, "UNAVAILABLE")
    }

    @Test fun nonfiniteDomainEvidenceMapsToNullRatherThanInvalidJson() = testApplication {
        val backend = FakeBackend().apply { measurement = sample.copy(cameraA = camera.copy(pixelWidth = Double.NaN, diameterMm = Double.POSITIVE_INFINITY, confidence = Double.NEGATIVE_INFINITY), fusedDiameter = Double.NaN, shapeDifference = Double.POSITIVE_INFINITY, confidence = Double.NaN) }
        application { installRestApi(backend) { "corr1" } }
        val response = client.get("/api/v1/devices/device1/measurements/latest")
        assertEquals(HttpStatusCode.OK, response.status)
        val data = parse(response.bodyAsText())["data"]["measurement"]
        listOf("fusedDiameter", "shapeDifference", "confidence", "diameterA").forEach { assertTrue(data[it].isNull) }
        listOf("pixelWidth", "diameterMm", "confidence").forEach { assertTrue(data["cameraA"][it].isNull) }
        assertSafe(response.bodyAsText())
    }

    @Test fun duplicateCommandFieldsAreRejectedBeforeAnyMutation() = testApplication {
        val backend = FakeBackend(); application { installRestApi(backend) { "corr1" } }
        val response = client.post("/api/v1/devices/device1/monitoring/start") {
            contentType(ContentType.Application.Json); setBody("""{"commandId":"first","commandId":"second"}""")
        }
        assertError(response, HttpStatusCode.BadRequest, "INVALID_REQUEST"); assertTrue(backend.calls.isEmpty())
    }

    @Test fun absentLatestIsNullAndCameraBOnlyRetainsItsOwnEvidence() = testApplication {
        val backend = FakeBackend()
        val facade = object : BackendApplication by backend {
            override suspend fun latest(deviceId: String): MeasurementRecord? = null
        }
        application { installRestApi(facade) { "corr1" } }
        val empty = client.get("/api/v1/devices/device1/measurements/latest")
        assertEquals(HttpStatusCode.OK, empty.status); assertTrue(parse(empty.bodyAsText())["data"].isNull)
        backend.measurement = sample.copy(cameraA = null, cameraB = camera.copy(cameraId = CameraId("B"), timestamp = 1002, sequence = 9),
            status = MeasurementStatus.CAMERA_A_FAILURE, fusionReason = FusionReason.CAMERA_A_MISSING)
        val response = client.get("/api/v1/devices/device1/state")
        assertEquals(HttpStatusCode.OK, response.status)
        val sample = parse(response.bodyAsText())["data"]["latestMeasurement"]
        assertTrue(sample["cameraA"].isNull); assertTrue(sample["diameterA"].isNull)
        assertEquals("B", sample["cameraB"]["cameraId"].asText()); assertEquals(9, sample["cameraB"]["sequence"].asLong())
        assertEquals(1002, sample["cameraB"]["timestamp"].asLong()); assertSafe(response.bodyAsText())
    }

    @Test fun configurationNonfiniteOutputFailsSafelyBeforeSendingJson() = testApplication {
        val backend = object : BackendApplication by FakeBackend() {
            override suspend fun configuration(deviceId: String) = ConfigurationView(ConfigurationRevisions(1, 1),
                ConfigurationRevision(1, MonitoringConfiguration(targetDiameter = Double.NaN)))
        }
        application { installRestApi(backend) { "corr1" } }
        assertError(client.get("/api/v1/devices/device1/configuration"), HttpStatusCode.InternalServerError, "INTERNAL_ERROR")
    }

    @Test fun conflictFromConfigurationApplyIsPreservedAndDoesNotReturnSuccess() = testApplication {
        val backend = FakeBackend().apply { failure = RepositoryException(RepositoryFailure.CONFLICT, "secret configuration table") }
        application { installRestApi(backend) { "corr1" } }
        val response = client.post("/api/v1/devices/device1/configuration/apply") {
            contentType(ContentType.Application.Json); setBody("""{"commandId":"apply1","revision":2,"expectedSavedRevision":3}""")
        }
        assertError(response, HttpStatusCode.Conflict, "CONFLICT")
        assertEquals(listOf("apply" to listOf("device1", "apply1", 2L, 3L)), backend.calls)
    }

    @Test fun unsafeInjectedCorrelationFallsBackToNonblankHeaderSafeIdentity() = testApplication {
        application { installRestApi(FakeBackend()) { "\r\n" } }
        val response = client.get("/api/v1/devices/device1/state")
        assertEquals(HttpStatusCode.OK, response.status)
        val correlation = parse(response.bodyAsText())["correlationId"].asText()
        assertTrue(correlation.isNotBlank()); assertEquals(correlation, response.headers["X-Correlation-Id"])
        assertFalse(correlation.contains('\n'))
    }
}

internal fun assertSafe(body: String) {
    listOf("NaN", "Infinity", "base64", "pixels", "objectKey", "secret", "jdbc", "SELECT", "stackTrace", "cause").forEach { assertFalse("Leaked $it in $body", body.contains(it, ignoreCase = true)) }
}
internal suspend fun assertError(response: HttpResponse, status: HttpStatusCode, code: String) {
    assertEquals(status, response.status)
    assertEquals(ContentType.Application.Json, response.contentType()?.withoutParameters())
    val body = response.bodyAsText(); val root = parse(body)
    assertEquals(code, root["code"].asText()); assertTrue(root["message"].asText().isNotBlank())
    assertEquals("corr1", root["correlationId"].asText()); assertSafe(body)
}
