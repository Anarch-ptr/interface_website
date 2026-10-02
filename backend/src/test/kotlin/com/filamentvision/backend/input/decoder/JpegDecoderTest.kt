package com.filamentvision.backend.input.decoder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

class JpegDecoderTest {
    @Test
    fun decodesValidJpegAndReturnsReusableMetadata() {
        val bytes = jpeg2x2()
        val result = JpegDecoder(maxFrameBytes = bytes.size).decode(bytes) as PayloadDecodeResult.Success
        val image = (result.value as DecodedInput.Image).image
        assertEquals(2, image.width)
        assertEquals(2, image.height)
        assertEquals(ImageDecodeMetadata(2, 2, bytes.size), result.imageMetadata)
    }

    @Test
    fun rejectsInvalidCompressedSizeAndExpectedDimensionMismatch() {
        assertEquals(PayloadDecodeError.MALFORMED_PAYLOAD, (JpegDecoder(100).decode(byteArrayOf(1, 2)) as PayloadDecodeResult.Failure).error)
        val bytes = jpeg2x2()
        assertEquals(PayloadDecodeError.INVALID_PAYLOAD_SIZE, (JpegDecoder(bytes.size - 1).decode(bytes) as PayloadDecodeResult.Failure).error)
        assertEquals(PayloadDecodeError.DIMENSION_MISMATCH, (JpegDecoder(bytes.size, expectedWidth = 3).decode(bytes) as PayloadDecodeResult.Failure).error)
    }

    @Test
    fun oversizedMetadataIsRejectedBeforeRasterDecodeOrAllocation() {
        var rasterReads = 0
        var allocations = 0
        val session = object : JpegReadSession {
            override val metadata = JpegMetadata(100_000, 100_000)
            override fun readArgb(allocator: (Int) -> IntArray): IntArray { rasterReads++; return allocator(1) }
            override fun close() = Unit
        }
        val decoder = JpegDecoder(
            maxFrameBytes = 10,
            metadataReader = JpegMetadataReader { session },
            allocator = { size -> allocations++; IntArray(size) },
        )
        assertEquals(PayloadDecodeError.ALLOCATION_LIMIT, (decoder.decode(byteArrayOf(1)) as PayloadDecodeResult.Failure).error)
        assertEquals(0, rasterReads)
        assertEquals(0, allocations)
    }

    @Test
    fun validReaderIsDecodedExactlyOnce() {
        var reads = 0
        val session = object : JpegReadSession {
            override val metadata = JpegMetadata(1, 1)
            override fun readArgb(allocator: (Int) -> IntArray): IntArray { reads++; return intArrayOf(-1) }
            override fun close() = Unit
        }
        val result = JpegDecoder(10, metadataReader = JpegMetadataReader { session }).decode(byteArrayOf(1))
        assertTrue(result is PayloadDecodeResult.Success)
        assertEquals(1, reads)
    }

    private fun jpeg2x2(): ByteArray {
        val image = BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)
        image.setRGB(0, 0, 0x112233)
        return ByteArrayOutputStream().use { output ->
            check(ImageIO.write(image, "jpg", output))
            output.toByteArray()
        }
    }
}
