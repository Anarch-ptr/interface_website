package com.filamentvision.backend.preview

import com.filamentvision.core.frame.*
import com.filamentvision.core.preview.*
import org.junit.Assert.*
import org.junit.Test

class PreviewFrameDownsamplerTest {
    private val key = PreviewKey("device", CameraId("camera"), PreviewStage.RAW)

    @Test fun smallFrameKeepsExactArgbChannelsAndMetadataWithoutUpscaling() {
        val output = PreviewFrameDownsampler().downsample(key, 2, 1,
            PixelBuffer.ints(intArrayOf(0x80112233.toInt(), 0xffabcdef.toInt())), 123, 456)
        assertEquals(2, output.width); assertEquals(1, output.height)
        assertEquals(key, output.key); assertEquals(123L, output.timestamp); assertEquals(456L, output.sourceSequence)
        assertEquals(PreviewFormat.ARGB32, output.format)
        assertArrayEquals(byteArrayOf(0x80.toByte(), 0x11, 0x22, 0x33, -1, 0xab.toByte(), 0xcd.toByte(), 0xef.toByte()), output.copyPayload())
    }

    @Test fun deterministicEightMegapixelFrameSamplesDirectlyIntoOneBoundedAllocation() {
        val allocations = mutableListOf<Int>()
        val pixels = PixelBuffer.ints(IntArray(3840 * 2160) { index ->
            0xff000000.toInt() or ((index / 3840) shl 12) or (index % 3840)
        })
        val output = PreviewFrameDownsampler { allocations.add(it) }.downsample(key, 3840, 2160, pixels, 0, 7)
        assertEquals(1280, output.width); assertEquals(720, output.height)
        assertEquals(3_686_400, output.payloadSize)
        assertEquals(listOf(3_686_400), allocations)
        val bytes = output.copyPayload()
        fun pixel(x: Int, y: Int): Int {
            val offset = (y * 1280 + x) * 4
            return (0..3).fold(0) { argb, channel -> (argb shl 8) or (bytes[offset + channel].toInt() and 255) }
        }
        assertEquals(0xff000000.toInt(), pixel(0, 0))
        assertEquals(0xff003003.toInt(), pixel(1, 1))
        assertEquals(0xff4328bb.toInt(), pixel(745, 358)) // source (2235, 1074)
        assertEquals(0xff86deFD.toInt(), pixel(1279, 719)) // source (3837, 2157)
    }

    @Test fun oddLandscapePortraitAndThinFramesStayWithinBothLimits() {
        for ((width, height, expected) in listOf(
            Triple(1281, 721, 1279 to 720), Triple(721, 1281, 405 to 720),
            Triple(8192, 1, 1280 to 1), Triple(1, 8192, 1 to 720), Triple(1279, 719, 1279 to 719))) {
            val output = PreviewFrameDownsampler().downsample(key, width, height,
                PixelBuffer.ints(IntArray(width * height) { -1 }), 0, 0)
            assertEquals(expected.first, output.width); assertEquals(expected.second, output.height)
            assertTrue(output.payloadSize <= 3_686_400)
            // Integer dimensions introduce at most one destination pixel of aspect-ratio rounding.
            val ratio = minOf(1.0, 1280.0 / width, 720.0 / height)
            assertTrue(kotlin.math.abs(output.width - width * ratio) <= 1.0)
            assertTrue(kotlin.math.abs(output.height - height * ratio) <= 1.0)
        }
    }

    @Test fun invalidShapeOrMetadataFailsBeforeAllocation() {
        val allocations = mutableListOf<Int>()
        val sampler = PreviewFrameDownsampler { allocations.add(it) }
        for ((width, height) in listOf(0 to 1, -1 to 1, Int.MAX_VALUE to Int.MAX_VALUE, 2 to 2)) {
            assertThrows(IllegalArgumentException::class.java) {
                sampler.downsample(key, width, height, PixelBuffer.ints(intArrayOf(-1)), 0, 0)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            sampler.downsample(key, 1, 1, PixelBuffer.ints(intArrayOf(-1)), -1, 0)
        }
        assertEquals(emptyList<Int>(), allocations)
    }
}
