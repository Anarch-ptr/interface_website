package com.filamentvision.core.vision

import com.filamentvision.core.frame.PixelBuffer
import kotlin.math.abs

class EdgeMapProcessor {
    fun process(grayscale: IntensityFrame, threshold: IntensityFrame, edgeSensitivity: Int): IntensityFrame {
        require(grayscale.width == threshold.width && grayscale.height == threshold.height)
        val sensitivity = edgeSensitivity.coerceIn(0, 255)
        val output = IntArray(grayscale.intensities.count)
        for (y in 0 until grayscale.height) {
            val rowStart = y * grayscale.width
            for (x in 0 until grayscale.width) {
                val index = rowStart + x
                val leftEdge = x > 0 &&
                    threshold.intensities[index] != threshold.intensities[index - 1] &&
                    abs(grayscale.intensities[index] - grayscale.intensities[index - 1]) >= sensitivity
                val rightEdge = x + 1 < grayscale.width &&
                    threshold.intensities[index] != threshold.intensities[index + 1] &&
                    abs(grayscale.intensities[index] - grayscale.intensities[index + 1]) >= sensitivity
                output[index] = if (leftEdge || rightEdge) 255 else 0
            }
        }
        return grayscale.copy(intensities = PixelBuffer.ints(output))
    }
}
