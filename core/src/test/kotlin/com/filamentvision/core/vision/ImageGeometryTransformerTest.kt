package com.filamentvision.core.vision

import com.filamentvision.core.config.ImageGeometry
import com.filamentvision.core.frame.*
import org.junit.Assert.*
import org.junit.Test

class ImageGeometryTransformerTest {
    @Test
    fun sourceOrientationCatchesIgnoredMetadataAndWrongRotationDirection() {
        val cases = listOf(
            90 to intArrayOf(5,3,1,6,4,2),
            180 to intArrayOf(6,5,4,3,2,1),
            270 to intArrayOf(2,4,6,1,3,5),
        )
        cases.forEach { (orientation, expected) ->
            val input = CameraFrame(CameraId("A"), 99, 100, 2, 3, PixelFormat.ARGB32, PixelBuffer.ints(intArrayOf(1,2,3,4,5,6)), orientation, false, 3)
            val result = ImageGeometryTransformer.transform(input, ImageGeometry())
            assertArrayEquals("source orientation $orientation", expected, IntArray(result.pixels.count) { result.pixels[it] })
            assertEquals(if (orientation == 180) 2 else 3, result.width)
            assertEquals(if (orientation == 180) 3 else 2, result.height)
        }
    }

    @Test
    fun sourceMirrorCatchesIgnoredMetadataOrVerticalMirrorInterpretation() {
        val input = CameraFrame(CameraId("A"), 99, 100, 2, 3, PixelFormat.ARGB32, PixelBuffer.ints(intArrayOf(1,2,3,4,5,6)), 0, true, 3)
        val result = ImageGeometryTransformer.transform(input, ImageGeometry())
        assertArrayEquals(intArrayOf(2,1,4,3,6,5), IntArray(result.pixels.count) { result.pixels[it] })
        assertEquals(2, result.width)
        assertEquals(3, result.height)
    }

    @Test
    fun asymmetricPixelsCatchWrongRotationDirectionAndMirrorAxis() {
        val cases = listOf(
            ImageGeometry(0) to intArrayOf(1,2,3,4,5,6),
            ImageGeometry(90) to intArrayOf(5,3,1,6,4,2),
            ImageGeometry(180) to intArrayOf(6,5,4,3,2,1),
            ImageGeometry(270) to intArrayOf(2,4,6,1,3,5),
            ImageGeometry(0, mirrorHorizontally = true) to intArrayOf(2,1,4,3,6,5),
            ImageGeometry(0, mirrorVertically = true) to intArrayOf(5,6,3,4,1,2),
            ImageGeometry(90, mirrorHorizontally = true) to intArrayOf(6,4,2,5,3,1),
            ImageGeometry(270, mirrorHorizontally = true, mirrorVertically = true) to intArrayOf(5,3,1,6,4,2),
        )
        val input = CameraFrame(CameraId("A"), 99, 100, 2, 3, PixelFormat.ARGB32, PixelBuffer.ints(intArrayOf(1,2,3,4,5,6)), 0, false, 3)
        cases.forEach { (geometry, expected) ->
            val result = ImageGeometryTransformer.transform(input, geometry)
            assertArrayEquals(geometry.toString(), expected, IntArray(result.pixels.count) { result.pixels[it] })
        }
    }

    @Test
    fun unsupportedRotationCatchesSilentFallbackToIdentity() {
        val input = CameraFrame(CameraId("A"), 99, 100, 1, 1, PixelFormat.ARGB32, PixelBuffer.ints(intArrayOf(1)), 0, false, 3)
        assertThrows(IllegalArgumentException::class.java) { ImageGeometryTransformer.transform(input, ImageGeometry(45)) }
    }
}
