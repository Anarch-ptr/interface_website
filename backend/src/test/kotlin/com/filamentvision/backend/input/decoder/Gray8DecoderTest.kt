package com.filamentvision.backend.input.decoder

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class Gray8DecoderTest {
    @Test
    fun convertsUnsignedGrayToOpaqueArgb() {
        val result = Gray8Decoder(2, 1).decode(byteArrayOf(0, 0xff.toByte())) as PayloadDecodeResult.Success
        val image = (result.value as DecodedInput.Image).image
        assertEquals(2, image.width)
        assertArrayEquals(intArrayOf(0xff000000.toInt(), 0xffffffff.toInt()), image.argb)
    }

    @Test
    fun rejectsWrongPayloadBeforeCallingAllocator() {
        var allocations = 0
        val decoder = Gray8Decoder(2, 2) { size -> allocations++; IntArray(size) }
        val result = decoder.decode(byteArrayOf(1, 2, 3))
        assertEquals(PayloadDecodeResult.Failure(PayloadDecodeError.INVALID_PAYLOAD_SIZE), result)
        assertEquals(0, allocations)
    }

    @Test
    fun rejectsInvalidDimensionsBeforeCallingAllocator() {
        var allocations = 0
        val decoder = Gray8Decoder(Int.MAX_VALUE, 2) { size -> allocations++; IntArray(size) }
        assertEquals(PayloadDecodeError.ALLOCATION_LIMIT, (decoder.decode(byteArrayOf()) as PayloadDecodeResult.Failure).error)
        assertEquals(0, allocations)
    }
}
