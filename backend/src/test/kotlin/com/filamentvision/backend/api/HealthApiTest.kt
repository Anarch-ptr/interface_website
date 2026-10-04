package com.filamentvision.backend.api

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthApiTest {
    @Test fun `health proves HTTP routing without a device or monitoring session`() = testApplication {
        application { installHealthApi() }

        val response = client.get("/api/v1/health")

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.headers[HttpHeaders.ContentType]!!.startsWith(ContentType.Application.Json.toString()))
        assertEquals("{\"status\":\"ok\"}", response.bodyAsText())
    }
}
