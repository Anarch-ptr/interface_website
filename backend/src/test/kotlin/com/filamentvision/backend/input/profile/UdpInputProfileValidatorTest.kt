package com.filamentvision.backend.input.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UdpInputProfileValidatorTest {
    private val validator = UdpInputProfileValidator()

    @Test
    fun acceptsIndependentValidCameraProfilesAndOptionalIpv4Filter() {
        val result = validator.validate(
            profiles(
                cameraA = profile(
                    cameraId = UdpCameraId.A,
                    sourceIp = "192.168.1.20",
                    listenPort = 5001,
                    payload = PayloadProfile.Gray8(width = 640, height = 480),
                ),
                cameraB = profile(
                    cameraId = UdpCameraId.B,
                    sourceIp = null,
                    listenPort = 5002,
                    payload = PayloadProfile.Rgb888(320, 240, RgbChannelOrder.BGR),
                ),
            ),
        )

        assertTrue(result.errors.toString(), result.isValid)
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun enabledCamerasRequireDifferentPortsAndMatchingSlots() {
        val duplicatePorts = validator.validate(
            profiles(
                cameraA = profile(UdpCameraId.A, listenPort = 6000),
                cameraB = profile(UdpCameraId.B, listenPort = 6000),
            ),
        )
        assertPaths(duplicatePorts, "cameraA.listenPort", "cameraB.listenPort")

        val swapped = validator.validate(
            profiles(
                cameraA = profile(UdpCameraId.B, listenPort = 6000),
                cameraB = profile(UdpCameraId.A, listenPort = 6001),
            ),
        )
        assertPaths(swapped, "cameraA.cameraId", "cameraB.cameraId")

        val disabledCanShare = validator.validate(
            profiles(
                cameraA = profile(UdpCameraId.A, enabled = false, listenPort = 6000),
                cameraB = profile(UdpCameraId.B, enabled = false, listenPort = 6000),
            ),
        )
        assertTrue(disabledCanShare.errors.toString(), disabledCanShare.isValid)
    }

    @Test
    fun acceptsOnlyCanonicalIpv4LiteralsInVersionOne() {
        listOf("0.0.0.0", "10.0.0.1", "192.168.20.200", "255.255.255.255").forEach { address ->
            assertTrue(
                address,
                validator.validate(profiles(cameraA = profile(UdpCameraId.A, sourceIp = address))).isValid,
            )
        }

        listOf(
            "::1",
            "2001:db8::1",
            "camera.local",
            "192.168.1",
            "192.168.1.256",
            "192.168.001.1",
            " 127.0.0.1 ",
            "",
        ).forEach { address ->
            val result = validator.validate(profiles(cameraA = profile(UdpCameraId.A, sourceIp = address)))
            assertTrue("$address -> ${result.errors}", "cameraA.sourceIp" in result.paths())
        }
    }

    @Test
    fun rejectsPortsAndTransportLimitsOutsideApprovedRanges() {
        val invalidCases = listOf(
            profile(UdpCameraId.A, listenPort = 0) to "cameraA.listenPort",
            profile(UdpCameraId.A, listenPort = 65_536) to "cameraA.listenPort",
            profile(UdpCameraId.A, limits = limits(maxInFlightFrames = 0)) to "cameraA.transportLimits.maxInFlightFrames",
            profile(UdpCameraId.A, limits = limits(maxInFlightFrames = 33)) to "cameraA.transportLimits.maxInFlightFrames",
            profile(UdpCameraId.A, limits = limits(maxFrameBytes = 0)) to "cameraA.transportLimits.maxFrameBytes",
            profile(UdpCameraId.A, limits = limits(maxFrameBytes = 32 * MIB + 1)) to "cameraA.transportLimits.maxFrameBytes",
            profile(UdpCameraId.A, limits = limits(frameTimeoutMs = 99)) to "cameraA.transportLimits.frameTimeoutMs",
            profile(UdpCameraId.A, limits = limits(frameTimeoutMs = 10_001)) to "cameraA.transportLimits.frameTimeoutMs",
            profile(UdpCameraId.A, limits = limits(maxChunkCount = 0)) to "cameraA.transportLimits.maxChunkCount",
            profile(UdpCameraId.A, limits = limits(maxChunkCount = 8_193)) to "cameraA.transportLimits.maxChunkCount",
            profile(UdpCameraId.A, limits = limits(maxChunkPayloadBytes = 0)) to "cameraA.transportLimits.maxChunkPayloadBytes",
            profile(UdpCameraId.A, limits = limits(maxChunkPayloadBytes = 60_001)) to "cameraA.transportLimits.maxChunkPayloadBytes",
        )

        invalidCases.forEach { (candidate, path) ->
            val result = validator.validate(profiles(cameraA = candidate))
            assertTrue("$path -> ${result.errors}", path in result.paths())
        }
    }

    @Test
    fun validatesEveryImagePayloadWithoutUncheckedDimensionArithmetic() {
        val validPayloads = listOf(
            PayloadProfile.Gray8(640, 480),
            PayloadProfile.Binary(640, 480, BinaryPacking.BYTE_PER_PIXEL),
            PayloadProfile.Binary(641, 479, BinaryPacking.BIT_PACKED),
            PayloadProfile.Rgb888(320, 240, RgbChannelOrder.RGB),
            PayloadProfile.Rgb888(320, 240, RgbChannelOrder.BGR),
            PayloadProfile.Jpeg(maxFrameBytes = 2 * MIB, expectedWidth = null, expectedHeight = null),
            PayloadProfile.Jpeg(maxFrameBytes = 2 * MIB, expectedWidth = 1920, expectedHeight = 1080),
        )
        validPayloads.forEach { payload ->
            val candidate = profile(
                UdpCameraId.A,
                payload = payload,
                limits = limits(maxFrameBytes = 8 * MIB),
            )
            assertTrue("$payload", validator.validate(profiles(cameraA = candidate)).isValid)
        }

        val invalidPayloads = listOf(
            PayloadProfile.Gray8(0, 1) to "cameraA.payload.width",
            PayloadProfile.Gray8(1, 0) to "cameraA.payload.height",
            PayloadProfile.Gray8(8193, 1) to "cameraA.payload.width",
            PayloadProfile.Gray8(4097, 4097) to "cameraA.payload.decodedPixels",
            PayloadProfile.Rgb888(8192, 8192, RgbChannelOrder.RGB) to "cameraA.payload.decodedPixels",
            PayloadProfile.Jpeg(0) to "cameraA.payload.maxFrameBytes",
            PayloadProfile.Jpeg(33 * MIB) to "cameraA.payload.maxFrameBytes",
            PayloadProfile.Jpeg(MIB, expectedWidth = 8193, expectedHeight = 1) to "cameraA.payload.expectedWidth",
            PayloadProfile.Jpeg(MIB, expectedWidth = 1, expectedHeight = 8193) to "cameraA.payload.expectedHeight",
            PayloadProfile.Rgb888(Int.MAX_VALUE, Int.MAX_VALUE, RgbChannelOrder.RGB) to "cameraA.payload.width",
        )
        invalidPayloads.forEach { (payload, expectedPath) ->
            val result = validator.validate(
                profiles(cameraA = profile(UdpCameraId.A, payload = payload, limits = limits(maxFrameBytes = 32 * MIB))),
            )
            assertFalse("$payload unexpectedly valid", result.isValid)
            assertTrue("$payload -> ${result.errors}", expectedPath in result.paths())
        }
    }

    @Test
    fun rawWirePayloadMustFitConfiguredFrameLimit() {
        val result = validator.validate(
            profiles(
                cameraA = profile(
                    UdpCameraId.A,
                    payload = PayloadProfile.Rgb888(100, 100, RgbChannelOrder.RGB),
                    limits = limits(maxFrameBytes = 29_999),
                ),
            ),
        )

        assertPaths(result, "cameraA.payload.expectedPayloadBytes")
    }

    @Test
    fun validatesDiameterNumbersConfidenceAndEncodingOptions() {
        DiameterValueEncoding.entries.forEach { encoding ->
            DiameterByteOrder.entries.forEach { byteOrder ->
                val result = validator.validate(
                    profiles(
                        cameraA = profile(
                            UdpCameraId.A,
                            transportMode = TransportMode.SINGLE_DATAGRAM,
                            payload = PayloadProfile.Diameter(
                                valueEncoding = encoding,
                                byteOrder = byteOrder,
                                scale = 1.0,
                                offset = 0.0,
                                unit = DiameterUnit.MM,
                                directDiameterFusionConfidence = 1.0,
                            ),
                        ),
                    ),
                )
                assertTrue("$encoding/$byteOrder -> ${result.errors}", result.isValid)
            }
        }

        val invalidCases = listOf(
            diameter(scale = Double.NaN) to "cameraA.payload.scale",
            diameter(scale = Double.POSITIVE_INFINITY) to "cameraA.payload.scale",
            diameter(offset = Double.NEGATIVE_INFINITY) to "cameraA.payload.offset",
            diameter(confidence = 0.0) to "cameraA.payload.directDiameterFusionConfidence",
            diameter(confidence = 1.000_001) to "cameraA.payload.directDiameterFusionConfidence",
        )
        invalidCases.forEach { (payload, path) ->
            val result = validator.validate(profiles(cameraA = profile(UdpCameraId.A, payload = payload)))
            assertTrue("$path -> ${result.errors}", path in result.paths())
        }
    }

    @Test
    fun validatesHexAndAsciiApplicationFramingWithStableFieldPaths() {
        val valid = listOf(
            framing(headerFormat = FramingFormat.HEX, headerBytes = "AA 55 01"),
            framing(headerFormat = FramingFormat.HEX, headerBytes = "AA5501"),
            framing(
                headerFormat = FramingFormat.ASCII,
                headerBytes = "FRAME",
                footerEnabled = true,
                footerFormat = FramingFormat.ASCII,
                footerBytes = "\r\n",
                footerScope = FramingScope.FRAME,
                stripFooter = false,
            ),
        )
        valid.forEach { applicationFraming ->
            val result = validator.validate(
                profiles(cameraA = profile(UdpCameraId.A, applicationFraming = applicationFraming)),
            )
            assertTrue("$applicationFraming -> ${result.errors}", result.isValid)
        }

        val invalidCases = listOf(
            framing(headerFormat = FramingFormat.HEX, headerBytes = "AA GG 01") to "cameraA.applicationFraming.headerBytes",
            framing(headerFormat = FramingFormat.HEX, headerBytes = "ABC") to "cameraA.applicationFraming.headerBytes",
            framing(headerFormat = FramingFormat.ASCII, headerBytes = "帧") to "cameraA.applicationFraming.headerBytes",
            framing(headerFormat = FramingFormat.HEX, headerBytes = "AA".repeat(65)) to "cameraA.applicationFraming.headerBytes",
            framing(
                headerEnabled = false,
                footerEnabled = true,
                footerFormat = FramingFormat.ASCII,
                footerBytes = "F".repeat(65),
            ) to "cameraA.applicationFraming.footerBytes",
        )
        invalidCases.forEach { (applicationFraming, path) ->
            val result = validator.validate(
                profiles(cameraA = profile(UdpCameraId.A, applicationFraming = applicationFraming)),
            )
            assertTrue("$path -> ${result.errors}", path in result.paths())
        }
    }

    @Test
    fun validationErrorsExposeStablePathCodeAndMessage() {
        val error = validator.validate(
            profiles(cameraA = profile(UdpCameraId.A, listenPort = 0)),
        ).errors.single { it.path == "cameraA.listenPort" }

        assertEquals("OUT_OF_RANGE", error.code)
        assertTrue(error.message.isNotBlank())
    }

    private fun profiles(
        cameraA: UdpInputProfile = profile(UdpCameraId.A, listenPort = 5001),
        cameraB: UdpInputProfile = profile(UdpCameraId.B, listenPort = 5002),
    ) = UdpInputProfileSet(cameraA, cameraB)

    private fun profile(
        cameraId: UdpCameraId,
        enabled: Boolean = true,
        sourceIp: String? = null,
        listenPort: Int = if (cameraId == UdpCameraId.A) 5001 else 5002,
        transportMode: TransportMode = TransportMode.STRUCTURED_CHUNKED,
        payload: PayloadProfile = PayloadProfile.Gray8(640, 480),
        applicationFraming: ApplicationFramingProfile = ApplicationFramingProfile(),
        limits: TransportLimits = limits(),
    ) = UdpInputProfile(cameraId, enabled, sourceIp, listenPort, transportMode, payload, applicationFraming, limits)

    private fun limits(
        maxInFlightFrames: Int = 4,
        maxFrameBytes: Int = 8 * MIB,
        frameTimeoutMs: Long = 1_000,
        maxChunkCount: Int = 4_096,
        maxChunkPayloadBytes: Int = 1_400,
    ) = TransportLimits(maxInFlightFrames, maxFrameBytes, frameTimeoutMs, maxChunkCount, maxChunkPayloadBytes)

    private fun diameter(
        scale: Double = 1.0,
        offset: Double = 0.0,
        confidence: Double = 1.0,
    ) = PayloadProfile.Diameter(
        DiameterValueEncoding.FLOAT32,
        DiameterByteOrder.BIG_ENDIAN,
        scale,
        offset,
        DiameterUnit.MM,
        confidence,
    )

    private fun framing(
        headerEnabled: Boolean = true,
        headerFormat: FramingFormat = FramingFormat.HEX,
        headerBytes: String = "AA55",
        headerScope: FramingScope = FramingScope.DATAGRAM,
        stripHeader: Boolean = true,
        footerEnabled: Boolean = false,
        footerFormat: FramingFormat = FramingFormat.HEX,
        footerBytes: String = "",
        footerScope: FramingScope = FramingScope.DATAGRAM,
        stripFooter: Boolean = true,
    ) = ApplicationFramingProfile(
        headerEnabled,
        headerFormat,
        headerBytes,
        headerScope,
        stripHeader,
        footerEnabled,
        footerFormat,
        footerBytes,
        footerScope,
        stripFooter,
    )

    private fun UdpInputValidationResult.paths() = errors.map { it.path }.toSet()

    private fun assertPaths(result: UdpInputValidationResult, vararg expected: String) {
        assertFalse(result.isValid)
        assertEquals(expected.toSet(), result.paths())
    }

    private companion object {
        const val MIB = 1024 * 1024
    }
}
