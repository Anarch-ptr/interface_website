package com.filamentvision.core.repository

import com.filamentvision.core.runtime.RuntimeSession
import com.filamentvision.core.runtime.SessionFinalization

interface SessionRepository {
    fun sessions(after: HistoryCursor? = null, limit: Int = 100, from: Long? = null, through: Long? = null): List<SessionRecord>
    fun createSession(session: RuntimeSession)
    fun finalizeSession(finalization: SessionFinalization)
    fun session(id: String): SessionRecord?
}
