package com.filamentvision.core.vision

import com.filamentvision.core.frame.PixelBuffer

/** Full-resolution diagnostic data with read-only indexed pixels. */
data class IntensityFrame(val width: Int, val height: Int, val intensities: PixelBuffer)
data class ArgbStage(val width: Int, val height: Int, val pixels: PixelBuffer)
