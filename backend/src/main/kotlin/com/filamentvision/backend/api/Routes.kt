package com.filamentvision.backend.api

import com.filamentvision.backend.api.dto.*
import com.filamentvision.backend.application.*
import com.filamentvision.core.config.ConfigurationApplyOutcome
import com.filamentvision.core.repository.RepositoryException
import com.filamentvision.core.repository.RepositoryFailure
import com.filamentvision.core.runtime.StartOutcome
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.UUID
import kotlinx.coroutines.CancellationException

/** Installs only HTTP adapters; the caller supplies an already-owned application facade. */
fun Application.installRestApi(backend: BackendApplication, correlationId: () -> String = { UUID.randomUUID().toString() }) {
    routing {
        route("/api/v1") {
            route("/devices/{deviceId}") {
                get("/state") { api(correlationId) { reply(it, backend.state(id("deviceId")).toDto()) } }
                get("/measurements/latest") { api(correlationId) { reply(it, backend.latest(id("deviceId"))?.toDto()) } }
                post("/monitoring/start") { api(correlationId) { correlation ->
                    val request = body<StartRequest>(); command(request.commandId)
                    valid(request.targetDiameter == null || request.targetDiameter.isFinite() && request.targetDiameter > 0)
                    valid(request.appliedRevision == null || request.appliedRevision > 0)
                    val result = backend.start(id("deviceId"), request.commandId, request.targetDiameter, request.appliedRevision)
                    when (result.outcome) {
                        StartOutcome.STARTED, StartOutcome.ALREADY_RUNNING -> reply(correlation, MonitoringCommandDto(result.outcome.name, result.session?.toDto()))
                        StartOutcome.INTERRUPTED -> throw ApplicationFailure(ApplicationFailureCode.CONFLICT)
                        StartOutcome.FAILED, StartOutcome.CLOSED -> throw ApplicationFailure(ApplicationFailureCode.UNAVAILABLE)
                    }
                } }
                post("/monitoring/stop") { api(correlationId) { correlation ->
                    val request = body<CommandRequest>(); command(request.commandId)
                    val result = backend.stop(id("deviceId"), request.commandId)
                    if (result.error != null) throw ApplicationFailure(ApplicationFailureCode.UNAVAILABLE)
                    reply(correlation, MonitoringCommandDto("STOPPED", result.session?.toDto()))
                } }
                get("/configuration") { api(correlationId) { reply(it, backend.configuration(id("deviceId")).toDto()) } }
                put("/configuration") { api(correlationId) { correlation ->
                    val request = body<SaveConfigurationRequest>(); command(request.commandId)
                    configurationReply(correlation, backend.save(id("deviceId"), request.commandId, request.configuration.toDomain()))
                } }
                post("/configuration/apply") { api(correlationId) { correlation ->
                    val request = body<ApplyConfigurationRequest>(); command(request.commandId)
                    valid(request.revision > 0 && request.expectedSavedRevision > 0)
                    configurationReply(correlation, backend.apply(id("deviceId"), request.commandId, request.revision, request.expectedSavedRevision))
                } }
                post("/configuration/save-and-apply") { api(correlationId) { correlation ->
                    val request = body<SaveConfigurationRequest>(); command(request.commandId)
                    configurationReply(correlation, backend.saveAndApply(id("deviceId"), request.commandId, request.configuration.toDomain()))
                } }
                get("/udp-input-configuration") { api(correlationId) {
                    reply(it, backend.udpInputConfiguration(id("deviceId")).toDto())
                } }
                put("/udp-input-configuration") { api(correlationId) { correlation ->
                    val request = body<SaveUdpInputConfigurationRequest>(); command(request.commandId)
                    reply(correlation, backend.saveUdpInput(id("deviceId"), request.commandId, request.configuration.toDomain()).toDto())
                } }
                post("/udp-input-configuration/apply") { api(correlationId) { correlation ->
                    val request = body<ApplyUdpInputConfigurationRequest>(); command(request.commandId)
                    valid(request.revision > 0 && request.expectedSavedRevision > 0)
                    reply(correlation, backend.applyUdpInput(id("deviceId"), request.commandId, request.revision,
                        request.expectedSavedRevision).toDto())
                } }
                post("/calibration/drafts") { api(correlationId) { correlation ->
                    val request = body<CreateDraftRequest>(); command(request.commandId); valid(request.cameraId.isNotBlank())
                    reply(correlation, backend.createDraft(id("deviceId"), request.commandId, request.cameraId, request.configuration.toDomain()).toDto(), HttpStatusCode.Created)
                } }
                patch("/calibration/drafts/{draftId}") { api(correlationId) { correlation ->
                    val request = body<UpdateDraftRequest>(); command(request.commandId); valid(request.expectedRevision > 0)
                    reply(correlation, backend.updateDraft(id("deviceId"), id("draftId"), request.commandId, request.expectedRevision, request.configuration.toDomain()).toDto())
                } }
                delete("/calibration/drafts/{draftId}") { api(correlationId) { correlation ->
                    val request = body<CommandRequest>(); command(request.commandId)
                    backend.deleteDraft(id("deviceId"), id("draftId"), request.commandId); reply(correlation, DeletedDto())
                } }
                get("/cameras") { api(correlationId) { reply(it, backend.cameras(id("deviceId")).map { camera -> camera.toDto() }) } }
                get("/diagnostics") { api(correlationId) { reply(it, backend.diagnostics(id("deviceId")).toDto()) } }
                get("/cameras/{cameraId}/udp-diagnostics") { api(correlationId) { correlation ->
                    val camera = id("cameraId")
                    reply(correlation, backend.udpDiagnostics(id("deviceId"), camera).toDto(camera))
                } }
            }
            get("/sessions") { api(correlationId) { correlation ->
                val page = backend.sessions(pageQuery("sessions"))
                reply(correlation, PageDto(page.items.map { it.toDto() }, page.nextCursor?.encodeCursor("sessions")))
            } }
            get("/sessions/{sessionId}") { api(correlationId) { reply(it, backend.session(id("sessionId")).toDto()) } }
            get("/sessions/{sessionId}/measurements") { api(correlationId) { correlation ->
                val sessionId = id("sessionId"); val scope = "history:$sessionId"
                val page = backend.history(sessionId, pageQuery(scope))
                reply(correlation, PageDto(page.items.map { it.toDto() }, page.nextCursor?.encodeCursor(scope)))
            } }
            get("/sessions/{sessionId}/alarms") { api(correlationId) { reply(it, backend.alarms(id("sessionId")).map { alarm -> alarm.toDto() }) } }
            get("/errors") { api(correlationId) { correlation ->
                val sessionId = query("sessionId"); valid(sessionId == null || sessionId.isNotBlank())
                reply(correlation, backend.errors(sessionId).map { it.toDto() })
            } }
            get("/errors/{errorId}/snapshots") { api(correlationId) { reply(it, backend.snapshots(id("errorId")).map { snapshot -> snapshot.toDto() }) } }
            get("/snapshots/{snapshotId}") { api(correlationId) {
                val snapshot = backend.snapshot(id("snapshotId"))
                check(snapshot.mediaType in setOf("image/png", "image/jpeg"))
                check(snapshot.filename.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,100}\\.(png|jpg|jpeg)")))
                call.response.header(HttpHeaders.ContentDisposition, ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, snapshot.filename).toString())
                call.response.header("X-Content-Type-Options", "nosniff")
                call.respondBytes(snapshot.bytes, ContentType.parse(snapshot.mediaType))
            } }
        }
    }
}

private fun valid(condition: Boolean) { if (!condition) throw ApplicationFailure(ApplicationFailureCode.INVALID) }
private fun command(value: String) = valid(value.isNotBlank())
private fun RoutingContext.id(name: String) = call.parameters[name].orEmpty().also { valid(it.isNotBlank()) }
private fun RoutingContext.query(name: String): String? {
    val values = call.request.queryParameters.getAll(name)
    valid(values == null || values.size == 1)
    return values?.single()
}
private fun RoutingContext.pageQuery(scope: String): PageQuery {
    fun number(name: String): Long? = query(name)?.let { value -> value.toLongOrNull()?.also { valid(it >= 0) } ?: throw ApplicationFailure(ApplicationFailureCode.INVALID) }
    val limit = query("limit")?.toIntOrNull() ?: if (query("limit") == null) 100 else throw ApplicationFailure(ApplicationFailureCode.INVALID)
    valid(limit in 1..500)
    val from = number("from"); val through = number("through")
    valid(from == null || through == null || from <= through)
    return PageQuery(query("cursor")?.let { decodeCursor(it, scope) }, limit, from, through)
}
private suspend inline fun <reified T> RoutingContext.body(): T {
    val mediaType = try { call.request.contentType() }
    catch (_: BadContentTypeFormatException) { throw unsupportedMedia() }
    if (mediaType.withoutParameters() != ContentType.Application.Json) throw unsupportedMedia()
    // Resolve explicitly: Ktor's charset() helper silently treats unknown names as absent.
    val charset = try { mediaType.parameter("charset")?.let { Charset.forName(it) } ?: Charsets.UTF_8 }
    catch (_: IllegalArgumentException) { throw unsupportedMedia() }
    val bytes = call.receive<ByteArray>()
    val text = try {
        charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) { throw badRequest() }
    return decodeRequest(text)
}
private fun unsupportedMedia() = ApiHttpFailure(HttpStatusCode.UnsupportedMediaType, "UNSUPPORTED_MEDIA_TYPE", "Valid JSON content type and supported charset required")
private suspend fun RoutingContext.reply(correlation: String, data: Any?, status: HttpStatusCode = HttpStatusCode.OK) {
    call.respondText(apiJson.writeValueAsString(ApiResponse(correlation, data)), ContentType.Application.Json, status)
}
private suspend fun RoutingContext.configurationReply(correlation: String, view: ConfigurationView) {
    if (view.outcome == ConfigurationApplyOutcome.REQUIRES_STOP)
        throw ApiHttpFailure(HttpStatusCode.Conflict, "REQUIRES_STOP", "Stop monitoring before applying this revision", RevisionDetailsDto(view.revisions.savedRevision, view.revisions.appliedRevision))
    reply(correlation, view.toDto())
}
private class ApiHttpFailure(val status: HttpStatusCode, val code: String, val safeMessage: String, val details: Any? = null) : RuntimeException()
private suspend fun RoutingContext.api(generator: () -> String, block: suspend (String) -> Unit) {
    val correlation = generator().takeIf { it.matches(Regex("[A-Za-z0-9_.:-]{1,128}")) } ?: UUID.randomUUID().toString()
    call.response.header("X-Correlation-Id", correlation)
    try { block(correlation) }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: Exception) {
        val mapped = when (failure) {
            is ApiHttpFailure -> failure
            is UdpInputValidationException -> ApiHttpFailure(HttpStatusCode.BadRequest, "INVALID_REQUEST", "Invalid request",
                UdpValidationDetailsDto(failure.errors.map { FieldValidationErrorDto(it.path, it.message) }))
            is ApplicationFailure -> when (failure.code) {
                ApplicationFailureCode.INVALID -> badRequest()
                ApplicationFailureCode.NOT_FOUND -> ApiHttpFailure(HttpStatusCode.NotFound, "NOT_FOUND", "Resource not found")
                ApplicationFailureCode.CONFLICT -> ApiHttpFailure(HttpStatusCode.Conflict, "CONFLICT", "Command conflicts with current state")
                ApplicationFailureCode.REQUIRES_STOP -> ApiHttpFailure(HttpStatusCode.Conflict, "REQUIRES_STOP", "Stop monitoring before applying this revision")
                ApplicationFailureCode.UNAVAILABLE -> ApiHttpFailure(HttpStatusCode.ServiceUnavailable, "UNAVAILABLE", "Operation unavailable")
            }
            is RepositoryException -> when (failure.failure) {
                RepositoryFailure.INVALID -> badRequest()
                RepositoryFailure.NOT_FOUND -> ApiHttpFailure(HttpStatusCode.NotFound, "NOT_FOUND", "Resource not found")
                RepositoryFailure.CONFLICT -> ApiHttpFailure(HttpStatusCode.Conflict, "CONFLICT", "Command conflicts with current state")
                RepositoryFailure.STORAGE -> ApiHttpFailure(HttpStatusCode.ServiceUnavailable, "STORAGE_UNAVAILABLE", "Storage unavailable")
            }
            else -> ApiHttpFailure(HttpStatusCode.InternalServerError, "INTERNAL_ERROR", "Internal server error")
        }
        call.respondText(apiJson.writeValueAsString(ErrorDto(mapped.code, mapped.safeMessage, correlation, mapped.details)), ContentType.Application.Json, mapped.status)
    }
}
private fun badRequest() = ApiHttpFailure(HttpStatusCode.BadRequest, "INVALID_REQUEST", "Invalid request")
