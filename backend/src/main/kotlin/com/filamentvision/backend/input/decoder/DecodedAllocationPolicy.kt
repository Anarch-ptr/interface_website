package com.filamentvision.backend.input.decoder

class DecodeValidationException(message: String) : IllegalArgumentException(message)

data class DecodedImageShape(
    val width: Int,
    val height: Int,
    val pixelCount: Int,
    val decodedBytes: Int,
)

object DecodedAllocationPolicy {
    const val HARD_MAX_WIDTH = 8_192
    const val HARD_MAX_HEIGHT = 8_192
    const val HARD_MAX_DECODED_PIXELS = 16_777_216L
    const val HARD_MAX_DECODED_BYTES = 64L * 1024 * 1024

    fun validateImage(width: Int, height: Int): DecodedImageShape {
        if (width !in 1..HARD_MAX_WIDTH || height !in 1..HARD_MAX_HEIGHT) {
            throw DecodeValidationException("Decoded image dimensions exceed hard limits")
        }
        val pixels = checkedMultiply(width.toLong(), height.toLong())
        if (pixels > HARD_MAX_DECODED_PIXELS) {
            throw DecodeValidationException("Decoded image pixel count exceeds hard limit")
        }
        val decodedBytes = validateDecodedBytes(pixels, 4)
        return DecodedImageShape(width, height, pixels.toInt(), decodedBytes.toInt())
    }

    fun validateDecodedBytes(elements: Long, bytesPerElement: Long): Long {
        val bytes = checkedMultiply(elements, bytesPerElement)
        if (bytes > HARD_MAX_DECODED_BYTES) {
            throw DecodeValidationException("Decoded byte count exceeds hard limit")
        }
        return bytes
    }

    fun checkedMultiply(left: Long, right: Long): Long = try {
        Math.multiplyExact(left, right)
    } catch (error: ArithmeticException) {
        throw DecodeValidationException("Decoded allocation calculation overflowed")
    }

    fun checkedAdd(left: Long, right: Long): Long = try {
        Math.addExact(left, right)
    } catch (error: ArithmeticException) {
        throw DecodeValidationException("Decoded allocation calculation overflowed")
    }

    fun ceilDiv(value: Long, divisor: Long): Long {
        if (value < 0 || divisor <= 0) throw DecodeValidationException("Invalid ceil division")
        return checkedAdd(value, divisor - 1) / divisor
    }
}
