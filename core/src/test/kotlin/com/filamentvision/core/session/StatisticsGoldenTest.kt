package com.filamentvision.core.session

import com.filamentvision.core.fusion.FusionReason
import com.filamentvision.core.model.MeasurementStatus
import com.filamentvision.core.model.OfficialMeasurement
import org.junit.Assert.*
import org.junit.Test

class StatisticsGoldenTest {
    @Test fun emptySnapshotRetainsZeroDefaultsAndCallerAbnormalCount() {
        // Catches infinities/NaN in an empty session and a lost caller-provided event count.
        val empty = SessionStatisticsAccumulator().snapshot(1)
        assertEquals(0L, empty.sampleCount)
        assertEquals(0.0, empty.averageDiameter, 0.0)
        assertEquals(0.0, empty.minimumDiameter, 0.0)
        assertEquals(0.0, empty.maximumDiameter, 0.0)
        assertEquals(0.0, empty.standardDeviation, 0.0)
        assertEquals(0.0, empty.averageConfidence, 0.0)
        assertEquals(1, empty.abnormalEventCount)
    }

    @Test fun statisticsUsePopulationDeviationAndPreserveAbnormalCount() {
        // Frozen Task 1 values catch sample deviation, incorrect Welford update and confidence averaging.
        val accumulator = SessionStatisticsAccumulator()
        accumulator.add(measurement(1.0, .7))
        accumulator.add(measurement(2.0, .8))
        accumulator.add(measurement(3.0, .9))
        val statistics = accumulator.snapshot(2)
        assertEquals(3L, statistics.sampleCount)
        assertEquals(2.0, statistics.averageDiameter, 0.0)
        assertEquals(1.0, statistics.minimumDiameter, 0.0)
        assertEquals(3.0, statistics.maximumDiameter, 0.0)
        assertEquals(.816496580927726, statistics.standardDeviation, 1e-12)
        assertEquals(.8, statistics.averageConfidence, 1e-12)
        assertEquals(2, statistics.abnormalEventCount)
        assertEquals(statistics.copy(abnormalEventCount = 5), accumulator.snapshot(5))
    }

    @Test fun snapshotsAreNonDestructiveAndSingleSampleHasZeroDeviation() {
        // Catches snapshot resetting state or later additions mutating a previous snapshot.
        val accumulator = SessionStatisticsAccumulator()
        accumulator.add(measurement(3.0, .9))
        val first = accumulator.snapshot(0)
        assertEquals(1L, first.sampleCount)
        assertEquals(3.0, first.averageDiameter, 0.0)
        assertEquals(3.0, first.minimumDiameter, 0.0)
        assertEquals(3.0, first.maximumDiameter, 0.0)
        assertEquals(0.0, first.standardDeviation, 0.0)
        assertEquals(.9, first.averageConfidence, 0.0)
        accumulator.add(measurement(1.0, .7))
        val second = accumulator.snapshot(1)
        assertEquals(2L, second.sampleCount)
        assertEquals(2.0, second.averageDiameter, 0.0)
        assertEquals(1.0, second.minimumDiameter, 0.0)
        assertEquals(3.0, second.maximumDiameter, 0.0)
        assertEquals(1.0, second.standardDeviation, 0.0)
        assertEquals(.8, second.averageConfidence, 1e-12)
        assertEquals(1L, first.sampleCount)
        assertEquals(3.0, first.averageDiameter, 0.0)
    }

    @Test fun largeOffsetDoesNotEraseSmallDiameterVariance() {
        // Catches replacing Welford with unstable sum-of-squares subtraction.
        val accumulator = SessionStatisticsAccumulator()
        accumulator.add(measurement(1_000_000_001.0, .7))
        accumulator.add(measurement(1_000_000_002.0, .8))
        accumulator.add(measurement(1_000_000_003.0, .9))
        val statistics = accumulator.snapshot(0)
        assertEquals(1_000_000_002.0, statistics.averageDiameter, 0.0)
        assertEquals(.816496580927726, statistics.standardDeviation, 1e-12)
    }

    private fun measurement(diameter: Double, confidence: Double) = OfficialMeasurement(
        null, null, diameter, 0.0, confidence, MeasurementStatus.NORMAL, 0, FusionReason.BOTH_CAMERAS,
    )
}
