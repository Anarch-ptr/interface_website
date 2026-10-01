package com.filamentvision.core.vision

import com.filamentvision.core.config.ImageGeometry
import com.filamentvision.core.frame.CameraFrame
import com.filamentvision.core.frame.PixelBuffer

object ImageGeometryTransformer {
    /** Normalizes source metadata first, then applies configured geometry in normalized coordinates. */
    fun transform(frame: CameraFrame, geometry: ImageGeometry): ArgbStage {
        val source = ArgbStage(frame.width, frame.height, frame.pixels)
        if (frame.orientationDegrees == 0 && !frame.mirrored) return transformStage(source, geometry)
        val normalized = transformStage(source, ImageGeometry(
            rotationDegrees = frame.orientationDegrees,
            mirrorHorizontally = frame.mirrored,
        ))
        return if (geometry == ImageGeometry()) normalized else transformStage(normalized, geometry)
    }

    /** Each transform mirrors its input coordinates before rotating, preserving the reference formula. */
    private fun transformStage(frame: ArgbStage, geometry: ImageGeometry): ArgbStage {
        require(geometry.rotationDegrees in setOf(0, 90, 180, 270)) { "Rotation must be 0, 90, 180, or 270 degrees" }
        val swapped = geometry.rotationDegrees == 90 || geometry.rotationDegrees == 270
        val outputWidth = if (swapped) frame.height else frame.width
        val outputHeight = if (swapped) frame.width else frame.height
        val output = IntArray(frame.pixels.count)
        for (sourceY in 0 until frame.height) {
            for (sourceX in 0 until frame.width) {
                val x = if (geometry.mirrorHorizontally) frame.width - 1 - sourceX else sourceX
                val y = if (geometry.mirrorVertically) frame.height - 1 - sourceY else sourceY
                val destination = when (geometry.rotationDegrees) {
                    0 -> x to y
                    90 -> frame.height - 1 - y to x
                    180 -> frame.width - 1 - x to frame.height - 1 - y
                    270 -> y to frame.width - 1 - x
                    else -> error("Rotation was validated")
                }
                output[destination.second * outputWidth + destination.first] = frame.pixels[sourceY * frame.width + sourceX]
            }
        }
        return ArgbStage(outputWidth, outputHeight, PixelBuffer.ints(output))
    }
}
