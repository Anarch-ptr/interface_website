package com.filamentvision.backend.input.transport

import com.filamentvision.backend.input.profile.TransportLimits
import com.filamentvision.backend.input.profile.UdpCameraId
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportDatagramParserTest {
    private val limits = TransportLimits(
        maxInFlightFrames = 4,
        maxFrameBytes = 8 * 1024 * 1024,
        frameTimeoutMs = 1_000,
        maxChunkCount = 4_096,
        maxChunkPayloadBytes = 1_400,
    )

    @Test
    fun codecUsesExact34ByteBigEndianLayoutAndRoundTrips() {
        val payload = byteArrayOf(0x10, 0x20, 0x30)
        val header = StructuredTransportHeader(
            cameraId = UdpCameraId.B,
            frameId = 0x0102030405060708uL,
            chunkIndex = 0x0102,
            chunkCount = 0x0304,
            sourceTimestampMicros = 0x0102030405060708L,
            payloadLength = payload.size,
            framePayloadBytes = 0x01020304,
        )

        val encoded = StructuredTransportCodec.encode(header, payload)

        assertEquals(StructuredTransportCodec.HEADER_BYTES + payload.size, encoded.size)
        assertArrayEquals(byteArrayOf(0x46, 0x56, 0x55, 0x31), encoded.copyOfRange(0, 4))
        assertEquals(1, encoded[4].toInt() and 0xff)
        assertEquals(34, encoded[5].toInt() and 0xff)
        assertEquals(2, encoded[6].toInt() and 0xff)
        assertEquals(0, encoded[7].toInt() and 0xff)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), encoded.copyOfRange(8, 16))
        assertArrayEquals(byteArrayOf(1, 2), encoded.copyOfRange(16, 18))
        assertArrayEquals(byteArrayOf(3, 4), encoded.copyOfRange(18, 20))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), encoded.copyOfRange(20, 28))
        assertArrayEquals(byteArrayOf(0, 3), encoded.copyOfRange(28, 30))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), encoded.copyOfRange(30, 34))

        val accepted = parser().parse(
            encoded,
            encoded.size,
            UdpCameraId.B,
            limits.copy(maxFrameBytes = 32 * 1024 * 1024),
        ).accepted()
        assertEquals(header, accepted.header)
        assertArrayEquals(payload, accepted.payload.copyOwned())
    }

    @Test
    fun preservesUnsignedFrameIdAndBorrowsPayloadUntilSynchronousConsumptionEnds() {
        val encoded = StructuredTransportCodec.encode(
            validHeader(frameId = ULong.MAX_VALUE, payloadLength = 2, framePayloadBytes = 2),
            byteArrayOf(7, 8),
        )
        val accepted = parser().parse(encoded, encoded.size, UdpCameraId.A, limits).accepted()

        assertEquals(ULong.MAX_VALUE, accepted.header.frameId)
        encoded[StructuredTransportCodec.HEADER_BYTES] = 99
        assertEquals(99, accepted.payload[0].toInt())
    }

    @Test
    fun singleStreamParserAcceptsCompatibilityOneAndPreservesTimestampAndPayload() {
        val header = StructuredTransportHeader(
            compatibilityStreamId = 1u.toUByte(),
            frameId = ULong.MAX_VALUE,
            chunkIndex = 0,
            chunkCount = 1,
            sourceTimestampMicros = Long.MAX_VALUE,
            payloadLength = 2,
            framePayloadBytes = 2,
        )
        val bytes = encoded(header, byteArrayOf(0x80.toByte(), 0xff.toByte()))

        val accepted = parser().parse(bytes, bytes.size, limits).accepted()

        assertEquals(header, accepted.header)
        assertEquals(1u.toUByte(), accepted.header.compatibilityStreamId)
        assertEquals(Long.MAX_VALUE, accepted.header.sourceTimestampMicros)
        assertArrayEquals(byteArrayOf(0x80.toByte(), 0xff.toByte()), accepted.payload.copyOwned())
        bytes[34] = 7
        assertEquals(7, accepted.payload[0].toInt())
    }

    @Test
    fun singleStreamParserRejectsOtherCompatibilityBytesBeforeBorrowingPayload() {
        var payloadViews = 0
        val parser = TransportDatagramParser { bytes, offset, length ->
            payloadViews++
            BorrowedPayloadView(bytes, offset, length)
        }
        listOf(0, 2, 255).forEach { stream ->
            val bytes = mutateValid(6, stream)

            val rejected = parser.parse(bytes, bytes.size, limits).rejected()

            assertEquals("stream $stream", TransportErrorCode.INVALID_TRANSPORT_HEADER, rejected.code)
            assertEquals("stream $stream", 0, payloadViews)
        }
    }

    @Test
    fun singleStreamParserEnforcesConfiguredAndHardFrameLimitsBeforeBorrowingPayload() {
        listOf(8 * 1024 * 1024, 32 * 1024 * 1024, Int.MAX_VALUE).forEach { maxFrameBytes ->
            listOf(0x7fffffffL, 0x80000000L, 0xffffffffL).forEach { declaration ->
                var payloadViews = 0
                val parser = TransportDatagramParser { bytes, offset, length ->
                    payloadViews++
                    BorrowedPayloadView(bytes, offset, length)
                }
                val bytes = encoded(validHeader(framePayloadBytes = declaration))

                val rejected = parser.parse(bytes, bytes.size, limits.copy(maxFrameBytes = maxFrameBytes)).rejected()

                assertEquals("$declaration / $maxFrameBytes", TransportErrorCode.FRAME_TOO_LARGE, rejected.code)
                assertEquals("$declaration / $maxFrameBytes", 0, payloadViews)
            }
        }
    }

    @Test
    fun singleStreamParserAcceptsExactFrameLimitsAndRejectsOneByteOver() {
        listOf(8 * 1024 * 1024, 32 * 1024 * 1024).forEach { maxFrameBytes ->
            val exact = encoded(validHeader(framePayloadBytes = maxFrameBytes.toLong()))
            val over = encoded(validHeader(framePayloadBytes = maxFrameBytes.toLong() + 1))

            assertEquals(maxFrameBytes.toLong(), parser().parse(exact, exact.size,
                limits.copy(maxFrameBytes = maxFrameBytes)).accepted().header.framePayloadBytes)
            assertEquals(TransportErrorCode.FRAME_TOO_LARGE, parser().parse(over, over.size,
                limits.copy(maxFrameBytes = maxFrameBytes)).rejected().code)
        }
        val overHardMax = encoded(validHeader(framePayloadBytes = 32L * 1024 * 1024 + 1))
        assertEquals(TransportErrorCode.FRAME_TOO_LARGE, parser().parse(overHardMax, overHardMax.size,
            limits.copy(maxFrameBytes = Int.MAX_VALUE)).rejected().code)
    }

    @Test
    fun rejectsInvalidFixedHeaderFieldsAndLengths() {
        val cases = listOf(
            mutateValid(0, 0) to TransportErrorCode.INVALID_TRANSPORT_HEADER,
            mutateValid(4, 2) to TransportErrorCode.UNSUPPORTED_PROTOCOL_VERSION,
            mutateValid(5, 33) to TransportErrorCode.INVALID_TRANSPORT_HEADER,
            mutateValid(7, 1) to TransportErrorCode.INVALID_TRANSPORT_HEADER,
            mutateValid(6, 2) to TransportErrorCode.CAMERA_ID_MISMATCH,
            encoded(validHeader(chunkIndex = 2, chunkCount = 2)) to TransportErrorCode.INVALID_CHUNK_INDEX,
            encoded(validHeader(chunkCount = 0)) to TransportErrorCode.INVALID_CHUNK_COUNT,
            encoded(validHeader(chunkCount = 4_097)) to TransportErrorCode.INVALID_CHUNK_COUNT,
            encoded(validHeader(sourceTimestampMicros = -1)) to TransportErrorCode.INVALID_TRANSPORT_HEADER,
            encoded(validHeader(payloadLength = 2), byteArrayOf(1)) to TransportErrorCode.INVALID_CHUNK_LENGTH,
        )

        cases.forEach { (bytes, expected) ->
            val rejected = parser().parse(bytes, bytes.size, UdpCameraId.A, limits).rejected()
            assertEquals("${bytes.toList()}", expected, rejected.code)
        }
    }

    @Test
    fun validatesPayloadLengthBeforeCreatingPayloadView() {
        var payloadViews = 0
        val parser = TransportDatagramParser { bytes, offset, length ->
            payloadViews++
            BorrowedPayloadView(bytes, offset, length)
        }
        val oversized = encoded(
            validHeader(payloadLength = 1_401, framePayloadBytes = 1_401),
            ByteArray(1_401),
        )

        val rejected = parser.parse(oversized, oversized.size, UdpCameraId.A, limits).rejected()

        assertEquals(TransportErrorCode.INVALID_CHUNK_LENGTH, rejected.code)
        assertEquals(0, payloadViews)
    }

    @Test
    fun widensUnsignedFramePayloadBytesBeforeHardLimitValidation() {
        val rawValues = listOf(0x7fffffffuL, 0x80000000uL, 0xffffffffuL)
        rawValues.forEach { raw ->
            var payloadViews = 0
            val parser = TransportDatagramParser { bytes, offset, length ->
                payloadViews++
                BorrowedPayloadView(bytes, offset, length)
            }
            val bytes = encoded(validHeader(framePayloadBytes = raw.toLong()))

            val rejected = parser.parse(bytes, bytes.size, UdpCameraId.A, limits).rejected()

            assertEquals(raw.toString(), TransportErrorCode.FRAME_TOO_LARGE, rejected.code)
            assertEquals(raw.toString(), 0, payloadViews)
        }
    }

    @Test
    fun rejectsDatagramShorterThanHeaderWithoutReadingPastBounds() {
        listOf(0, 1, 33).forEach { length ->
            val rejected = parser().parse(ByteArray(34), length, UdpCameraId.A, limits).rejected()
            assertEquals(TransportErrorCode.INVALID_TRANSPORT_HEADER, rejected.code)
        }
    }

    private fun parser() = TransportDatagramParser()

    private fun validHeader(
        frameId: ULong = 42uL,
        chunkIndex: Int = 0,
        chunkCount: Int = 1,
        sourceTimestampMicros: Long = 123_456,
        payloadLength: Int = 1,
        framePayloadBytes: Long = 1,
    ) = StructuredTransportHeader(
        cameraId = UdpCameraId.A,
        frameId = frameId,
        chunkIndex = chunkIndex,
        chunkCount = chunkCount,
        sourceTimestampMicros = sourceTimestampMicros,
        payloadLength = payloadLength,
        framePayloadBytes = framePayloadBytes,
    )

    private fun encoded(
        header: StructuredTransportHeader,
        payload: ByteArray = ByteArray(header.payloadLength),
    ): ByteArray = StructuredTransportCodec.encode(header, payload)

    private fun mutateValid(offset: Int, value: Int): ByteArray =
        encoded(validHeader()).also { it[offset] = value.toByte() }

    private fun TransportParseResult.accepted(): ParsedTransportDatagram {
        assertTrue("Expected Accepted but was $this", this is TransportParseResult.Accepted)
        return (this as TransportParseResult.Accepted).datagram
    }

    private fun TransportParseResult.rejected(): TransportParseError {
        assertTrue("Expected Rejected but was $this", this is TransportParseResult.Rejected)
        return (this as TransportParseResult.Rejected).error
    }
}
