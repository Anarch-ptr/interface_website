package com.filamentvision.core.repository

interface MeasurementRepository {
    /** All records commit or none do. Exact full-content retries of stable IDs succeed;
     * a reused ID with different evidence conflicts. IDs compare lexicographically. */
    fun appendMeasurements(batch: List<MeasurementRecord>)
    /** Ascending (timestamp,id), cursor exclusive, inclusive optional timestamp range. */
    fun history(sessionId: String, after: HistoryCursor? = null, limit: Int = 100, from: Long? = null, through: Long? = null): MeasurementPage
}
