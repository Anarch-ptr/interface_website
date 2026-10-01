package com.filamentvision.core.preview

import com.filamentvision.core.frame.CameraId

enum class PreviewStage { RAW, GRAYSCALE, THRESHOLD, EDGE }
enum class PreviewFormat(val bytesPerPixel: Int) { ARGB32(4), GRAY8(1) }
data class PreviewKey(val deviceId: String, val cameraId: CameraId, val stage: PreviewStage)

/**
 * Owned immutable decoded bytes, copied on input and output; no frame lease is retained.
 * ARGB32 uses four bytes per pixel in A, R, G, B order; GRAY8 is unsigned intensity.
 * Hub configuration limits accepted payload bytes. Width/height must exactly match payload.
 */
class PreviewFrame(
    val key: PreviewKey,
    val timestamp: Long,
    val sourceSequence: Long,
    val width: Int,
    val height: Int,
    val format: PreviewFormat,
    payload: ByteArray,
) {
    private val bytes: ByteArray

    init {
        require(key.deviceId.isNotBlank())
        require(timestamp >= 0 && sourceSequence >= 0)
        require(width > 0 && height > 0)
        val pixels = width.toLong() * height
        require(pixels <= Int.MAX_VALUE / format.bytesPerPixel)
        require(payload.size.toLong() == pixels * format.bytesPerPixel)
        bytes = payload.copyOf()
    }

    val payloadSize: Int get() = bytes.size
    fun copyPayload(): ByteArray = bytes.copyOf()
}
