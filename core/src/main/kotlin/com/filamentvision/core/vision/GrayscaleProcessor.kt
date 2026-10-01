package com.filamentvision.core.vision

import com.filamentvision.core.config.GrayscaleMethod
import com.filamentvision.core.frame.PixelBuffer

class GrayscaleProcessor {
    fun process(input: ArgbStage, method: GrayscaleMethod): IntensityFrame {
        val output = IntArray(input.pixels.count) { index ->
            val pixel = input.pixels[index]
            val red = pixel ushr 16 and 0xFF
            val green = pixel ushr 8 and 0xFF
            val blue = pixel and 0xFF
            when (method) {
                GrayscaleMethod.LUMA_BT601 -> (299 * red + 587 * green + 114 * blue + 500) / 1_000
                GrayscaleMethod.AVERAGE -> (red + green + blue) / 3
            }
        }
        return IntensityFrame(input.width, input.height, PixelBuffer.ints(output))
    }
}
