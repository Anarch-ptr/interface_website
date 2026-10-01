package com.filamentvision.core.alarm

import com.filamentvision.core.fusion.FusionReason
import com.filamentvision.core.model.MeasurementStatus
import com.filamentvision.core.model.OfficialMeasurement
import org.junit.Assert.*
import org.junit.Test

class AlarmGoldenTest {
    @Test fun twoAndFivePercentBoundariesAreImmediateInBothDirections() {
        // Catches changed thresholds/direction or any added delay.
        val evaluator = AlarmEvaluator(2.0)
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
            val state = evaluator.evaluate(measurement(diameter, 100L + index)).state
            assertEquals("diameter=$diameter", level, state.level)
            assertEquals("diameter=$diameter", direction, state.direction)
            assertEquals(state, evaluator.currentState)
        }
    }

    @Test fun exactThresholdsIncludeEqualityAndReportSignedPercentage() {
        // Integer diameter differences avoid decimal subtraction ambiguity at the threshold.
        // Catches strict > thresholds or fractional rather than percentage deviation.
        val evaluator = AlarmEvaluator(100.0)
        listOf(
            Triple(102.0, AlarmLevel.WARNING, 2.0), Triple(98.0, AlarmLevel.WARNING, -2.0),
            Triple(105.0, AlarmLevel.CRITICAL, 5.0), Triple(95.0, AlarmLevel.CRITICAL, -5.0),
        ).forEach { (diameter, level, deviation) ->
            val state = evaluator.evaluate(measurement(diameter)).state
            assertEquals(level, state.level)
            assertEquals(if (deviation < 0) AlarmDirection.LOW else AlarmDirection.HIGH, state.direction)
            assertEquals(deviation, state.deviationPercent, 0.0)
        }
    }

    @Test fun transitionsUseMeasurementEvidenceAndOnlyLevelOrDirectionChanges() {
        // Catches duplicate events, wall-clock timestamps, lost previous state or direction-only transitions.
        val evaluator = AlarmEvaluator(2.0)
        assertNull(evaluator.evaluate(measurement(2.0, 100)).transition)
        val warning = evaluator.evaluate(measurement(2.05, 125)).transition!!
        assertEquals(AlarmLevel.NORMAL, warning.from.level)
        assertEquals(AlarmDirection.NONE, warning.from.direction)
        assertEquals(AlarmLevel.WARNING, warning.to.level)
        assertEquals(AlarmDirection.HIGH, warning.to.direction)
        assertEquals(125L, warning.timestamp)
        assertEquals(2.05, warning.fusedDiameter, 0.0)
        val repeatedWarning = evaluator.evaluate(measurement(2.06, 250))
        assertNull(repeatedWarning.transition)
        assertEquals(3.0, repeatedWarning.state.deviationPercent, 1e-12)
        val critical = evaluator.evaluate(measurement(2.11, 375)).transition!!
        assertEquals(repeatedWarning.state, critical.from)
        assertEquals(AlarmLevel.CRITICAL, critical.to.level)
        assertEquals(AlarmDirection.HIGH, critical.to.direction)
        assertEquals(375L, critical.timestamp)
        assertEquals(2.11, critical.fusedDiameter, 0.0)
        assertNull(evaluator.evaluate(measurement(2.12, 500)).transition)
        val low = evaluator.evaluate(measurement(1.88, 625)).transition!!
        assertEquals(AlarmLevel.CRITICAL, low.from.level)
        assertEquals(AlarmDirection.HIGH, low.from.direction)
        assertEquals(AlarmLevel.CRITICAL, low.to.level)
        assertEquals(AlarmDirection.LOW, low.to.direction)
        assertEquals(625L, low.timestamp)
        assertEquals(1.88, low.fusedDiameter, 0.0)
        assertNull(evaluator.evaluate(measurement(1.87, 750)).transition)
        val normal = evaluator.evaluate(measurement(2.0, 875)).transition!!
        assertEquals(AlarmLevel.CRITICAL, normal.from.level)
        assertEquals(AlarmLevel.NORMAL, normal.to.level)
        assertEquals(AlarmDirection.NONE, normal.to.direction)
        assertEquals(875L, normal.timestamp)
        assertEquals(2.0, normal.fusedDiameter, 0.0)
    }

    @Test fun invalidTargetAndThresholdOrderAreRejected() {
        // Catches division by zero and an unreachable warning band.
        assertThrows(IllegalArgumentException::class.java) { AlarmEvaluator(0.0) }
        assertThrows(IllegalArgumentException::class.java) { AlarmEvaluator(-1.0) }
        assertThrows(IllegalArgumentException::class.java) { AlarmEvaluator(2.0, .06, .05) }
        assertThrows(IllegalArgumentException::class.java) { AlarmEvaluator(2.0, -.01, .05) }
    }

    private fun measurement(diameter: Double, timestamp: Long = 0) = OfficialMeasurement(
        null, null, diameter, 0.0, .9, MeasurementStatus.NORMAL, timestamp, FusionReason.BOTH_CAMERAS,
    )
}
