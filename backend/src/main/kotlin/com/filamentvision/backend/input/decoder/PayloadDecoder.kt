package com.filamentvision.backend.input.decoder

import com.filamentvision.backend.adapter.DecodedImage

sealed interface DecodedInput {
    data class Image(val image: DecodedImage) : DecodedInput
}

enum class PayloadDecodeError {
    INVALID_PAYLOAD_SIZE,
    ALLOCATION_LIMIT,
    MALFORMED_PAYLOAD,
    DIMENSION_MISMATCH,
}

data class ImageDecodeMetadata(val width: Int, val height: Int, val compressedBytes: Int)

sealed interface PayloadDecodeResult {
    data class Success(
        val value: DecodedInput,
        val imageMetadata: ImageDecodeMetadata? = null,
    ) : PayloadDecodeResult
    data class Failure(val error: PayloadDecodeError) : PayloadDecodeResult
}

fun interface PayloadDecoder {
    fun decode(payload: ByteArray): PayloadDecodeResult
}
