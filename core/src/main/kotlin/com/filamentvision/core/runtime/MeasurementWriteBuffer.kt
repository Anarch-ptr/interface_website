package com.filamentvision.core.runtime

import com.filamentvision.core.model.OfficialMeasurement

/** Reliable batch, serialized by the owner. Failed writes retain the entire batch. */
internal class MeasurementWriteBuffer(private val batchSize: Int = 8) {
    init { require(batchSize > 0) }
    private val pending = mutableListOf<OfficialMeasurement>()
    class Batch(val measurements: List<OfficialMeasurement>)
    private var claimed: Batch? = null
    val ready get() = pending.size >= batchSize
    val size get() = pending.size
    fun add(measurement: OfficialMeasurement) { pending += measurement }
    fun claim(): Batch? {
        if (claimed == null && pending.isNotEmpty()) claimed = Batch(pending.toList())
        return claimed
    }
    fun acknowledge(batch: Batch) {
        check(claimed === batch)
        pending.subList(0, batch.measurements.size).clear()
        claimed = null
    }
}
