package com.filamentvision.backend.input.decoder

import com.filamentvision.backend.adapter.DecodedImage
import com.filamentvision.backend.input.profile.BinaryPacking

class BinaryDecoder(
    private val width: Int,
    private val height: Int,
    private val packing: BinaryPacking,
    private val allocator: (Int) -> IntArray = ::IntArray,
) : PayloadDecoder {
    override fun decode(payload: ByteArray): PayloadDecodeResult {
        val shape = try {
            DecodedAllocationPolicy.validateImage(width, height)
        } catch (_: DecodeValidationException) {
            return PayloadDecodeResult.Failure(PayloadDecodeError.ALLOCATION_LIMIT)
        }
        val expected = when (packing) {
            BinaryPacking.BYTE_PER_PIXEL -> shape.pixelCount.toLong()
            BinaryPacking.BIT_PACKED -> DecodedAllocationPolicy.ceilDiv(shape.pixelCount.toLong(), 8)
        }
        if (payload.size.toLong() != expected) {
            return PayloadDecodeResult.Failure(PayloadDecodeError.INVALID_PAYLOAD_SIZE)
        }
        val argb = allocator(shape.pixelCount)
        repeat(shape.pixelCount) { index ->
            val enabled = when (packing) {
                BinaryPacking.BYTE_PER_PIXEL -> payload[index].toInt() != 0
                BinaryPacking.BIT_PACKED -> (payload[index / 8].toInt() and (0x80 ushr (index % 8))) != 0
            }
            argb[index] = if (enabled) WHITE else BLACK
        }
        return PayloadDecodeResult.Success(DecodedInput.Image(DecodedImage(width, height, argb)))
    }

    private companion object {
        const val BLACK = -0x1000000
        const val WHITE = -0x1
    }
}
