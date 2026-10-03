package com.filamentvision.backend.input.framing

import com.filamentvision.backend.input.transport.BorrowedDatagramBytes
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class DatagramEnvelopeParserTest {
    private val header = byteArrayOf(0xaa.toByte(), 0x01)
    private val footer = byteArrayOf(0x0d, 0x0a)

    @Test
    fun stripsExactlyTwoBytesAtEachEndWithoutParsingStructuredFields() {
        val structured = byteArrayOf(
            0x46, 0x56, 0x55, 0x31, 1, 34, 1, 0,
            0, 0, 0, 0, 0, 0, 0, 1,
            0, 0, 0, 1,
            0, 0, 0, 0, 0, 0, 0, 0,
            0, 3, 0, 0, 0, 3,
            0x11, 0x22, 0x33,
        )
        val raw = header + structured + footer
        val accepted = parser().parse(BorrowedDatagramBytes(raw, 41)) as EnvelopeParseResult.Accepted

        assertEquals(37, accepted.structuredBytes.length)
        assertArrayEquals(structured, accepted.structuredBytes.copyOwned())
        assertEquals(EnvelopeBoundaryBytes(0xaa.toByte(), 0x01), accepted.matchedHeader)
        assertEquals(EnvelopeBoundaryBytes(0x0d, 0x0a), accepted.matchedFooter)
    }

    @Test
    fun acceptsMinimum38BytesWithAnUninterpreted34ByteStructuredHeader() {
        val raw = datagram(34)
        val accepted = parser().parse(BorrowedDatagramBytes(raw, 38)) as EnvelopeParseResult.Accepted

        assertEquals(34, accepted.structuredBytes.length)
        assertArrayEquals(ByteArray(34) { 0x55 }, accepted.structuredBytes.copyOwned())
    }

    @Test
    fun rejectsEverySizeBelow38BeforeCheckingBoundaries() {
        val parser = parser()
        for (size in 0..37) {
            val result = parser.parse(BorrowedDatagramBytes(ByteArray(80), size)) as EnvelopeParseResult.Rejected
            assertEquals("raw size $size", EnvelopeErrorCode.DATAGRAM_TOO_SHORT, result.error)
        }
    }

    @Test
    fun rejectsMismatchInEitherHeaderByte() {
        for (index in 0..1) {
            val raw = datagram(34)
            raw[index] = (raw[index].toInt() xor 1).toByte()
            val result = parser().parse(BorrowedDatagramBytes(raw, raw.size)) as EnvelopeParseResult.Rejected
            assertEquals(EnvelopeErrorCode.HEADER_MISMATCH, result.error)
        }
    }

    @Test
    fun headerMismatchTakesPrecedenceWhenBothBoundariesMismatch() {
        val raw = datagram(34)
        raw[0] = 0
        raw[37] = 0
        val result = parser().parse(BorrowedDatagramBytes(raw, 38)) as EnvelopeParseResult.Rejected

        assertEquals(EnvelopeErrorCode.HEADER_MISMATCH, result.error)
    }

    @Test
    fun rejectsMismatchInEitherFooterByte() {
        for (index in 36..37) {
            val raw = datagram(34)
            raw[index] = (raw[index].toInt() xor 1).toByte()
            val result = parser().parse(BorrowedDatagramBytes(raw, 38)) as EnvelopeParseResult.Rejected
            assertEquals(EnvelopeErrorCode.FOOTER_MISMATCH, result.error)
        }
    }

    @Test
    fun strippedViewUsesReceiveArrayAndReceivedSizeWithoutCopyingPayload() {
        val raw = datagram(37).copyOf(80)
        raw[78] = 0x0d
        raw[79] = 0x0a
        val accepted = parser().parse(BorrowedDatagramBytes(raw, 41)) as EnvelopeParseResult.Accepted

        accepted.structuredBytes.withBytes { bytes, offset, length ->
            assertSame(raw, bytes)
            assertEquals(2, offset)
            assertEquals(37, length)
        }
        raw[2] = 0x7f
        assertEquals(0x7f.toByte(), accepted.structuredBytes[0])
        assertThrows(IllegalArgumentException::class.java) { accepted.structuredBytes[-1] }
        assertThrows(IllegalArgumentException::class.java) { accepted.structuredBytes[37] }
    }

    @Test
    fun structuredViewExpiresWithTheReceiveLease() {
        val raw = datagram(34)
        val borrowed = BorrowedDatagramBytes(raw, 38)
        val accepted = parser().parse(borrowed) as EnvelopeParseResult.Accepted
        borrowed.invalidate()

        assertThrows(IllegalStateException::class.java) { accepted.structuredBytes.copyOwned() }
        assertThrows(IllegalStateException::class.java) { accepted.structuredBytes.withBytes { _, _, _ -> Unit } }
        assertThrows(IllegalStateException::class.java) { accepted.structuredBytes[0] }
        assertThrows(IllegalStateException::class.java) { parser().parse(borrowed) }
    }

    @Test
    fun retainedBoundaryMetadataSurvivesTwoReceivesReusingOneBackingArray() {
        val parser = parser()
        val reusable = datagram(37)
        val firstBorrowed = BorrowedDatagramBytes(reusable, 41)
        val first = parser.parse(firstBorrowed) as EnvelopeParseResult.Accepted
        val retainedStructured = first.structuredBytes.copyOwned()
        firstBorrowed.invalidate()

        datagram(34).copyInto(reusable)
        reusable[0] = 0x7f
        reusable[1] = 0x7e
        reusable[39] = 0x7d
        reusable[40] = 0x7c
        val secondBorrowed = BorrowedDatagramBytes(reusable, 38)
        val second = parser.parse(secondBorrowed) as EnvelopeParseResult.Rejected
        secondBorrowed.invalidate()

        assertEquals(EnvelopeErrorCode.HEADER_MISMATCH, second.error)
        assertEquals(EnvelopeBoundaryBytes(0xaa.toByte(), 0x01), first.matchedHeader)
        assertEquals(EnvelopeBoundaryBytes(0x0d, 0x0a), first.matchedFooter)
        assertArrayEquals(ByteArray(37) { 0x55 }, retainedStructured)
        assertThrows(IllegalStateException::class.java) { first.structuredBytes.copyOwned() }
    }

    @Test
    fun parserOwnsCompiledBoundaryBytesBeforeParsing() {
        val headerBytes = header.copyOf()
        val footerBytes = footer.copyOf()
        val parser = DatagramEnvelopeParser(headerBytes, footerBytes)
        headerBytes.fill(0)
        footerBytes.fill(0)
        val raw = datagram(34)
        val accepted = parser.parse(BorrowedDatagramBytes(raw, 38)) as EnvelopeParseResult.Accepted

        assertEquals(EnvelopeBoundaryBytes(0xaa.toByte(), 0x01), accepted.matchedHeader)
        assertEquals(EnvelopeBoundaryBytes(0x0d, 0x0a), accepted.matchedFooter)
    }

    @Test
    fun rejectsCompiledBoundariesThatAreNotExactlyTwoBytes() {
        for (size in listOf(0, 1, 3, 64)) {
            assertThrows(IllegalArgumentException::class.java) { DatagramEnvelopeParser(ByteArray(size), footer) }
            assertThrows(IllegalArgumentException::class.java) { DatagramEnvelopeParser(header, ByteArray(size)) }
        }
    }

    private fun parser() = DatagramEnvelopeParser(header, footer)

    private fun datagram(structuredLength: Int) = header + ByteArray(structuredLength) { 0x55 } + footer
}
