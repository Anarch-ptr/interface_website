package com.filamentvision.backend.input.decoder

import com.filamentvision.backend.input.profile.DiameterByteOrder
import com.filamentvision.backend.input.profile.DiameterUnit
import com.filamentvision.backend.input.profile.DiameterValueEncoding
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class DecodedDiameter(
    val rawValue: Double,
    val encoding: DiameterValueEncoding,
    val unit: DiameterUnit,
    val scaledValue: Double,
    val normalizedMillimeters: Double,
)

class DiameterDecoder(
    private val encoding: DiameterValueEncoding,
    private val byteOrder: DiameterByteOrder,
    private val scale: Double,
    private val offset: Double,
    private val unit: DiameterUnit,
) : PayloadDecoder {
    override fun decode(payload: ByteArray): PayloadDecodeResult {
        if (!scale.isFinite() || !offset.isFinite()) return malformed()
        val raw = decodeRaw(payload) ?: return malformed()
        val scaled = raw * scale + offset
        if (!scaled.isFinite()) return malformed()
        val millimetres = when (unit) {
            DiameterUnit.MM -> scaled
            DiameterUnit.MICROMETER -> scaled / 1_000.0
            DiameterUnit.INCH -> scaled * 25.4
        }
        if (!millimetres.isFinite()) return malformed()
        return PayloadDecodeResult.Success(
            DecodedInput.DirectDiameter(
                DecodedDiameter(raw, encoding, unit, scaled, millimetres),
            ),
        )
    }

    private fun decodeRaw(payload: ByteArray): Double? = try {
        when (encoding) {
            DiameterValueEncoding.FLOAT32 -> exactBuffer(payload, 4)?.float?.toDouble()
            DiameterValueEncoding.FLOAT64 -> exactBuffer(payload, 8)?.double
            DiameterValueEncoding.INT16 -> exactBuffer(payload, 2)?.short?.toDouble()
            DiameterValueEncoding.INT32 -> exactBuffer(payload, 4)?.int?.toDouble()
            DiameterValueEncoding.ASCII -> decodeAscii(payload)
        }?.takeIf(Double::isFinite)
    } catch (_: Throwable) {
        null
    }

    private fun exactBuffer(payload: ByteArray, expectedSize: Int): ByteBuffer? {
        if (payload.size != expectedSize) return null
        val order = if (byteOrder == DiameterByteOrder.BIG_ENDIAN) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN
        return ByteBuffer.wrap(payload).order(order)
    }

    private fun decodeAscii(payload: ByteArray): Double? {
        if (payload.isEmpty() || payload.size > MAX_ASCII_BYTES || payload.any { it.toInt() !in 0..0x7f }) return null
        val text = payload.decodeToString().trim()
        if (text.isEmpty()) return null
        return text.toDoubleOrNull()
    }

    private fun malformed() = PayloadDecodeResult.Failure(PayloadDecodeError.MALFORMED_PAYLOAD)

    private companion object {
        const val MAX_ASCII_BYTES = 128
    }
}
