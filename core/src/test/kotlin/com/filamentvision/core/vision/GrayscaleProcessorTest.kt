package com.filamentvision.core.vision

import com.filamentvision.core.config.CalibrationProfile
import com.filamentvision.core.config.GrayscaleMethod
import com.filamentvision.core.frame.PixelBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class GrayscaleProcessorTest {
    @Test
    fun colorChannelsCatchAverageDefaultOrUnroundedBt601Luma() {
        val input = ArgbStage(5, 1, PixelBuffer.ints(intArrayOf(
            0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(), 0, 0x00010100,
        )))
        val processor = GrayscaleProcessor()
        val default = processor.process(input, CalibrationProfile().grayscaleMethod)
        assertArrayEquals(intArrayOf(76, 150, 29, 0, 1), IntArray(5) { default.intensities[it] })
        val luma = processor.process(input, GrayscaleMethod.LUMA_BT601)
        assertArrayEquals(intArrayOf(76, 150, 29, 0, 1), IntArray(5) { luma.intensities[it] })
        val average = processor.process(input, GrayscaleMethod.AVERAGE)
        assertArrayEquals(intArrayOf(85, 85, 85, 0, 0), IntArray(5) { average.intensities[it] })
    }
}
