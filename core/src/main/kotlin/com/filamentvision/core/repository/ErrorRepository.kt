package com.filamentvision.core.repository

interface ErrorRepository {
    fun saveError(error: ErrorRecord)
    fun errors(sessionId: String?): List<ErrorRecord>
}
