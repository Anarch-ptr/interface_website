package com.filamentvision.core.repository

interface AlarmEventRepository {
    /** Atomic append. An existing stable ID succeeds only for an exact full-content retry. */
    fun appendAlarms(batch: List<AlarmEventRecord>)
    fun alarms(sessionId: String): List<AlarmEventRecord>
}
