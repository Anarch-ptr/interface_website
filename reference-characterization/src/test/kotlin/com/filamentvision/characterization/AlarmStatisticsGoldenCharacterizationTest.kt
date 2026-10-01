package com.filamentvision.characterization

import com.filamentvision.domain.alarm.AlarmDirection
import com.filamentvision.domain.alarm.AlarmEvaluator
import com.filamentvision.domain.alarm.AlarmLevel
import com.filamentvision.domain.session.SessionStatisticsAccumulator
import com.filamentvision.model.MeasurementStatus
import com.filamentvision.model.VisionMeasurement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AlarmStatisticsGoldenCharacterizationTest {
    @Test
    fun twoAndFivePercentBoundariesAreImmediateInBothDirections() {
        // Catches changed inequality, delayed classification, or lost direction.
        val evaluator = AlarmEvaluator(targetDiameter = 2.0)
        val cases = listOf(
            Triple(2.0399, AlarmLevel.NORMAL, AlarmDirection.NONE),
            Triple(2.0401, AlarmLevel.WARNING, AlarmDirection.HIGH),
            Triple(2.0999, AlarmLevel.WARNING, AlarmDirection.HIGH),
            Triple(2.1001, AlarmLevel.CRITICAL, AlarmDirection.HIGH),
            Triple(1.9601, AlarmLevel.NORMAL, AlarmDirection.NONE),
            Triple(1.9599, AlarmLevel.WARNING, AlarmDirection.LOW),
            Triple(1.9001, AlarmLevel.WARNING, AlarmDirection.LOW),
            Triple(1.8999, AlarmLevel.CRITICAL, AlarmDirection.LOW),
        )
        cases.forEachIndexed { index, (diameter, level, direction) ->
            val evaluation = evaluator.evaluate(measurement(diameter, timestamp = 100L + index))
            assertEquals("diameter=$diameter", level, evaluation.state.level)
            assertEquals("diameter=$diameter", direction, evaluation.state.direction)
        }
    }

    @Test
    fun transitionsUseMeasurementTimestampAndDoNotRepeatWithinState() {
        // Catches duplicate alarm events or timestamp replacement with wall-clock time.
        val evaluator = AlarmEvaluator(targetDiameter = 2.0)
        assertNull(evaluator.evaluate(measurement(2.0, 100L)).transition)

        val highWarning = evaluator.evaluate(measurement(2.05, 125L)).transition!!
        assertEquals(AlarmLevel.NORMAL, highWarning.from.level)
        assertEquals(AlarmDirection.NONE, highWarning.from.direction)
        assertEquals(AlarmLevel.WARNING, highWarning.to.level)
        assertEquals(AlarmDirection.HIGH, highWarning.to.direction)
        assertEquals(125L, highWarning.timestamp)
        assertEquals(2.05, highWarning.fusedDiameter, 0.0)
        assertNull(evaluator.evaluate(measurement(2.06, 250L)).transition)

        val highCritical = evaluator.evaluate(measurement(2.11, 375L)).transition!!
        assertEquals(AlarmLevel.WARNING, highCritical.from.level)
        assertEquals(AlarmLevel.CRITICAL, highCritical.to.level)
        assertEquals(AlarmDirection.HIGH, highCritical.to.direction)
        assertEquals(375L, highCritical.timestamp)
        assertNull(evaluator.evaluate(measurement(2.12, 500L)).transition)

        val lowCritical = evaluator.evaluate(measurement(1.88, 625L)).transition!!
        assertEquals(AlarmLevel.CRITICAL, lowCritical.from.level)
        assertEquals(AlarmDirection.HIGH, lowCritical.from.direction)
        assertEquals(AlarmLevel.CRITICAL, lowCritical.to.level)
        assertEquals(AlarmDirection.LOW, lowCritical.to.direction)
        assertEquals(625L, lowCritical.timestamp)
        assertNull(evaluator.evaluate(measurement(1.87, 750L)).transition)

        val backToNormal = evaluator.evaluate(measurement(2.0, 875L)).transition!!
        assertEquals(AlarmLevel.CRITICAL, backToNormal.from.level)
        assertEquals(AlarmLevel.NORMAL, backToNormal.to.level)
        assertEquals(AlarmDirection.NONE, backToNormal.to.direction)
        assertEquals(875L, backToNormal.timestamp)
    }

    @Test
    fun statisticsUsePopulationDeviationAndPreserveAbnormalCount() {
        // Catches sample rather than population deviation or miscounted confidence.
        val accumulator = SessionStatisticsAccumulator()
        val empty = accumulator.snapshot(abnormalEventCount = 1)
        assertEquals(0L, empty.sampleCount)
        assertEquals(0.0, empty.standardDeviation, 0.0)
        assertEquals(1, empty.abnormalEventCount)

        accumulator.add(measurement(1.0, confidence = 0.7))
        accumulator.add(measurement(2.0, confidence = 0.8))
        accumulator.add(measurement(3.0, confidence = 0.9))
        val statistics = accumulator.snapshot(abnormalEventCount = 2)
        assertEquals(3L, statistics.sampleCount)
        assertEquals(2.0, statistics.averageDiameter, 0.0)
        assertEquals(1.0, statistics.minimumDiameter, 0.0)
        assertEquals(3.0, statistics.maximumDiameter, 0.0)
        assertEquals(0.816496580927726, statistics.standardDeviation, 0.000_000_000_001)
        assertEquals(0.8, statistics.averageConfidence, 0.000_000_000_001)
        assertEquals(2, statistics.abnormalEventCount)
    }

    private fun measurement(diameter: Double, timestamp: Long = 0L, confidence: Double = 0.9) = VisionMeasurement(
        diameterA = diameter,
        diameterB = diameter,
        fusedDiameter = diameter,
        shapeDifference = 0.0,
        cameraAConfidence = confidence,
        cameraBConfidence = confidence,
        confidence = confidence,
        status = MeasurementStatus.NORMAL,
        timestamp = timestamp,
    )
}
