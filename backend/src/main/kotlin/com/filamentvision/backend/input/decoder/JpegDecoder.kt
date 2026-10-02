package com.filamentvision.backend.input.decoder

import com.filamentvision.backend.adapter.DecodedImage

class JpegDecoder(
    private val maxFrameBytes: Int,
    private val expectedWidth: Int? = null,
    private val expectedHeight: Int? = null,
    private val metadataReader: JpegMetadataReader = ImageIoJpegMetadataReader,
    private val allocator: (Int) -> IntArray = ::IntArray,
) : PayloadDecoder {
    init {
        require(maxFrameBytes > 0)
    }

    override fun decode(payload: ByteArray): PayloadDecodeResult {
        if (payload.size > maxFrameBytes) {
            return PayloadDecodeResult.Failure(PayloadDecodeError.INVALID_PAYLOAD_SIZE)
        }
        val session = try {
            metadataReader.open(payload)
        } catch (_: Throwable) {
            null
        } ?: return PayloadDecodeResult.Failure(PayloadDecodeError.MALFORMED_PAYLOAD)

        return session.use {
            val metadata = session.metadata
            val shape = try {
                DecodedAllocationPolicy.validateImage(metadata.width, metadata.height)
            } catch (_: DecodeValidationException) {
                return@use PayloadDecodeResult.Failure(PayloadDecodeError.ALLOCATION_LIMIT)
            }
            if (
                (expectedWidth != null && expectedWidth != metadata.width) ||
                (expectedHeight != null && expectedHeight != metadata.height)
            ) {
                return@use PayloadDecodeResult.Failure(PayloadDecodeError.DIMENSION_MISMATCH)
            }
            val argb = try {
                session.readArgb(allocator)
            } catch (_: Throwable) {
                return@use PayloadDecodeResult.Failure(PayloadDecodeError.MALFORMED_PAYLOAD)
            }
            if (argb.size != shape.pixelCount) {
                return@use PayloadDecodeResult.Failure(PayloadDecodeError.MALFORMED_PAYLOAD)
            }
            PayloadDecodeResult.Success(
                value = DecodedInput.Image(DecodedImage(metadata.width, metadata.height, argb)),
                imageMetadata = ImageDecodeMetadata(metadata.width, metadata.height, payload.size),
            )
        }
    }
}
