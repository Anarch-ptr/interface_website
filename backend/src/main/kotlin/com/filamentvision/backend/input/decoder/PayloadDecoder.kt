package com.filamentvision.backend.input.decoder

import com.filamentvision.backend.adapter.DecodedImage

sealed interface DecodedInput {
    data class Image(val image: DecodedImage) : DecodedInput
}

enum class PayloadDecodeError {
    INVALID_PAYLOAD_SIZE,
    ALLOCATION_LIMIT,
    MALFORMED_PAYLOAD,
}

sealed interface PayloadDecodeResult {
    data class Success(val value: DecodedInput) : PayloadDecodeResult
    data class Failure(val error: PayloadDecodeError) : PayloadDecodeResult
}

fun interface PayloadDecoder {
    fun decode(payload: ByteArray): PayloadDecodeResult
}
