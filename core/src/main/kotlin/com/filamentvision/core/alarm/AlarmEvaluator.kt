package com.filamentvision.core.alarm

import com.filamentvision.core.model.OfficialMeasurement
import kotlin.math.abs

enum class AlarmLevel { NORMAL, WARNING, CRITICAL }
enum class AlarmDirection { NONE, LOW, HIGH }

data class AlarmState(
    val level: AlarmLevel = AlarmLevel.NORMAL,
    val direction: AlarmDirection = AlarmDirection.NONE,
    val deviationPercent: Double = 0.0,
)

data class AlarmTransition(val from: AlarmState, val to: AlarmState, val timestamp: Long, val fusedDiameter: Double)
data class AlarmEvaluation(val state: AlarmState, val transition: AlarmTransition?)

/** Immediate baseline classification; one evaluator is owned by one serialized monitoring session. */
class AlarmEvaluator(
    private val targetDiameter: Double,
    private val warningPercent: Double = 0.02,
    private val criticalPercent: Double = 0.05,
) {
    var currentState: AlarmState = AlarmState()
        private set

    init {
        require(targetDiameter > 0.0)
        require(warningPercent in 0.0..criticalPercent)
    }

    fun evaluate(measurement: OfficialMeasurement): AlarmEvaluation {
        val deviation = (measurement.fusedDiameter - targetDiameter) / targetDiameter
        val magnitude = abs(deviation)
        val nextState = AlarmState(
            level = when {
                magnitude >= criticalPercent -> AlarmLevel.CRITICAL
                magnitude >= warningPercent -> AlarmLevel.WARNING
                else -> AlarmLevel.NORMAL
            },
            direction = when {
                magnitude < warningPercent -> AlarmDirection.NONE
                deviation < 0.0 -> AlarmDirection.LOW
                else -> AlarmDirection.HIGH
            },
            deviationPercent = deviation * 100.0,
        )
        val previous = currentState
        currentState = nextState
        val transition = if (previous.level != nextState.level || previous.direction != nextState.direction) {
            AlarmTransition(previous, nextState, measurement.timestamp, measurement.fusedDiameter)
        } else null
        return AlarmEvaluation(nextState, transition)
    }
}
