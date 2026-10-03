package com.filamentvision.backend.input.framing

import com.filamentvision.backend.input.transport.BorrowedDatagramBytes
import com.filamentvision.backend.input.transport.StructuredTransportCodec

/** Immutable boundary metadata that can be retained after the receive callback. */
data class EnvelopeBoundaryBytes(val first: Byte, val second: Byte)

enum class EnvelopeErrorCode {
    DATAGRAM_TOO_SHORT,
    HEADER_MISMATCH,
    FOOTER_MISMATCH,
}

sealed interface EnvelopeParseResult {
    data class Accepted(
        val structuredBytes: BorrowedStructuredBytes,
        val matchedHeader: EnvelopeBoundaryBytes,
        val matchedFooter: EnvelopeBoundaryBytes,
    ) : EnvelopeParseResult

    data class Rejected(val error: EnvelopeErrorCode) : EnvelopeParseResult
}

/**
 * Synchronous-only stripped view. Its bytes expire with the original receive lease.
 * Call [copyOwned] during the callback only when ownership must cross that boundary.
 */
class BorrowedStructuredBytes internal constructor(
    private val datagram: BorrowedDatagramBytes,
    private val offset: Int,
    val length: Int,
) {
    operator fun get(index: Int): Byte {
        require(index in 0 until length)
        return datagram.withBytes { bytes, _ -> bytes[offset + index] }
    }

    fun copyOwned(): ByteArray = withBytes { bytes, offset, length ->
        bytes.copyOfRange(offset, offset + length)
    }

    /** The backing array must not be retained or used outside this receive callback. */
    fun <T> withBytes(block: (bytes: ByteArray, offset: Int, length: Int) -> T): T =
        datagram.withBytes { bytes, _ -> block(bytes, offset, length) }
}

/** Consumes two-byte boundaries compiled from HEX before parsing any Datagrams. */
class DatagramEnvelopeParser(headerBytes: ByteArray, footerBytes: ByteArray) {
    private val header = ownBoundary(headerBytes)
    private val footer = ownBoundary(footerBytes)
    private val matchedHeader = EnvelopeBoundaryBytes(header[0], header[1])
    private val matchedFooter = EnvelopeBoundaryBytes(footer[0], footer[1])

    fun parse(datagram: BorrowedDatagramBytes): EnvelopeParseResult = datagram.withBytes { bytes, size ->
        when {
            size < MIN_DATAGRAM_BYTES -> EnvelopeParseResult.Rejected(EnvelopeErrorCode.DATAGRAM_TOO_SHORT)
            !matches(bytes, 0, header) -> EnvelopeParseResult.Rejected(EnvelopeErrorCode.HEADER_MISMATCH)
            !matches(bytes, size - BOUNDARY_BYTES, footer) -> EnvelopeParseResult.Rejected(EnvelopeErrorCode.FOOTER_MISMATCH)
            else -> EnvelopeParseResult.Accepted(
                structuredBytes = BorrowedStructuredBytes(datagram, BOUNDARY_BYTES, size - 2 * BOUNDARY_BYTES),
                matchedHeader = matchedHeader,
                matchedFooter = matchedFooter,
            )
        }
    }

    private fun ownBoundary(bytes: ByteArray): ByteArray {
        require(bytes.size == BOUNDARY_BYTES) { "Datagram envelope boundaries must be exactly two bytes" }
        return bytes.copyOf()
    }

    // Always compare both bytes of this fixed-size boundary without an early mismatch return.
    private fun matches(bytes: ByteArray, offset: Int, expected: ByteArray): Boolean =
        ((bytes[offset].toInt() xor expected[0].toInt()) or
            (bytes[offset + 1].toInt() xor expected[1].toInt())) == 0

    private companion object {
        const val BOUNDARY_BYTES = 2
        const val MIN_DATAGRAM_BYTES = StructuredTransportCodec.HEADER_BYTES + 2 * BOUNDARY_BYTES
    }
}
