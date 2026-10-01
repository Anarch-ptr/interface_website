package com.filamentvision.core.repository

interface ErrorRepository {
    fun error(id: String): ErrorRecord?
    fun allErrors(): List<ErrorRecord>
    fun saveError(error: ErrorRecord)
    fun errors(sessionId: String?): List<ErrorRecord>
}
