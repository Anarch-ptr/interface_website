package com.filamentvision.backend.input.transport

import com.filamentvision.backend.input.profile.ApplicationFramingProfile
import com.filamentvision.backend.input.profile.FramingFormat
import com.filamentvision.backend.input.profile.FramingScope
import com.filamentvision.backend.input.profile.PayloadProfile
import com.filamentvision.backend.input.profile.TransportLimits
import com.filamentvision.backend.input.profile.TransportMode
import com.filamentvision.backend.input.profile.UdpCameraId
import com.filamentvision.backend.input.profile.UdpInputProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UdpReceiveCapacityTest {
    @Test
    fun structuredEnvelopeCapacityIncludesBothTwoByteBoundaries() {
        val capacity = UdpReceiveCapacityCalculator.forProfile(
            profile(TransportMode.STRUCTURED_CHUNKED, limits(maxChunkPayloadBytes = 1_400)),
            envelopeBytes = 4,
        )

        assertEquals(1_438, capacity.acceptedLimit)
        assertEquals(1_439, capacity.receiveCapacity)
        assertTrue(capacity.sentinelEnabled)
    }

    @Test
    fun structuredEnvelopeSentinelStopsAtIpv4PayloadCeiling() {
        val below = UdpReceiveCapacityCalculator.forProfile(
            profile(TransportMode.STRUCTURED_CHUNKED, limits(maxChunkPayloadBytes = 65_468)),
            envelopeBytes = 4,
        )
        assertEquals(65_506, below.acceptedLimit)
        assertEquals(65_507, below.receiveCapacity)
        assertTrue(below.sentinelEnabled)

        for (chunkBytes in listOf(65_469, 65_470)) {
            val capped = UdpReceiveCapacityCalculator.forProfile(
                profile(TransportMode.STRUCTURED_CHUNKED, limits(maxChunkPayloadBytes = chunkBytes)),
                envelopeBytes = 4,
            )
            assertEquals(65_507, capped.acceptedLimit)
            assertEquals(65_507, capped.receiveCapacity)
            assertFalse(capped.sentinelEnabled)
        }
    }

    @Test
    fun explicitEnvelopeBytesAreCountedAndNegativeLengthsAreRejected() {
        val structured = profile(TransportMode.STRUCTURED_CHUNKED, limits())
        val capacity = UdpReceiveCapacityCalculator.forProfile(structured, envelopeBytes = 8)
        assertEquals(1_442, capacity.acceptedLimit)
        assertEquals(1_443, capacity.receiveCapacity)
        assertThrows(IllegalArgumentException::class.java) {
            UdpReceiveCapacityCalculator.forProfile(structured, envelopeBytes = -1)
        }
    }

    @Test
    fun structuredCapacityIncludesFixedHeaderAndPreFramingPayloadLimit() {
        val capacity = UdpReceiveCapacityCalculator.forProfile(
            profile(
                transportMode = TransportMode.STRUCTURED_CHUNKED,
                limits = limits(maxChunkPayloadBytes = 1_400),
            ),
        )

        assertEquals(34 + 1_400, capacity.acceptedLimit)
        assertEquals(34 + 1_400 + 1, capacity.receiveCapacity)
        assertTrue(capacity.sentinelEnabled)
    }

    @Test
    fun singleDatagramAddsOnlyStrippedDatagramScopeFraming() {
        val capacity = UdpReceiveCapacityCalculator.forProfile(
            profile(
                transportMode = TransportMode.SINGLE_DATAGRAM,
                limits = limits(maxFrameBytes = 10_000),
                framing = ApplicationFramingProfile(
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
            ),
        )

        assertEquals(10_005, capacity.acceptedLimit)
        assertEquals(10_006, capacity.receiveCapacity)
    }

    @Test
    fun retainedOrFrameScopeFramingIsAlreadyInsideFrameLimit() {
        val framing = ApplicationFramingProfile(
            headerEnabled = true,
            headerFormat = FramingFormat.HEX,
            headerBytes = "AA55",
            headerScope = FramingScope.DATAGRAM,
            stripHeader = false,
            footerEnabled = true,
            footerFormat = FramingFormat.ASCII,
            footerBytes = "END",
            footerScope = FramingScope.FRAME,
            stripFooter = true,
        )

        val capacity = UdpReceiveCapacityCalculator.forProfile(
            profile(
                transportMode = TransportMode.SINGLE_DATAGRAM,
                limits = limits(maxFrameBytes = 10_000),
                framing = framing,
            ),
        )

        assertEquals(10_000, capacity.acceptedLimit)
        assertEquals(10_001, capacity.receiveCapacity)
    }

    @Test
    fun capsAcceptedAndReceiveCapacityAtIpv4UdpMaximum() {
        val overHardLimit = UdpReceiveCapacityCalculator.forProfile(
            profile(
                transportMode = TransportMode.SINGLE_DATAGRAM,
                limits = limits(maxFrameBytes = 8 * 1024 * 1024),
            ),
        )
        assertEquals(65_507, overHardLimit.acceptedLimit)
        assertEquals(65_507, overHardLimit.receiveCapacity)
        assertFalse(overHardLimit.sentinelEnabled)

        val exact = UdpReceiveCapacityCalculator.calculate(65_507L)
        assertEquals(65_507, exact.acceptedLimit)
        assertEquals(65_507, exact.receiveCapacity)
        assertFalse(exact.sentinelEnabled)
    }

    @Test
    fun usesSentinelOnlyBelowIpv4UdpMaximum() {
        val capacity = UdpReceiveCapacityCalculator.calculate(65_506L)

        assertEquals(65_506, capacity.acceptedLimit)
        assertEquals(65_507, capacity.receiveCapacity)
        assertTrue(capacity.sentinelEnabled)
    }

    @Test
    fun checkedArithmeticRejectsOverflowAndNonPositiveLimits() {
        assertThrows(IllegalArgumentException::class.java) {
            UdpReceiveCapacityCalculator.checkedSingleDatagramLimit(Long.MAX_VALUE, 1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            UdpReceiveCapacityCalculator.checkedSingleDatagramLimit(1L, Long.MAX_VALUE)
        }
        assertThrows(IllegalArgumentException::class.java) {
            UdpReceiveCapacityCalculator.calculate(0L)
        }
    }

    private fun profile(
        transportMode: TransportMode,
        limits: TransportLimits,
        framing: ApplicationFramingProfile = ApplicationFramingProfile(),
    ) = UdpInputProfile(
        cameraId = UdpCameraId.A,
        enabled = true,
        sourceIp = null,
        listenPort = 5_001,
        transportMode = transportMode,
        payload = PayloadProfile.Gray8(10, 10),
        applicationFraming = framing,
        transportLimits = limits,
    )

    private fun limits(
        maxFrameBytes: Int = 8 * 1024 * 1024,
        maxChunkPayloadBytes: Int = 1_400,
    ) = TransportLimits(
        maxInFlightFrames = 4,
        maxFrameBytes = maxFrameBytes,
        frameTimeoutMs = 1_000,
        maxChunkCount = 4_096,
        maxChunkPayloadBytes = maxChunkPayloadBytes,
    )
}
