package com.filamentvision.backend.api

import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.content.OutgoingContent
import io.ktor.server.testing.*
import org.junit.Assert.*
import org.junit.Test

class RequestMediaContractTest {
    @Test fun malformedMediaTypeIsStructured415OnEveryMutation() = rejectedEverywhere("invalid", HttpStatusCode.UnsupportedMediaType, "UNSUPPORTED_MEDIA_TYPE")
    @Test fun malformedSubtypeIsStructured415OnEveryMutation() = rejectedEverywhere("application//json", HttpStatusCode.UnsupportedMediaType, "UNSUPPORTED_MEDIA_TYPE")
    @Test fun unsupportedCharsetIsStructured415OnEveryMutation() = rejectedEverywhere("application/json; charset=nonexistent-charset", HttpStatusCode.UnsupportedMediaType, "UNSUPPORTED_MEDIA_TYPE")
    @Test fun illegalCharsetNameIsStructured415OnEveryMutation() = rejectedEverywhere("application/json; charset=\"bad charset\"", HttpStatusCode.UnsupportedMediaType, "UNSUPPORTED_MEDIA_TYPE")
    @Test fun malformedUtf8CannotBecomeAnAcceptedCommandId() = rejectedEverywhere("application/json; charset=UTF-8", HttpStatusCode.BadRequest, "INVALID_REQUEST", malformedBytes = true)

    private fun rejectedEverywhere(mediaType: String, status: HttpStatusCode, code: String, malformedBytes: Boolean = false) = testApplication {
        val backend = FakeBackend(); application { installRestApi(backend) { "corr1" } }
        for ((method, path, fields) in mutations) {
            val commandBytes = if (malformedBytes) byteArrayOf(0xc3.toByte(), 0x28) else "command1".toByteArray()
            val bytes = "{\"commandId\":\"".toByteArray() + commandBytes + "\"$fields}".toByteArray()
            // Content headers bypass the client's early Content-Type parsing; bytes bypass string encoding.
            val response = client.request("/api/v1/devices/device1$path") {
                this.method = method
                setBody(object : OutgoingContent.ByteArrayContent() {
                    override val headers = headersOf(HttpHeaders.ContentType, mediaType)
                    override fun bytes() = bytes
                })
            }
            assertEquals("$method $path with $mediaType", status, response.status)
            assertError(response, status, code)
            assertEquals("corr1", response.headers["X-Correlation-Id"])
            assertTrue(backend.calls.isEmpty())
        }
    }

    @Test fun validExplicitUtf8StillDelegatesEveryMutationExactlyOnce() = testApplication {
        val backend = FakeBackend(); application { installRestApi(backend) { "corr1" } }
        for ((method, path, fields) in mutations) {
            val body = "{\"commandId\":\"命令-1\"$fields}".toByteArray(Charsets.UTF_8)
            val response = client.request("/api/v1/devices/device1$path") {
                this.method = method; header(HttpHeaders.ContentType, "application/json; charset=UTF-8"); setBody(ByteArrayContent(body))
            }
            assertEquals(if (path == "/calibration/drafts") HttpStatusCode.Created else HttpStatusCode.OK, response.status)
        }
        assertEquals(listOf("start", "stop", "save", "apply", "saveAndApply", "createDraft", "updateDraft", "deleteDraft"), backend.calls.map { it.first })
        assertTrue(backend.calls.all { "命令-1" in it.second })
    }

    private val mutations = listOf(
        Triple(HttpMethod.Post, "/monitoring/start", ""),
        Triple(HttpMethod.Post, "/monitoring/stop", ""),
        Triple(HttpMethod.Put, "/configuration", ",\"configuration\":{}"),
        Triple(HttpMethod.Post, "/configuration/apply", ",\"revision\":1,\"expectedSavedRevision\":1"),
        Triple(HttpMethod.Post, "/configuration/save-and-apply", ",\"configuration\":{}"),
        Triple(HttpMethod.Post, "/calibration/drafts", ",\"cameraId\":\"A\",\"configuration\":{}"),
        Triple(HttpMethod.Patch, "/calibration/drafts/draft1", ",\"expectedRevision\":1,\"configuration\":{}"),
        Triple(HttpMethod.Delete, "/calibration/drafts/draft1", ""),
    )
}
