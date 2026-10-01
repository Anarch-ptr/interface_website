package com.filamentvision.core.repository

interface AlarmEventRepository {
    fun appendAlarms(batch: List<AlarmEventRecord>)
    fun alarms(sessionId: String): List<AlarmEventRecord>
}
