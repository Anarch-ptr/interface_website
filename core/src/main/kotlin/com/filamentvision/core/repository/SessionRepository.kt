package com.filamentvision.core.repository

import com.filamentvision.core.runtime.RuntimeSession
import com.filamentvision.core.runtime.SessionFinalization

interface SessionRepository {
    fun createSession(session: RuntimeSession)
    fun finalizeSession(finalization: SessionFinalization)
    fun session(id: String): SessionRecord?
}
