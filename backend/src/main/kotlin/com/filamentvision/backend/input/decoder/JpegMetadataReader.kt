package com.filamentvision.backend.input.decoder

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.Closeable
import javax.imageio.ImageIO
import javax.imageio.ImageReader
import javax.imageio.stream.ImageInputStream

data class JpegMetadata(val width: Int, val height: Int)

fun interface JpegMetadataReader {
    fun open(payload: ByteArray): JpegReadSession?
}

interface JpegReadSession : Closeable {
    val metadata: JpegMetadata
    fun readArgb(allocator: (Int) -> IntArray): IntArray
}

object ImageIoJpegMetadataReader : JpegMetadataReader {
    override fun open(payload: ByteArray): JpegReadSession? {
        val input = ImageIO.createImageInputStream(ByteArrayInputStream(payload)) ?: return null
        val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull()
        if (reader == null) {
            input.close()
            return null
        }
        return try {
            reader.input = input
            ImageIoJpegReadSession(reader, input, JpegMetadata(reader.getWidth(0), reader.getHeight(0)))
        } catch (error: Throwable) {
            reader.dispose()
            input.close()
            null
        }
    }
}

private class ImageIoJpegReadSession(
    private val reader: ImageReader,
    private val input: ImageInputStream,
    override val metadata: JpegMetadata,
) : JpegReadSession {
    override fun readArgb(allocator: (Int) -> IntArray): IntArray {
        val image: BufferedImage = reader.read(0)
        check(image.width == metadata.width && image.height == metadata.height)
        val pixels = allocator(metadata.width * metadata.height)
        image.getRGB(0, 0, metadata.width, metadata.height, pixels, 0, metadata.width)
        return pixels
    }

    override fun close() {
        reader.dispose()
        input.close()
    }
}
