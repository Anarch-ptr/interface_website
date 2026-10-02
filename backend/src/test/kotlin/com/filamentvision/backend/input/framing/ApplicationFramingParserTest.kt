package com.filamentvision.backend.input.framing

import com.filamentvision.backend.input.profile.ApplicationFramingProfile
import com.filamentvision.backend.input.profile.FramingFormat
import com.filamentvision.backend.input.profile.FramingScope
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ApplicationFramingParserTest {
    @Test
    fun validatesAndStripsSpacedHexHeaderAndAsciiFooter() {
        val parser = ApplicationFramingParser(
            ApplicationFramingProfile(
                headerEnabled = true,
                headerFormat = FramingFormat.HEX,
                headerBytes = "AA 55",
                headerScope = FramingScope.DATAGRAM,
                stripHeader = true,
                footerEnabled = true,
                footerFormat = FramingFormat.ASCII,
                footerBytes = "END",
                footerScope = FramingScope.DATAGRAM,
                stripFooter = true,
            ),
        )
        val result = parser.parse(FramingScope.DATAGRAM, byteArrayOf(0xaa.toByte(), 0x55, 1, 2, 69, 78, 68))
            as ApplicationFramingResult.Accepted
        assertArrayEquals(byteArrayOf(1, 2), result.payload.copyOwned())
        assertEquals(2, result.matchedHeader?.length)
        assertEquals(3, result.matchedFooter?.length)
    }

    @Test
    fun stripFalseRetainsMatchedBytesAndDefinesReassemblyLength() {
        val parser = ApplicationFramingParser(
            ApplicationFramingProfile(
                headerEnabled = true,
                headerFormat = FramingFormat.HEX,
                headerBytes = "AA55",
                headerScope = FramingScope.DATAGRAM,
                stripHeader = false,
            ),
        )
        val result = parser.parse(FramingScope.DATAGRAM, byteArrayOf(0xaa.toByte(), 0x55, 1))
            as ApplicationFramingResult.Accepted
        assertArrayEquals(byteArrayOf(0xaa.toByte(), 0x55, 1), result.payload.copyOwned())
        assertEquals(3, result.payload.length)
    }

    @Test
    fun onlyParsesRequestedScopeAndSupportsTwoStageInvocation() {
        val parser = ApplicationFramingParser(
            ApplicationFramingProfile(
                headerEnabled = true,
                headerFormat = FramingFormat.ASCII,
                headerBytes = "D",
                headerScope = FramingScope.DATAGRAM,
                stripHeader = true,
                footerEnabled = true,
                footerFormat = FramingFormat.ASCII,
                footerBytes = "F",
                footerScope = FramingScope.FRAME,
                stripFooter = true,
            ),
        )
        val datagram = parser.parse(FramingScope.DATAGRAM, "D12F".encodeToByteArray()) as ApplicationFramingResult.Accepted
        assertArrayEquals("12F".encodeToByteArray(), datagram.payload.copyOwned())
        val frame = parser.parse(FramingScope.FRAME, datagram.payload) as ApplicationFramingResult.Accepted
        assertArrayEquals("12".encodeToByteArray(), frame.payload.copyOwned())
    }

    @Test
    fun reportsHeaderFooterAndTooShortFailures() {
        val header = ApplicationFramingParser(profile(header = "AA"))
        assertEquals(ApplicationFramingError.HEADER_MISMATCH, (header.parse(FramingScope.DATAGRAM, byteArrayOf(0)) as ApplicationFramingResult.Rejected).error)
        assertEquals(ApplicationFramingError.PAYLOAD_TOO_SHORT, (header.parse(FramingScope.DATAGRAM, byteArrayOf()) as ApplicationFramingResult.Rejected).error)

        val footer = ApplicationFramingParser(profile(footer = "0D0A"))
        assertEquals(ApplicationFramingError.FOOTER_MISMATCH, (footer.parse(FramingScope.DATAGRAM, byteArrayOf(1, 2)) as ApplicationFramingResult.Rejected).error)
    }

    @Test
    fun rejectsMalformedHexNonAsciiAndMoreThan64FramingBytes() {
        assertThrows(IllegalArgumentException::class.java) { FramingBytesParser.parse(FramingFormat.HEX, "AA GG") }
        assertThrows(IllegalArgumentException::class.java) { FramingBytesParser.parse(FramingFormat.ASCII, "främe") }
        assertThrows(IllegalArgumentException::class.java) { FramingBytesParser.parse(FramingFormat.ASCII, "x".repeat(65)) }
        assertTrue(FramingBytesParser.parse(FramingFormat.HEX, "AA55").contentEquals(byteArrayOf(0xaa.toByte(), 0x55)))
    }

    private fun profile(header: String = "", footer: String = "") = ApplicationFramingProfile(
        headerEnabled = header.isNotEmpty(),
        headerBytes = header,
        footerEnabled = footer.isNotEmpty(),
        footerBytes = footer,
    )
}
