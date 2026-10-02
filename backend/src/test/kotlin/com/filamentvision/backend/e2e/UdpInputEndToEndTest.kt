package com.filamentvision.backend.e2e

import com.filamentvision.backend.BackendComposition
import com.filamentvision.backend.input.inspector.RawParseStatus
import com.filamentvision.backend.input.profile.*
import com.filamentvision.backend.input.runtime.UdpCameraInputSource
import com.filamentvision.core.event.DomainEvent
import com.filamentvision.core.event.EventReplay
import java.net.DatagramSocket
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UdpInputEndToEndTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun structuredGrayAcceptsOutOfOrderChunksForIndependentCamerasAndFeedsInspector() = runBlocking {
        val (portA, portB) = freePorts()
        val framing = ApplicationFramingProfile(
            headerEnabled = true,
            headerFormat = FramingFormat.ASCII,
            headerBytes = "FV",
            headerScope = FramingScope.FRAME,
            stripHeader = true,
        )
        val profiles = structuredProfiles(portA, portB, framing)
        val c = open("structured")
        try {
            apply(c, profiles, "structured")
            val lease = c.application.createPacketInspectorLease(c.deviceId, "A", 128)
            c.application.start(c.deviceId, "start-structured", null, null)
            val image = grayImage()
            UdpTestSender().use { sender ->
                sender.sendStructured(portA, UdpCameraId.A, 1u, "NO".encodeToByteArray() + image, 97)
                await { c.application.udpDiagnostics(c.deviceId, "A").framesDropped >= 1 }
                assertEquals(1, c.application.udpDiagnostics(c.deviceId, "A").invalidHeaderCount)

                val valid = "FV".encodeToByteArray() + image
                val chunkCount = (valid.size + 96) / 97
                val reverse = (0 until chunkCount).reversed().toList()
                sender.sendStructured(portA, UdpCameraId.A, 2u, valid, 97, reverse)
                sender.sendStructured(portB, UdpCameraId.B, 2u, valid, 97, reverse)
                await { c.application.latest(c.deviceId) != null }
            }

            val source = c.inputSource as UdpCameraInputSource
            val a = c.application.udpDiagnostics(c.deviceId, "A")
            val b = c.application.udpDiagnostics(c.deviceId, "B")
            assertTrue(a.outOfOrderChunks!! > 0)
            assertTrue(b.outOfOrderChunks!! > 0)
            assertEquals(1, source.activeProducerCount)
            assertEquals(1, source.maximumActiveProducerCount)
            val snapshot = c.application.packetInspectorSnapshot(c.deviceId, "A", lease.id, 128)
            assertNotNull(snapshot.raw.transport)
            assertNotNull(snapshot.parsedFrame)
            assertEquals("GRAY8", snapshot.parsedFrame!!.payloadFormat)
            c.application.stop(c.deviceId, "stop-structured")
            assertEquals(0, source.activeProducerCount)
        } finally { c.close() }
        Unit
    }

    @Test fun parserOnlyApplyAndPortRestartTakeEffectWithoutDuplicatingProducer() = runBlocking {
        val (portA, portB) = freePorts()
        val oldFraming = frameHeader("A")
        val c = open("apply")
        try {
            apply(c, structuredProfiles(portA, portB, oldFraming), "initial")
            c.application.start(c.deviceId, "start-apply", null, null)
            UdpTestSender().use { sender ->
                val image = grayImage()
                sender.sendStructured(portA, UdpCameraId.A, 10u, byteArrayOf('A'.code.toByte()) + image, 128)
                await { c.application.udpDiagnostics(c.deviceId, "A").framesCompleted == 1L }

                val parserRevision = c.application.saveUdpInput(
                    c.deviceId,
                    "save-parser",
                    structuredProfiles(portA, portB, frameHeader("B")),
                ).savedRevision
                c.application.applyUdpInput(c.deviceId, "apply-parser", parserRevision, parserRevision)
                sender.sendStructured(portA, UdpCameraId.A, 11u, byteArrayOf('B'.code.toByte()) + image, 128)
                await { c.application.udpDiagnostics(c.deviceId, "A").framesCompleted == 2L }

                val newPort = freePorts().first
                val restarted = structuredProfiles(newPort, portB, frameHeader("B"))
                val restartRevision = c.application.saveUdpInput(c.deviceId, "save-restart", restarted).savedRevision
                c.application.applyUdpInput(c.deviceId, "apply-restart", restartRevision, restartRevision)
                sender.sendStructured(newPort, UdpCameraId.A, 12u, byteArrayOf('B'.code.toByte()) + image, 128)
                await { c.application.udpDiagnostics(c.deviceId, "A").framesCompleted == 3L }
                assertEquals(0, c.application.udpDiagnostics(c.deviceId, "B").datagramsReceived)
            }
            val source = c.inputSource as UdpCameraInputSource
            assertEquals(1, source.activeProducerCount)
            assertEquals(1, source.maximumActiveProducerCount)
            assertEquals(c.udpInputRouter.effectiveProfiles, c.udpInputRouter.receiverEffectiveProfiles)
            assertEquals(c.udpInputRouter.appliedRevision, c.application.udpInputConfiguration(c.deviceId).appliedRevision)
            c.application.stop(c.deviceId, "stop-apply")
        } finally { c.close() }
        Unit
    }

    @Test fun singleDiameterSourceFilterAndSentinelDropsAreObservableWithoutEnteringPipeline() = runBlocking {
        val (portA, portB) = freePorts()
        val limits = TransportLimits(maxFrameBytes = 32, maxChunkPayloadBytes = 32)
        fun diameter(id: UdpCameraId, port: Int, sourceIp: String?) = UdpInputProfile(
            id, true, sourceIp, port, TransportMode.SINGLE_DATAGRAM,
            PayloadProfile.Diameter(DiameterValueEncoding.ASCII, DiameterByteOrder.BIG_ENDIAN),
            ApplicationFramingProfile(), limits,
        )
        val profiles = UdpInputProfileSet(diameter(UdpCameraId.A, portA, null), diameter(UdpCameraId.B, portB, "127.0.0.2"))
        val c = open("single")
        try {
            apply(c, profiles, "single")
            val lease = c.application.createPacketInspectorLease(c.deviceId, "A", 64)
            val mismatchLease = c.application.createPacketInspectorLease(c.deviceId, "B", 64)
            c.application.start(c.deviceId, "start-single", null, null)
            UdpTestSender().use { sender ->
                sender.send(portA, "1.75".encodeToByteArray())
                await { c.application.latest(c.deviceId) != null }
                val parsed = c.application.packetInspectorSnapshot(c.deviceId, "A", lease.id, 64)
                assertEquals("DIAMETER", parsed.parsedFrame!!.payloadFormat)

                repeat(12) { sender.send(portA, "2.00".encodeToByteArray()) }
                await { c.runtime.state.value.statistics.sampleCount >= 2 }

                sender.send(portB, "1.80".encodeToByteArray())
                await { c.application.udpDiagnostics(c.deviceId, "B").sourceFilteredDatagrams == 1L }
                assertEquals(
                    RawParseStatus.SOURCE_IP_MISMATCH,
                    c.application.packetInspectorSnapshot(c.deviceId, "B", mismatchLease.id, 64).raw.status,
                )

                sender.send(portA, ByteArray(33) { 1 })
                await { c.application.udpDiagnostics(c.deviceId, "A").oversizedOrTruncatedDatagrams == 1L }
                assertEquals(
                    RawParseStatus.DATAGRAM_TOO_LARGE_OR_TRUNCATED,
                    c.application.packetInspectorSnapshot(c.deviceId, "A", lease.id, 64).raw.status,
                )
            }
            c.application.stop(c.deviceId, "stop-single")
            val replay = c.events.replayAfter(c.deviceId, 0) as EventReplay.Available
            assertTrue(replay.events.any { it.event is DomainEvent.SessionStarted })
            assertTrue(replay.events.any { it.event is DomainEvent.Measurement })
            assertTrue(replay.events.any { it.event is DomainEvent.Alarm })
            assertTrue(replay.events.any { it.event is DomainEvent.SessionFinalized })
            assertTrue(c.packetInspector.retainedPreviewBytes <= 128)
        } finally { c.close() }
        Unit
    }

    private suspend fun apply(c: BackendComposition, profiles: UdpInputProfileSet, key: String) {
        val saved = c.application.saveUdpInput(c.deviceId, "save-$key", profiles)
        c.application.applyUdpInput(c.deviceId, "apply-$key", saved.savedRevision, saved.savedRevision)
    }

    private suspend fun open(name: String) = BackendComposition.open(
        temporary.root.toPath().resolve("$name.db"),
        temporary.root.toPath().resolve("$name-objects"),
    )

    private fun structuredProfiles(portA: Int, portB: Int, framing: ApplicationFramingProfile) = UdpInputProfileSet(
        structured(UdpCameraId.A, portA, framing),
        structured(UdpCameraId.B, portB, framing),
    )

    private fun structured(id: UdpCameraId, port: Int, framing: ApplicationFramingProfile) = UdpInputProfile(
        id, true, null, port, TransportMode.STRUCTURED_CHUNKED,
        PayloadProfile.Gray8(48, 8), framing,
        TransportLimits(maxInFlightFrames = 2, maxFrameBytes = 1_024, frameTimeoutMs = 500,
            maxChunkCount = 16, maxChunkPayloadBytes = 128),
    )

    private fun frameHeader(value: String) = ApplicationFramingProfile(
        headerEnabled = true,
        headerFormat = FramingFormat.ASCII,
        headerBytes = value,
        headerScope = FramingScope.FRAME,
        stripHeader = true,
    )

    private fun grayImage(): ByteArray {
        val width = 35
        val left = (48 - width) / 2
        return ByteArray(48 * 8) { index ->
            if (index % 48 in left until left + width) 20 else 200.toByte()
        }
    }

    private suspend fun await(condition: suspend () -> Boolean) = withTimeout(5_000) {
        while (!condition()) delay(10)
    }

    private fun freePorts(): Pair<Int, Int> {
        val a = DatagramSocket(0)
        val b = DatagramSocket(0)
        return try { a.localPort to b.localPort } finally { a.close(); b.close() }
    }
}
