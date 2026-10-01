package com.filamentvision.core.runtime

import com.filamentvision.core.model.OfficialMeasurement

/** Deliberately lossy UI history, never used as a persistence queue. */
internal class RealtimeMeasurementBuffer(private val capacity: Int = 300) {
    init { require(capacity > 0) }
    private val values = ArrayDeque<OfficialMeasurement>()
    fun add(measurement: OfficialMeasurement) {
        if (values.size == capacity) values.removeFirst()
        values.addLast(measurement)
    }
    fun clear() = values.clear()
    fun snapshot(): List<OfficialMeasurement> = values.toList()
}
