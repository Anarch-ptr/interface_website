package com.filamentvision.core.runtime

import com.filamentvision.core.model.OfficialMeasurement

/** Reliable batch, serialized by the owner. Failed writes retain the entire batch. */
internal class MeasurementWriteBuffer(private val batchSize: Int = 8) {
    init { require(batchSize > 0) }
    private val pending = mutableListOf<OfficialMeasurement>()
    val ready get() = pending.size >= batchSize
    fun add(measurement: OfficialMeasurement) { pending += measurement }
    suspend fun flush(write: suspend (List<OfficialMeasurement>) -> Unit) {
        if (pending.isEmpty()) return
        write(pending.toList())
        pending.clear()
    }
}
