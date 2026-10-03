package com.filamentvision.core.vision

import com.filamentvision.core.config.VisionConfiguration
import com.filamentvision.core.frame.CameraFrame

interface VisionAnalyzer {
    fun process(frame: CameraFrame, configuration: VisionConfiguration, decodeNanos: Long = 0): VisionResult
}
