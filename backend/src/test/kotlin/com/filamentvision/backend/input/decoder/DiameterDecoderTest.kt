package com.filamentvision.backend.input.decoder

import com.filamentvision.backend.input.profile.DiameterByteOrder
import com.filamentvision.backend.input.profile.DiameterUnit
import com.filamentvision.backend.input.profile.DiameterValueEncoding
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DiameterDecoderTest {
    @Test
    fun decodesFloat32InBothByteOrders() {
        assertEquals(1.25, diameter(DiameterValueEncoding.FLOAT32, DiameterByteOrder.BIG_ENDIAN, bytes(Float.SIZE_BYTES, ByteOrder.BIG_ENDIAN) { putFloat(1.25f) }).normalizedMillimeters, 0.0001)
        assertEquals(2.5, diameter(DiameterValueEncoding.FLOAT32, DiameterByteOrder.LITTLE_ENDIAN, bytes(Float.SIZE_BYTES, ByteOrder.LITTLE_ENDIAN) { putFloat(2.5f) }).normalizedMillimeters, 0.0001)
    }

    @Test
    fun decodesFloat64Int16Int32AndAsciiScientific() {
        assertEquals(3.5, diameter(DiameterValueEncoding.FLOAT64, payload = bytes(8) { putDouble(3.5) }).rawValue, 0.0)
        assertEquals(-12.0, diameter(DiameterValueEncoding.INT16, payload = bytes(2) { putShort(-12) }).rawValue, 0.0)
        assertEquals(123_456.0, diameter(DiameterValueEncoding.INT32, payload = bytes(4) { putInt(123_456) }).rawValue, 0.0)
        assertEquals(1.25, diameter(DiameterValueEncoding.ASCII, payload = "1.25e0".encodeToByteArray()).rawValue, 0.0)
    }

    @Test
    fun appliesScaleOffsetThenNormalizesUnitToMillimetres() {
        assertEquals(5.0, diameter(DiameterValueEncoding.ASCII, payload = "2".encodeToByteArray(), scale = 2.0, offset = 1.0, unit = DiameterUnit.MM).normalizedMillimeters, 0.0)
        assertEquals(2.0, diameter(DiameterValueEncoding.ASCII, payload = "2000".encodeToByteArray(), unit = DiameterUnit.MICROMETER).normalizedMillimeters, 0.0)
        assertEquals(25.4, diameter(DiameterValueEncoding.ASCII, payload = "1".encodeToByteArray(), unit = DiameterUnit.INCH).normalizedMillimeters, 0.0)
    }

    @Test
    fun rejectsWrongBinaryLengthsOversizedAsciiMalformedAndNonFinite() {
        assertFailure(DiameterValueEncoding.FLOAT32, byteArrayOf(1))
        assertFailure(DiameterValueEncoding.ASCII, ByteArray(129) { '1'.code.toByte() })
        assertFailure(DiameterValueEncoding.ASCII, "not-a-number".encodeToByteArray())
        assertFailure(DiameterValueEncoding.ASCII, "NaN".encodeToByteArray())
        assertFailure(DiameterValueEncoding.ASCII, "Infinity".encodeToByteArray())
    }

    private fun diameter(
        encoding: DiameterValueEncoding,
        order: DiameterByteOrder = DiameterByteOrder.BIG_ENDIAN,
        payload: ByteArray,
        scale: Double = 1.0,
        offset: Double = 0.0,
        unit: DiameterUnit = DiameterUnit.MM,
    ): DecodedDiameter = ((DiameterDecoder(encoding, order, scale, offset, unit).decode(payload) as PayloadDecodeResult.Success).value as DecodedInput.DirectDiameter).evidence

    private fun assertFailure(encoding: DiameterValueEncoding, payload: ByteArray) {
        assertEquals(PayloadDecodeError.MALFORMED_PAYLOAD, (DiameterDecoder(encoding, DiameterByteOrder.BIG_ENDIAN, 1.0, 0.0, DiameterUnit.MM).decode(payload) as PayloadDecodeResult.Failure).error)
    }

    private fun bytes(size: Int, order: ByteOrder = ByteOrder.BIG_ENDIAN, write: ByteBuffer.() -> Unit): ByteArray =
        ByteBuffer.allocate(size).order(order).apply(write).array()
}
