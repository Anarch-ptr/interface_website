package com.filamentvision.core.repository

interface MeasurementRepository {
    /** All records commit or none do. IDs are stable, unique and compared lexicographically. */
    fun appendMeasurements(batch: List<MeasurementRecord>)
    /** Ascending (timestamp,id), cursor exclusive, inclusive optional timestamp range. */
    fun history(sessionId: String, after: HistoryCursor? = null, limit: Int = 100, from: Long? = null, through: Long? = null): MeasurementPage
}
