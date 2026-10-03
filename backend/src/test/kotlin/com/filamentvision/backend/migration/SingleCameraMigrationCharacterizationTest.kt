package com.filamentvision.backend.migration

import com.filamentvision.backend.adapter.DecodedImage
import com.filamentvision.backend.api.dto.toDto
import com.filamentvision.backend.input.decoder.*
import com.filamentvision.backend.input.profile.*
import com.filamentvision.backend.input.runtime.*
import com.filamentvision.backend.input.transport.*
import com.filamentvision.backend.persistence.sqlite.*
import com.filamentvision.core.frame.CameraId
import com.filamentvision.core.fusion.FusionReason
import com.filamentvision.core.model.MeasurementStatus
import com.filamentvision.core.runtime.RuntimeSession
import com.filamentvision.core.vision.CameraMeasurementSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/** Literal baseline expectations. Later adapters may rename identity, never these wire bytes. */
class SingleCameraMigrationCharacterizationTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun streamOneFixturePreservesEvery34ByteHeaderOffset() {
        val (header, payload) = transportFixture()
        val bytes = StructuredTransportCodec.encode(header, payload)
        val neutralHeader = StructuredTransportHeader(
            compatibilityStreamId = 1u.toUByte(), frameId = 0x0102030405060708uL,
            chunkIndex = 0x0102, chunkCount = 0x0304,
            sourceTimestampMicros = 0x0102030405060708L,
            payloadLength = 3, framePayloadBytes = 0x01020304,
        )
        val neutralBytes = StructuredTransportCodec.encode(neutralHeader, payload)
        val expectedHeader = byteArrayOf(
            0x46, 0x56, 0x55, 0x31, 1, 34, 1, 0,
            1, 2, 3, 4, 5, 6, 7, 8, 1, 2, 3, 4,
            1, 2, 3, 4, 5, 6, 7, 8, 0, 3, 1, 2, 3, 4,
        )
        assertEquals(34, StructuredTransportCodec.HEADER_BYTES)
        assertEquals(37, bytes.size)
        assertArrayEquals(bytes, neutralBytes)
        assertArrayEquals(expectedHeader, bytes.copyOfRange(0, 34))
        assertArrayEquals(byteArrayOf(0x10, 0x20, 0x30), bytes.copyOfRange(34, 37))
        // Each tuple names a field boundary, independently of the production serializer.
        listOf(0 to 4, 4 to 5, 5 to 6, 6 to 7, 7 to 8, 8 to 16,
            16 to 18, 18 to 20, 20 to 28, 28 to 30, 30 to 34).forEach { (start, end) ->
            assertArrayEquals("offset $start..${end - 1}", expectedHeader.copyOfRange(start, end), bytes.copyOfRange(start, end))
        }
        val parsed = TransportDatagramParser().parse(bytes, bytes.size, UdpCameraId.A,
            TransportLimits(maxFrameBytes = 32 * 1024 * 1024)) as TransportParseResult.Accepted
        assertEquals(header, parsed.datagram.header)
        assertArrayEquals(payload, parsed.datagram.payload.copyOwned())
        val neutralParsed = TransportDatagramParser().parse(neutralBytes, neutralBytes.size,
            TransportLimits(maxFrameBytes = 32 * 1024 * 1024)) as TransportParseResult.Accepted
        assertEquals(neutralHeader, neutralParsed.datagram.header)
        assertEquals(1u.toUByte(), neutralParsed.datagram.header.compatibilityStreamId)
        assertEquals(0x0102030405060708L, neutralParsed.datagram.header.sourceTimestampMicros)
        assertEquals(3, neutralParsed.datagram.header.payloadLength)
        assertArrayEquals(payload, neutralParsed.datagram.payload.copyOwned())
    }

    @Test fun unsignedFramePayloadDeclarationsAreRejectedBeforeBorrowingPayload() {
        val (baseline, payload) = transportFixture()
        listOf(0x7fffffffL, 0x80000000L, 0xffffffffL).forEach { declaration ->
            val header = baseline.copy(chunkIndex = 0, chunkCount = 1, framePayloadBytes = declaration)
            val bytes = StructuredTransportCodec.encode(header, payload)
            assertEquals(declaration, java.nio.ByteBuffer.wrap(bytes, 30, 4).int.toLong() and 0xffffffffL)
            var views = 0
            val parser = TransportDatagramParser { input, offset, length ->
                views++; BorrowedPayloadView(input, offset, length)
            }
            val rejected = parser.parse(bytes, bytes.size, UdpCameraId.A, TransportLimits()) as TransportParseResult.Rejected
            assertEquals(TransportErrorCode.FRAME_TOO_LARGE, rejected.error.code)
            assertEquals(0, views)
        }
    }

    @Test fun gray8DecoderPreservesUnsignedIntensitiesAndOpaqueArgb() {
        val image = decodedFixture(Gray8Decoder(2, 2), byteArrayOf(0, 127, 128.toByte(), 255.toByte()))
        assertEquals(2, image.width)
        assertEquals(2, image.height)
        assertArrayEquals(intArrayOf(0xff000000.toInt(), 0xff7f7f7f.toInt(), 0xff808080.toInt(), -1), image.argb)
    }

    @Test fun binaryDecoderPreservesNonzeroBytesAndMsbFirstPackingAcrossByteBoundary() {
        val expected = intArrayOf(-1, 0xff000000.toInt(), -1, 0xff000000.toInt(), 0xff000000.toInt(),
            0xff000000.toInt(), 0xff000000.toInt(), -1, -1)
        assertArrayEquals(expected, decodedFixture(BinaryDecoder(9, 1, BinaryPacking.BIT_PACKED),
            byteArrayOf(0xa1.toByte(), 0x80.toByte())).argb)
        assertArrayEquals(expected, decodedFixture(BinaryDecoder(9, 1, BinaryPacking.BYTE_PER_PIXEL),
            byteArrayOf(1, 0, 255.toByte(), 0, 0, 0, 0, 2, 128.toByte())).argb)
    }

    @Test fun rgb888DecoderPreservesChannelOrderForRgbAndBgr() {
        val bytes = byteArrayOf(0x12, 0x34, 0x56, 255.toByte(), 128.toByte(), 0)
        assertArrayEquals(intArrayOf(0xff123456.toInt(), 0xffff8000.toInt()),
            decodedFixture(Rgb888Decoder(2, 1, RgbChannelOrder.RGB), bytes).argb)
        assertArrayEquals(intArrayOf(0xff563412.toInt(), 0xff0080ff.toInt()),
            decodedFixture(Rgb888Decoder(2, 1, RgbChannelOrder.BGR), bytes).argb)
    }

    @Test fun jpegDecoderPreservesFixedBlackImagePixelsAndMetadata() {
        val bytes = blackJpegFixture()
        val result = JpegDecoder(bytes.size).decode(bytes) as PayloadDecodeResult.Success
        val image = (result.value as DecodedInput.Image).image
        assertEquals(ImageDecodeMetadata(2, 2, bytes.size), result.imageMetadata)
        assertArrayEquals(IntArray(4) { 0xff000000.toInt() }, image.argb)
    }

    @Test fun savingProfileDoesNotApplyUntilExplicitApplyAndReopenRetainsBothRevisions() = runBlocking {
        val initial = profileFixture()
        val path = temporary.root.toPath().resolve("profiles.db")
        SqliteDatabase(path).use { database ->
            val repository = SqliteUdpInputProfileRepository(database, initialProfiles = initial)
            val router = UdpInputRouter(1, initial, UdpCameraRuntimeSwapper { _, _, _, _ -> })
            val service = UdpInputConfigurationService(repository, router)
            val changed = initial.copy(cameraA = initial.cameraA.copy(payload = PayloadProfile.Gray8(2, 2)))
            val saved = service.save(changed)
            assertEquals(2L, saved.revision)
            assertEquals(1L, repository.appliedRevision())
            assertEquals(initial, router.effectiveProfiles)
            assertEquals(initial, router.receiverEffectiveProfiles)
            SqliteDatabase(path).use { reopened ->
                val persisted = SqliteUdpInputProfileRepository(reopened)
                assertEquals(changed, persisted.latest().profiles)
                assertEquals(1L, persisted.appliedRevision())
                assertEquals(initial, requireNotNull(persisted.get(1)).profiles)
            }
            assertEquals(UdpApplyOutcome.APPLIED, service.apply(2, 2).outcome)
            assertEquals(2L, repository.appliedRevision())
            assertEquals(2L, router.appliedRevision)
            assertEquals(changed, router.effectiveProfiles)
            assertEquals(changed, router.receiverEffectiveProfiles)
        }
    }

    @Test fun legacySqlRowsMapPersistedFusionAndAvailableCameraEvidenceToHistory() {
        val path = temporary.root.toPath().resolve("history.db")
        SqliteDatabase(path).use { database -> seedLegacyHistoryFixture(database) }
        SqliteDatabase(path).use { database ->
            val row = SqliteRepositories(database).history("legacy-session").items.single()
            assertEquals("legacy-measurement", row.id)
            assertEquals("legacy-session", row.sessionId)
            val measurement = row.measurement
            assertEquals(1.77, measurement.fusedDiameter, 0.0)
            assertEquals(0.1, measurement.shapeDifference, 0.0)
            assertEquals(0.92, measurement.confidence, 0.0)
            assertEquals(10L, measurement.timestamp)
            assertEquals(MeasurementStatus.CAMERA_B_FAILURE, measurement.status)
            assertEquals(FusionReason.CAMERA_B_MISSING, measurement.fusionReason)
            assertNull(measurement.cameraB)
            val camera = requireNotNull(measurement.cameraA)
            assertEquals(CameraId("A"), camera.cameraId)
            assertEquals(83.0, requireNotNull(camera.pixelWidth), 0.0)
            assertEquals(1.7, camera.diameterMm, 0.0)
            assertEquals(0.91, camera.confidence, 0.0)
            assertEquals(101L, camera.timestamp)
            assertEquals(23L, camera.sequence)
            assertEquals(CameraMeasurementSource.VISION, camera.source)
            val dto = row.toDto().measurement
            assertEquals(1.77, requireNotNull(dto.fusedDiameter), 0.0)
            assertEquals(1.7, requireNotNull(dto.diameterA), 0.0)
            assertNull(dto.diameterB)
        }
    }

    private fun transportFixture() = StructuredTransportHeader(
        cameraId = UdpCameraId.A, frameId = 0x0102030405060708uL,
        chunkIndex = 0x0102, chunkCount = 0x0304,
        sourceTimestampMicros = 0x0102030405060708L,
        payloadLength = 3, framePayloadBytes = 0x01020304,
    ) to byteArrayOf(0x10, 0x20, 0x30)

    private fun decodedFixture(decoder: PayloadDecoder, bytes: ByteArray): DecodedImage {
        val result = decoder.decode(bytes)
        assertTrue("Expected image decode success, got $result", result is PayloadDecodeResult.Success)
        return ((result as PayloadDecodeResult.Success).value as DecodedInput.Image).image
    }

    private fun blackJpegFixture(): ByteArray = ByteArrayOutputStream().use { output ->
        check(ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "jpg", output))
        output.toByteArray()
    }

    private fun profileFixture(): UdpInputProfileSet {
        fun camera(id: UdpCameraId, port: Int) = UdpInputProfile(
            id, false, "127.0.0.1", port, TransportMode.STRUCTURED_CHUNKED,
            PayloadProfile.Gray8(8, 4), ApplicationFramingProfile(), TransportLimits(),
        )
        return UdpInputProfileSet(camera(UdpCameraId.A, 5101), camera(UdpCameraId.B, 5102))
    }

    private fun seedLegacyHistoryFixture(database: SqliteDatabase) {
        SqliteRepositories(database).createSession(RuntimeSession("legacy-session", 7, 1))
        // Raw legacy-style row bypasses today's serializer; null a_source exercises its read fallback.
        database.access(write = true) { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate("""
                    INSERT INTO measurements (
                        id, session_id, timestamp, fused_diameter, shape_difference, confidence, status, fusion_reason,
                        a_id, a_pixel_width, a_diameter, a_confidence, a_timestamp, a_sequence, a_valid,
                        a_roi_left, a_roi_top, a_roi_right, a_roi_bottom, a_left_edge, a_right_edge, a_source
                    ) VALUES (
                        'legacy-measurement', 'legacy-session', 10, 1.77, 0.1, 0.92,
                        'CAMERA_B_FAILURE', 'CAMERA_B_MISSING', 'A', 83.0, 1.7, 0.91, 101, 23, 1,
                        1, 2, 30, 40, 3, 29, NULL
                    )
                """.trimIndent())
            }
        }
    }
}
