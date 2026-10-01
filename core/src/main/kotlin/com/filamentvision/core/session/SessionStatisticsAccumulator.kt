package com.filamentvision.core.session

import com.filamentvision.core.model.OfficialMeasurement
import kotlin.math.sqrt

data class SessionStatistics(
    val sampleCount: Long = 0,
    val averageDiameter: Double = 0.0,
    val minimumDiameter: Double = 0.0,
    val maximumDiameter: Double = 0.0,
    val standardDeviation: Double = 0.0,
    val averageConfidence: Double = 0.0,
    val abnormalEventCount: Int = 0,
)

/** Incremental Welford population statistics; the session owner serializes additions and snapshots. */
class SessionStatisticsAccumulator {
    private var sampleCount = 0L
    private var meanDiameter = 0.0
    private var diameterM2 = 0.0
    private var minimumDiameter = Double.POSITIVE_INFINITY
    private var maximumDiameter = Double.NEGATIVE_INFINITY
    private var confidenceSum = 0.0

    fun add(measurement: OfficialMeasurement) {
        sampleCount += 1
        val delta = measurement.fusedDiameter - meanDiameter
        meanDiameter += delta / sampleCount
        val deltaAfterMeanUpdate = measurement.fusedDiameter - meanDiameter
        diameterM2 += delta * deltaAfterMeanUpdate
        minimumDiameter = minOf(minimumDiameter, measurement.fusedDiameter)
        maximumDiameter = maxOf(maximumDiameter, measurement.fusedDiameter)
        confidenceSum += measurement.confidence
    }

    fun snapshot(abnormalEventCount: Int): SessionStatistics {
        if (sampleCount == 0L) return SessionStatistics(abnormalEventCount = abnormalEventCount)
        return SessionStatistics(
            sampleCount = sampleCount,
            averageDiameter = meanDiameter,
            minimumDiameter = minimumDiameter,
            maximumDiameter = maximumDiameter,
            standardDeviation = sqrt(diameterM2 / sampleCount),
            averageConfidence = confidenceSum / sampleCount,
            abnormalEventCount = abnormalEventCount,
        )
    }
}
