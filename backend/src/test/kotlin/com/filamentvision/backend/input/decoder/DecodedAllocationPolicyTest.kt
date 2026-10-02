package com.filamentvision.backend.input.decoder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DecodedAllocationPolicyTest {
    @Test
    fun validatesDimensionsPixelsAndArgbAllocation() {
        val shape = DecodedAllocationPolicy.validateImage(640, 480)
        assertEquals(307_200, shape.pixelCount)
        assertEquals(1_228_800, shape.decodedBytes)
    }

    @Test
    fun rejectsZeroOversizedAndMultiplicationOverflow() {
        assertThrows(DecodeValidationException::class.java) { DecodedAllocationPolicy.validateImage(0, 1) }
        assertThrows(DecodeValidationException::class.java) { DecodedAllocationPolicy.validateImage(8_193, 1) }
        assertThrows(DecodeValidationException::class.java) { DecodedAllocationPolicy.validateImage(Int.MAX_VALUE, Int.MAX_VALUE) }
        assertThrows(DecodeValidationException::class.java) { DecodedAllocationPolicy.checkedMultiply(Long.MAX_VALUE, 2) }
    }

    @Test
    fun enforcesPixelAndDecodedByteHardLimits() {
        assertThrows(DecodeValidationException::class.java) { DecodedAllocationPolicy.validateImage(8_192, 8_192) }
        assertThrows(DecodeValidationException::class.java) {
            DecodedAllocationPolicy.validateDecodedBytes(DecodedAllocationPolicy.HARD_MAX_DECODED_BYTES / 4 + 1, 4)
        }
        assertEquals(2, DecodedAllocationPolicy.ceilDiv(9, 8))
    }
}
