package com.filamentvision.backend.api

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

/** Process-level readiness only; it deliberately has no device or database dependency. */
fun Application.installHealthApi() {
    routing {
        get("/api/v1/health") {
            call.respondText("{\"status\":\"ok\"}", ContentType.Application.Json, HttpStatusCode.OK)
        }
    }
}
