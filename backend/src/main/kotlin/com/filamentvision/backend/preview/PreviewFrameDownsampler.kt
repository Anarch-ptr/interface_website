package com.filamentvision.backend.preview

import com.filamentvision.core.frame.PixelBuffer
import com.filamentvision.core.preview.PreviewFormat
import com.filamentvision.core.preview.PreviewFrame
import com.filamentvision.core.preview.PreviewKey

/** Samples borrowed pixels directly; no full-resolution array or decoded-frame lease is retained. */
class PreviewFrameDownsampler(private val allocationObserver: (Int) -> Unit = {}) {
    fun downsample(key: PreviewKey, width: Int, height: Int, pixels: PixelBuffer,
        timestamp: Long, sourceSequence: Long): PreviewFrame {
        require(width > 0 && height > 0)
        require(timestamp >= 0 && sourceSequence >= 0 && key.deviceId.isNotBlank())
        val sourceCount = Math.multiplyExact(width.toLong(), height.toLong())
        require(sourceCount == pixels.count.toLong()) { "Preview source shape does not match pixels" }

        // Compare ratios as integers so rounding can never exceed either destination bound.
        val (previewWidth, previewHeight) = when {
            width <= MAX_WIDTH && height <= MAX_HEIGHT -> width to height
            width.toLong() * MAX_HEIGHT >= height.toLong() * MAX_WIDTH ->
                MAX_WIDTH to (height.toLong() * MAX_WIDTH / width).toInt().coerceAtLeast(1)
            else -> (width.toLong() * MAX_HEIGHT / height).toInt().coerceAtLeast(1) to MAX_HEIGHT
        }
        val count = Math.multiplyExact(previewWidth, previewHeight)
        val bytes = Math.multiplyExact(count, PreviewFormat.ARGB32.bytesPerPixel)
        require(bytes <= MAX_RAW_BYTES)
        allocationObserver(bytes)
        val payload = ByteArray(bytes)
        for (y in 0 until previewHeight) {
            val sourceY = y.toLong() * height / previewHeight
            for (x in 0 until previewWidth) {
                val sourceX = x.toLong() * width / previewWidth
                val argb = pixels[(sourceY * width + sourceX).toInt()]
                val offset = (y * previewWidth + x) * 4
                for (channel in 0..3) payload[offset + channel] = (argb ushr (24 - channel * 8)).toByte()
            }
        }
        // PreviewFrame takes its own bounded immutable copy; neither array is source-sized.
        return PreviewFrame(key, timestamp, sourceSequence, previewWidth, previewHeight, PreviewFormat.ARGB32, payload)
    }

    companion object {
        const val MAX_WIDTH = 1280
        const val MAX_HEIGHT = 720
        const val MAX_RAW_BYTES = 3_686_400
    }
}
