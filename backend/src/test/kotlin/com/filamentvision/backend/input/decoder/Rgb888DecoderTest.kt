package com.filamentvision.backend.input.decoder

import com.filamentvision.backend.input.profile.RgbChannelOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class Rgb888DecoderTest {
    @Test
    fun decodesRgbAndBgrToOpaqueArgb() {
        assertArrayEquals(
            intArrayOf(0xff112233.toInt()),
            image(Rgb888Decoder(1, 1, RgbChannelOrder.RGB).decode(byteArrayOf(0x11, 0x22, 0x33))).argb,
        )
        assertArrayEquals(
            intArrayOf(0xff112233.toInt()),
            image(Rgb888Decoder(1, 1, RgbChannelOrder.BGR).decode(byteArrayOf(0x33, 0x22, 0x11))).argb,
        )
    }

    @Test
    fun requiresExactThreeBytesPerPixel() {
        assertEquals(
            PayloadDecodeResult.Failure(PayloadDecodeError.INVALID_PAYLOAD_SIZE),
            Rgb888Decoder(2, 1, RgbChannelOrder.RGB).decode(ByteArray(5)),
        )
    }

    @Test
    fun validatesWireAndDecodedAllocationBeforeAllocator() {
        var allocations = 0
        val result = Rgb888Decoder(Int.MAX_VALUE, Int.MAX_VALUE, RgbChannelOrder.RGB) { size ->
            allocations++
            IntArray(size)
        }.decode(byteArrayOf())
        assertEquals(PayloadDecodeError.ALLOCATION_LIMIT, (result as PayloadDecodeResult.Failure).error)
        assertEquals(0, allocations)
    }

    private fun image(result: PayloadDecodeResult) = ((result as PayloadDecodeResult.Success).value as DecodedInput.Image).image
}
