package com.filamentvision.backend.preview

import com.filamentvision.core.preview.*
import com.filamentvision.core.frame.CameraFrame
import com.filamentvision.core.frame.CameraId
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class EncodedPreview(val bytes: ByteArray, val mediaType: String)
fun interface PreviewEncoder { suspend fun encode(frame: PreviewFrame): EncodedPreview }

/** One production preview slot. Admission is non-suspending and precedes sampling and encoding.
 * The caller continues to own/close the source frame; only bounded preview bytes enter the hub.
 * Encoding belongs to the lossy subscriber path, independently of reliable events.
 */
class SingleCameraPreviewPublisher(
    deviceId: String,
    private val maximumFramesPerSecond: () -> Int = { 5 },
    private val nanos: () -> Long = System::nanoTime,
    private val downsampler: PreviewFrameDownsampler = PreviewFrameDownsampler(),
) {
    val key = PreviewKey(deviceId, CameraId("camera"), PreviewStage.RAW)
    val hub = LatestPreviewHub(setOf(key), 4 * 1024 * 1024, 32)
    private var lastPublished: Long? = null

    @Synchronized
    fun tryPublish(frame: CameraFrame): Boolean {
        require(frame.cameraId == key.cameraId) { "Unknown preview camera" }
        val configuredFps = maximumFramesPerSecond()
        require(configuredFps > 0)
        val fps = configuredFps.coerceAtMost(30)
        val now = nanos()
        val interval = (1_000_000_000L + fps - 1) / fps
        if (lastPublished?.let { now - it < interval } == true) return false
        val preview = downsampler.downsample(key, frame.width, frame.height, frame.pixels,
            frame.capturedAtEpochMillis, frame.sequence)
        hub.publish(preview)
        lastPublished = now
        return true
    }
}

class PngPreviewEncoder : PreviewEncoder {
    override suspend fun encode(frame: PreviewFrame): EncodedPreview = withContext(Dispatchers.Default) {
        val bytes = frame.copyPayload()
        require(frame.width > 0 && frame.height > 0 && bytes.size.toLong() == frame.width.toLong() * frame.height * frame.format.bytesPerPixel)
        val image = BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until frame.height) for (x in 0 until frame.width) {
            val offset = (y * frame.width + x) * frame.format.bytesPerPixel
            fun channel(index: Int) = bytes[offset + index].toInt() and 255
            val argb = when (frame.format) {
                PreviewFormat.ARGB32 -> (channel(0) shl 24) or (channel(1) shl 16) or (channel(2) shl 8) or channel(3)
                PreviewFormat.GRAY8 -> (255 shl 24) or (channel(0) shl 16) or (channel(0) shl 8) or channel(0)
            }
            image.setRGB(x, y, argb)
        }
        val output = ByteArrayOutputStream()
        check(ImageIO.write(image, "png", output))
        EncodedPreview(output.toByteArray(), "image/png")
    }
}
