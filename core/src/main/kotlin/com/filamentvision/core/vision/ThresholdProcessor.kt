package com.filamentvision.core.vision

import com.filamentvision.core.config.CalibrationProfile
import com.filamentvision.core.config.ThresholdMode
import com.filamentvision.core.frame.PixelBuffer

data class ThresholdProcessingResult(val frame: IntensityFrame, val effectiveThreshold: Int)

class ThresholdProcessor {
    fun process(input: IntensityFrame, calibration: CalibrationProfile): ThresholdProcessingResult {
        val effectiveThreshold = when (calibration.thresholdMode) {
            ThresholdMode.MANUAL -> calibration.manualThreshold.coerceIn(0, 255)
            ThresholdMode.AUTOMATIC -> otsuThreshold(input, calibration)
        }
        val output = IntArray(input.intensities.count) { index ->
            if (input.intensities[index] >= effectiveThreshold) 255 else 0
        }
        return ThresholdProcessingResult(input.copy(intensities = PixelBuffer.ints(output)), effectiveThreshold)
    }

    private fun otsuThreshold(input: IntensityFrame, calibration: CalibrationProfile): Int {
        val left = calibration.roiLeft.takeIf { it in 0 until input.width } ?: 0
        val top = calibration.roiTop.takeIf { it in 0 until input.height } ?: 0
        val right = calibration.roiRight.takeIf { it in (left + 1)..input.width } ?: input.width
        val bottom = calibration.roiBottom.takeIf { it in (top + 1)..input.height } ?: input.height
        val histogram = IntArray(256)
        for (y in top until bottom) {
            for (x in left until right) histogram[input.intensities[y * input.width + x].coerceIn(0, 255)]++
        }
        val total = histogram.sum()
        var weightedSum = 0.0
        histogram.indices.forEach { weightedSum += it * histogram[it].toDouble() }
        var backgroundWeight = 0
        var backgroundSum = 0.0
        var bestVariance = -1.0
        var bestThreshold = 128
        histogram.indices.forEach { value ->
            backgroundWeight += histogram[value]
            if (backgroundWeight == 0) return@forEach
            val foregroundWeight = total - backgroundWeight
            if (foregroundWeight == 0) return@forEach
            backgroundSum += value * histogram[value].toDouble()
            val backgroundMean = backgroundSum / backgroundWeight
            val foregroundMean = (weightedSum - backgroundSum) / foregroundWeight
            val variance = backgroundWeight.toDouble() * foregroundWeight *
                (backgroundMean - foregroundMean) * (backgroundMean - foregroundMean)
            if (variance > bestVariance) {
                bestVariance = variance
                bestThreshold = value
            }
        }
        return (bestThreshold + 1).coerceIn(1, 255)
    }
}
