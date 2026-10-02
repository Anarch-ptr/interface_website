package com.filamentvision.backend.input.decoder

import com.filamentvision.backend.adapter.DecodedImage
import com.filamentvision.backend.input.profile.RgbChannelOrder

class Rgb888Decoder(
    private val width: Int,
    private val height: Int,
    private val channelOrder: RgbChannelOrder,
    private val allocator: (Int) -> IntArray = ::IntArray,
) : PayloadDecoder {
    override fun decode(payload: ByteArray): PayloadDecodeResult {
        val shape = try {
            DecodedAllocationPolicy.validateImage(width, height)
        } catch (_: DecodeValidationException) {
            return PayloadDecodeResult.Failure(PayloadDecodeError.ALLOCATION_LIMIT)
        }
        val expectedWireBytes = try {
            DecodedAllocationPolicy.checkedMultiply(shape.pixelCount.toLong(), 3)
        } catch (_: DecodeValidationException) {
            return PayloadDecodeResult.Failure(PayloadDecodeError.ALLOCATION_LIMIT)
        }
        if (payload.size.toLong() != expectedWireBytes) {
            return PayloadDecodeResult.Failure(PayloadDecodeError.INVALID_PAYLOAD_SIZE)
        }
        val argb = allocator(shape.pixelCount)
        repeat(shape.pixelCount) { index ->
            val offset = index * 3
            val first = payload[offset].toInt() and 0xff
            val green = payload[offset + 1].toInt() and 0xff
            val third = payload[offset + 2].toInt() and 0xff
            val red = if (channelOrder == RgbChannelOrder.RGB) first else third
            val blue = if (channelOrder == RgbChannelOrder.RGB) third else first
            argb[index] = 0xff000000.toInt() or (red shl 16) or (green shl 8) or blue
        }
        return PayloadDecodeResult.Success(DecodedInput.Image(DecodedImage(width, height, argb)))
    }
}
