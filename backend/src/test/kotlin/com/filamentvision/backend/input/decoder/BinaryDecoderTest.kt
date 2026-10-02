package com.filamentvision.backend.input.decoder

import com.filamentvision.backend.input.profile.BinaryPacking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class BinaryDecoderTest {
    @Test
    fun bytePerPixelMapsZeroToBlackAndAnyNonzeroToWhite() {
        val image = image(BinaryDecoder(3, 1, BinaryPacking.BYTE_PER_PIXEL).decode(byteArrayOf(0, 1, 0xff.toByte())))
        assertArrayEquals(intArrayOf(BLACK, WHITE, WHITE), image.argb)
    }

    @Test
    fun bitPackedUsesMsbFirstAndIgnoresPaddingBits() {
        val image = image(BinaryDecoder(9, 1, BinaryPacking.BIT_PACKED).decode(byteArrayOf(0b10100000.toByte(), 0b10000001.toByte())))
        assertArrayEquals(intArrayOf(WHITE, BLACK, WHITE, BLACK, BLACK, BLACK, BLACK, BLACK, WHITE), image.argb)
    }

    @Test
    fun requiresExactPackedAndUnpackedPayloadSizes() {
        assertEquals(
            PayloadDecodeResult.Failure(PayloadDecodeError.INVALID_PAYLOAD_SIZE),
            BinaryDecoder(9, 1, BinaryPacking.BIT_PACKED).decode(byteArrayOf(1)),
        )
        assertEquals(
            PayloadDecodeResult.Failure(PayloadDecodeError.INVALID_PAYLOAD_SIZE),
            BinaryDecoder(2, 1, BinaryPacking.BYTE_PER_PIXEL).decode(byteArrayOf(1)),
        )
    }

    @Test
    fun allocationLimitsAreCheckedBeforeAllocator() {
        var allocations = 0
        val result = BinaryDecoder(Int.MAX_VALUE, 2, BinaryPacking.BIT_PACKED) { size -> allocations++; IntArray(size) }
            .decode(byteArrayOf())
        assertEquals(PayloadDecodeError.ALLOCATION_LIMIT, (result as PayloadDecodeResult.Failure).error)
        assertEquals(0, allocations)
    }

    private fun image(result: PayloadDecodeResult) = ((result as PayloadDecodeResult.Success).value as DecodedInput.Image).image

    private companion object {
        const val BLACK = -0x1000000
        const val WHITE = -0x1
    }
}
