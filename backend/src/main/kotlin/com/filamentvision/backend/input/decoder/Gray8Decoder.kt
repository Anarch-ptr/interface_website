package com.filamentvision.backend.input.decoder

import com.filamentvision.backend.adapter.DecodedImage

class Gray8Decoder(
    private val width: Int,
    private val height: Int,
    private val allocator: (Int) -> IntArray = ::IntArray,
) : PayloadDecoder {
    override fun decode(payload: ByteArray): PayloadDecodeResult {
        val shape = try {
            DecodedAllocationPolicy.validateImage(width, height)
        } catch (_: DecodeValidationException) {
            return PayloadDecodeResult.Failure(PayloadDecodeError.ALLOCATION_LIMIT)
        }
        if (payload.size != shape.pixelCount) {
            return PayloadDecodeResult.Failure(PayloadDecodeError.INVALID_PAYLOAD_SIZE)
        }
        val argb = allocator(shape.pixelCount)
        payload.indices.forEach { index ->
            val intensity = payload[index].toInt() and 0xff
            argb[index] = 0xff000000.toInt() or (intensity shl 16) or (intensity shl 8) or intensity
        }
        return PayloadDecodeResult.Success(DecodedInput.Image(DecodedImage(width, height, argb)))
    }
}
