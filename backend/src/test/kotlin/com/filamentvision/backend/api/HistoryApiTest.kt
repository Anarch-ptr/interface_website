package com.filamentvision.backend.api

import com.filamentvision.backend.application.PageQuery
import com.filamentvision.backend.application.BackendApplication
import com.filamentvision.backend.application.SessionPage
import com.filamentvision.core.repository.HistoryCursor
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import org.junit.Assert.*
import org.junit.Test

class HistoryApiTest {
    @Test fun sessionsAndHistoryRoundtripOpaqueKeysetsWithInclusiveRangeAndBoundedLimit() = testApplication {
        val backend = FakeBackend(); application { installRestApi(backend) { "corr1" } }
        for ((path, timestamp, id) in listOf(Triple("/api/v1/sessions", 1000L, "s1"), Triple("/api/v1/sessions/s1/measurements", 1001L, "m1"))) {
            val first = client.get("$path?limit=2&from=900&through=1100")
            assertEquals(HttpStatusCode.OK, first.status)
            val page = parse(first.bodyAsText())["data"]
            assertEquals(1, page["items"].size()); val cursor = page["nextCursor"].asText()
            assertTrue(cursor.isNotBlank()); assertFalse(cursor.contains(id)); assertFalse(cursor.contains(":"))
            assertEquals(PageQuery(null, 2, 900, 1100), backend.calls.last().second.last())
            val next = client.get("$path?limit=1&cursor=$cursor&from=900&through=1100")
            assertEquals(HttpStatusCode.OK, next.status)
            assertEquals(PageQuery(HistoryCursor(timestamp, id), 1, 900, 1100), backend.calls.last().second.last())
            assertSafe(first.bodyAsText())
        }
        assertEquals(listOf("sessions", "sessions", "history", "history"), backend.calls.map { it.first })
        assertEquals("s1", backend.calls.last().second.first())
    }
    @Test fun invalidLimitsCursorsAndRangesNeverReachFacade() = testApplication {
        val backend = FakeBackend(); application { installRestApi(backend) { "corr1" } }
        for (query in listOf("limit=0", "limit=501", "limit=no", "cursor=garbage", "cursor=", "from=-1", "from=no", "from=20&through=10", "through=-1", "limit=1&limit=2"))
            for (path in listOf("/api/v1/sessions", "/api/v1/sessions/s1/measurements"))
                assertError(client.get("$path?$query"), HttpStatusCode.BadRequest, "INVALID_REQUEST")
        assertTrue(backend.calls.isEmpty())
    }
    @Test fun cursorCannotBeReusedAcrossSessionOrCollectionScopes() = testApplication {
        val backend = FakeBackend(); application { installRestApi(backend) { "corr1" } }
        val response = client.get("/api/v1/sessions/s1/measurements")
        assertEquals(HttpStatusCode.OK, response.status)
        val cursor = parse(response.bodyAsText())["data"]["nextCursor"].asText()
        assertEquals(PageQuery(), backend.calls.single().second.last())
        assertError(client.get("/api/v1/sessions/s2/measurements?cursor=$cursor"), HttpStatusCode.BadRequest, "INVALID_REQUEST")
        assertError(client.get("/api/v1/sessions?cursor=$cursor"), HttpStatusCode.BadRequest, "INVALID_REQUEST")
        assertEquals(1, backend.calls.size)
    }
    @Test fun terminalSessionPageExposesNullCursorAndAcceptsMaximumLimit() = testApplication {
        var received: PageQuery? = null
        val backend = object : BackendApplication by FakeBackend() {
            override suspend fun sessions(query: PageQuery): SessionPage { received = query; return SessionPage(emptyList(), null) }
        }
        application { installRestApi(backend) { "corr1" } }
        val response = client.get("/api/v1/sessions?limit=500")
        assertEquals(HttpStatusCode.OK, response.status)
        val page = parse(response.bodyAsText())["data"]
        assertEquals(0, page["items"].size()); assertTrue(page["nextCursor"].isNull)
        assertEquals(PageQuery(limit = 500), received)
    }
}
