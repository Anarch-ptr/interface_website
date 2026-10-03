package com.filamentvision.backend.input.transport

import com.filamentvision.backend.input.profile.ApplicationFramingProfile
import com.filamentvision.backend.input.profile.FramingFormat
import com.filamentvision.backend.input.profile.FramingScope
import com.filamentvision.backend.input.profile.TransportMode
import com.filamentvision.backend.input.profile.UdpInputProfile

data class UdpReceiveCapacity(
    val acceptedLimit: Int,
    val receiveCapacity: Int,
    val sentinelEnabled: Boolean,
)

object UdpReceiveCapacityCalculator {
    const val IPV4_UDP_PAYLOAD_MAX = 65_507

    fun forProfile(profile: UdpInputProfile): UdpReceiveCapacity = forProfile(profile, envelopeBytes = 0)

    /** Envelope bytes are outside the structured payload and existing application framing limits. */
    fun forProfile(profile: UdpInputProfile, envelopeBytes: Int): UdpReceiveCapacity {
        require(envelopeBytes >= 0)
        val accepted = when (profile.transportMode) {
            TransportMode.STRUCTURED_CHUNKED -> checkedAdd(
                StructuredTransportCodec.HEADER_BYTES.toLong(),
                profile.transportLimits.maxChunkPayloadBytes.toLong(),
            )
            TransportMode.SINGLE_DATAGRAM -> checkedSingleDatagramLimit(
                profile.transportLimits.maxFrameBytes.toLong(),
                strippedDatagramFramingBytes(profile.applicationFraming),
            )
        }
        return calculate(checkedAdd(accepted, envelopeBytes.toLong()))
    }

    internal fun checkedSingleDatagramLimit(maxFrameBytes: Long, strippedFramingBytes: Long): Long {
        require(maxFrameBytes > 0L)
        require(strippedFramingBytes >= 0L)
        return checkedAdd(maxFrameBytes, strippedFramingBytes)
    }

    internal fun calculate(rawAcceptedLimit: Long): UdpReceiveCapacity {
        require(rawAcceptedLimit > 0L)
        val accepted = minOf(rawAcceptedLimit, IPV4_UDP_PAYLOAD_MAX.toLong()).toInt()
        val sentinel = accepted < IPV4_UDP_PAYLOAD_MAX
        return UdpReceiveCapacity(
            acceptedLimit = accepted,
            receiveCapacity = if (sentinel) accepted + 1 else accepted,
            sentinelEnabled = sentinel,
        )
    }

    private fun strippedDatagramFramingBytes(framing: ApplicationFramingProfile): Long {
        var total = 0L
        if (framing.headerEnabled && framing.headerScope == FramingScope.DATAGRAM && framing.stripHeader) {
            total = checkedAdd(total, framingByteCount(framing.headerFormat, framing.headerBytes))
        }
        if (framing.footerEnabled && framing.footerScope == FramingScope.DATAGRAM && framing.stripFooter) {
            total = checkedAdd(total, framingByteCount(framing.footerFormat, framing.footerBytes))
        }
        return total
    }

    private fun framingByteCount(format: FramingFormat, value: String): Long = when (format) {
        FramingFormat.HEX -> {
            val compact = value.filterNot(Char::isWhitespace)
            require(compact.length % 2 == 0 && compact.all { it.digitToIntOrNull(16) != null })
            compact.length.toLong() / 2L
        }
        FramingFormat.ASCII -> {
            require(value.all { it.code <= 0x7f })
            value.length.toLong()
        }
    }

    private fun checkedAdd(left: Long, right: Long): Long =
        try {
            Math.addExact(left, right)
        } catch (error: ArithmeticException) {
            throw IllegalArgumentException("UDP receive capacity overflow", error)
        }
}
