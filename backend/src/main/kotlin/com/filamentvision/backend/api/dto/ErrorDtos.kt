package com.filamentvision.backend.api.dto

data class RevisionDetailsDto(val savedRevision: Long?, val appliedRevision: Long?)
data class ErrorDto(val code: String, val message: String, val correlationId: String, val details: Any? = null)
data class ErrorRecordDto(val id: String, val sessionId: String?, val timestamp: Long, val code: String, val message: String)
data class SnapshotDto(val id: String, val sessionId: String, val timestamp: Long, val cameraId: String, val sourceSequence: Long?, val url: String)
