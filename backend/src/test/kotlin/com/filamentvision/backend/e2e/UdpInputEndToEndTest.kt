package com.filamentvision.backend.e2e

import com.filamentvision.backend.BackendComposition
import com.filamentvision.backend.application.PageQuery
import com.filamentvision.backend.input.profile.*
import com.filamentvision.backend.input.runtime.*
import com.filamentvision.backend.input.transport.StructuredTransportCodec
import com.filamentvision.backend.input.transport.StructuredTransportHeader
import com.filamentvision.backend.input.transport.EpochClock
import com.filamentvision.backend.input.inspector.RawParseStatus
import com.filamentvision.backend.input.decoder.PayloadDecoder
import com.filamentvision.core.event.DomainEvent
import com.filamentvision.core.event.EventReplay
import com.filamentvision.core.frame.*
import com.filamentvision.core.input.CameraInput
import com.filamentvision.core.runtime.MonitoringLifecycle
import java.net.DatagramSocket
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UdpInputEndToEndTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun silentRealSourceWaitsWithoutFakeDataOrExtraProducerOnRepeatedStart() = runBlocking {
        val c = open("silent")
        val source = c.inputSource as RaspberryPiUdpInputSource
        try {
            apply(c, profile(freePort()))
            val starts = (1..12).map { index -> async { c.application.start(c.deviceId, "start-$index", null, null) } }.awaitAll()
            assertEquals(1, starts.map { it.session!!.id }.toSet().size)
            await { source.operationalStatus().status == UdpOperationalStatus.WAITING_FOR_RASPBERRY_PI }
            assertNull(c.application.latest(c.deviceId))
            assertEquals(0L, c.runtime.state.value.statistics.sampleCount)
            assertEquals(0L, c.previews.metrics.publishedFrames)
            assertEquals(1, source.activeReceiverCount)
            assertEquals(1, c.runtime.state.value.maximumActiveProducerCount)
            assertEquals(1L, source.totalProducerStarts)
        } finally { c.close() }
    }

    @Test fun everyProductionImageFormatCrossesOneRealSocketIntoVisionAndPreview() = runBlocking {
        val formats = listOf(ImagePayloadProfile.Jpeg(4096, 48, 8), ImagePayloadProfile.Gray8(48, 8),
            ImagePayloadProfile.Binary(48, 8, BinaryPacking.BYTE_PER_PIXEL),
            ImagePayloadProfile.Binary(48, 8, BinaryPacking.BIT_PACKED),
            ImagePayloadProfile.Rgb888(48, 8, RgbChannelOrder.RGB),
            ImagePayloadProfile.Rgb888(48, 8, RgbChannelOrder.BGR))
        val c = open("all-formats")
        val source = c.inputSource as RaspberryPiUdpInputSource
        val port = freePort()
        try {
            apply(c, profile(port).copy(payload = formats.first(), transportLimits = TransportLimits(2, 4096, 1000, 128, 128)))
            val session = c.application.start(c.deviceId, "start", null, null).session!!.id
            val lease = source.packetInspector.createLease(256)
            UdpTestSender().use { sender ->
                formats.forEachIndexed { index, format ->
                    if (index > 0) apply(c, source.effectiveProfile.copy(payload = format))
                    val payload = stripPayload(format)
                    val chunkCount = (payload.size + 30) / 31
                    val before = source.udpDiagnostics().datagramsReceived
                    sender.sendStructured(port, (100 + index).toULong(), payload, 31,
                        (0 until chunkCount).reversed().toList())
                    await { source.udpDiagnostics().datagramsReceived == before + chunkCount }
                    // Missing production envelope must fail as a packet contract, not a timeout.
                    assertEquals("Production sender must include the exact two-byte header", 0L,
                        source.udpDiagnostics().invalidHeaderCount)
                    await { c.runtime.state.value.statistics.sampleCount == index + 1L }
                    val measurement = c.application.latest(c.deviceId)!!.measurement
                    assertEquals(1.75, measurement.diameterMm, 1e-9)
                    assertEquals("camera", measurement.evidence.single().cameraId.value)
                    assertNull(measurement.legacyFusion)
                    assertEquals(1, source.activeProducerCount)
                    assertEquals(1, source.activeReceiverCount)
                    assertEquals(1, c.previewKeys.size)
                    assertEquals(1, c.previews.metrics.retainedFrames)
                    if (format is ImagePayloadProfile.Rgb888) {
                        assertArrayEquals(byteArrayOf(255.toByte(), 160.toByte(), 200.toByte(), 240.toByte()),
                            c.previews.latest(c.previewKeys.single())!!.copyPayload().copyOfRange(0, 4))
                    }
                    assertEquals("VALID", source.packetInspector.latest(lease.id)!!.parsedFrame!!.decodeResult)
                    assertTrue(source.packetInspector.retainedPreviewBytes <= 512)
                    delay(210)
                }
            }
            c.application.stop(c.deviceId, "stop")
            assertEquals(6, c.application.history(session, PageQuery()).items.size)
            assertEquals(1L, source.totalProducerStarts)
            source.packetInspector.releaseLease(lease.id)
            assertEquals(0, source.packetInspector.retainedPreviewBytes)
        } finally { c.close() }
    }

    private fun stripPayload(format: ImagePayloadProfile): ByteArray {
        fun dark(index: Int) = index % 48 in 6 until 41
        return when (format) {
            is ImagePayloadProfile.Gray8 -> ByteArray(384) { if (dark(it)) 20 else 200.toByte() }
            is ImagePayloadProfile.Binary -> when (format.packing) {
                BinaryPacking.BYTE_PER_PIXEL -> ByteArray(384) { if (dark(it)) 0 else 1 }
                BinaryPacking.BIT_PACKED -> ByteArray(48) { byte ->
                    (0..7).fold(0) { value, bit -> value or (if (dark(byte * 8 + bit)) 0 else (1 shl (7 - bit))) }.toByte()
                }
            }
            is ImagePayloadProfile.Rgb888 -> ByteArray(1152) { index ->
                if (dark(index / 3)) 20 else {
                    // Distinct channels ensure RGB/BGR traverse real channel-order decoding.
                    val rgb = if (format.channelOrder == RgbChannelOrder.RGB) index % 3 else 2 - index % 3
                    intArrayOf(160, 200, 240)[rgb].toByte()
                }
            }
            is ImagePayloadProfile.Jpeg -> ByteArrayOutputStream().use { output ->
                val image = BufferedImage(48, 8, BufferedImage.TYPE_INT_RGB)
                for (y in 0 until 8) for (x in 0 until 48) image.setRGB(x, y,
                    if (dark(y * 48 + x)) 0xff141414.toInt() else 0xffc8c8c8.toInt())
                check(ImageIO.write(image, "jpeg", output)); output.toByteArray()
            }
        }
    }

    @Test fun malformedEnvelopeDuplicatesConflictsMissingTimeoutAndLateChunksStayIsolated() = runBlocking {
        val c = open("packet-errors")
        val source = c.inputSource as RaspberryPiUdpInputSource
        val port = freePort()
        try {
            apply(c, profile(port).copy(transportLimits = TransportLimits(2, 1024, 1000, 16, 128)))
            c.application.start(c.deviceId, "start", null, null)
            val lease = source.packetInspector.createLease(256)
            UdpTestSender().use { sender ->
                suspend fun packet(bytes: ByteArray) {
                    val before = source.udpDiagnostics().datagramsReceived
                    sender.send(port, bytes)
                    await { source.udpDiagnostics().datagramsReceived == before + 1 }
                    delay(10) // onReceived precedes synchronous parsing; exact counters below wait separately.
                }
                val payload = stripPayload(ImagePayloadProfile.Gray8(48, 8))
                fun chunk(id: Long, index: Int, mutate: Boolean = false) =
                    byteArrayOf(0xaa.toByte(), 1) + StructuredTransportCodec.encode(
                        StructuredTransportHeader(compatibilityStreamId = 1u, frameId = id.toULong(),
                            chunkIndex = index, chunkCount = 3, sourceTimestampMicros = id * 1000,
                            payloadLength = 128, framePayloadBytes = 384),
                        payload.copyOfRange(index * 128, (index + 1) * 128).also { if (mutate) it[0] = 99 }) + byteArrayOf(13, 10)
                packet(chunk(1, 0).also { it[0] = 0 })
                await { source.udpDiagnostics().invalidHeaderCount == 1L }
                assertEquals(RawParseStatus.HEADER_MISMATCH, source.packetInspector.latest(lease.id)!!.raw.status)
                packet(chunk(2, 0).also { it[it.lastIndex] = 0 })
                await { source.udpDiagnostics().invalidFooterCount == 1L }
                assertEquals(RawParseStatus.FOOTER_MISMATCH, source.packetInspector.latest(lease.id)!!.raw.status)
                assertEquals(0L, source.udpDiagnostics().invalidTransportHeaders)
                packet(chunk(3, 0)); packet(chunk(3, 0))
                await { source.udpDiagnostics().duplicateChunks == 1L }
                packet(chunk(3, 0, mutate = true))
                await { source.udpDiagnostics().duplicateChunks == 2L }
                packet(chunk(4, 0)); packet(chunk(4, 2))
                await { source.udpDiagnostics().reassemblyTimeouts == 1L }
                assertEquals(1L, source.udpDiagnostics().missingChunks)
                packet(chunk(4, 1))
                await { source.udpDiagnostics().lateChunks == 1L }
                assertEquals(0L, source.udpDiagnostics().framesCompleted)
                assertNull(c.application.latest(c.deviceId))
                packet(chunk(5, 2)); packet(chunk(5, 0)); packet(chunk(5, 1))
                await { c.runtime.state.value.statistics.sampleCount == 1L }
                assertEquals(1.75, c.application.latest(c.deviceId)!!.measurement.diameterMm, 1e-9)
                assertEquals("VALID", source.packetInspector.latest(lease.id)!!.parsedFrame!!.decodeResult)
            }
            source.packetInspector.releaseLease(lease.id)
            assertEquals(0, source.packetInspector.retainedPreviewBytes)
        } finally { c.close() }
    }

    @Test fun saveBindFailureAndActualSqliteCasConflictRestoreOldLiveSocketAndParser() = runBlocking {
        val c = open("rollback-loopback")
        val source = c.inputSource as RaspberryPiUdpInputSource
        val port = freePort()
        try {
            apply(c, profile(port))
            c.application.start(c.deviceId, "start", null, null)
            val service = checkNotNull(c.udpInputService)
            DatagramSocket(0).use { occupied ->
                val saved = service.save(c.udpProfileRepository.state().saved.revision,
                    profile(occupied.localPort))
                assertEquals(port, source.effectiveProfile.listenPort) // Save cannot mutate the socket.
                assertTrue(runCatching { service.apply(saved.revision) }.isFailure)
                assertEquals(port, source.effectiveProfile.listenPort)
                assertEquals(port, c.udpProfileRepository.state().applied.profile.listenPort)
            }
            val old = c.udpProfileRepository.state().applied
            val candidate = service.save(c.udpProfileRepository.state().saved.revision,
                profile(port).copy(envelope = DatagramEnvelopeProfile("BB 02", "0D 0A")))
            // Only the repository decision is intercepted; source, sockets, parser and SQLite CAS are real.
            val competing = object : RaspberryPiUdpInputProfileRepository by c.udpProfileRepository {
                override suspend fun markApplied(expectedAppliedRevision: Long, revision: Long): Boolean {
                    c.udpProfileRepository.save(candidate.revision, candidate.profile.copy(envelope = DatagramEnvelopeProfile("CC 03", "0D 0A")))
                    return c.udpProfileRepository.markApplied(expectedAppliedRevision, revision)
                }
            }
            val conflicting = RaspberryPiUdpInputConfigurationService(competing, checkNotNull(c.udpInputRouter))
            assertEquals(RaspberryPiUdpApplyOutcome.CONFLICT, conflicting.apply(candidate.revision).outcome)
            assertEquals(old.profile, source.effectiveProfile)
            assertEquals(old, c.udpProfileRepository.state().applied)
            assertEquals(old.revision, c.udpInputRouter.appliedRevision)
            UdpTestSender().use { sender -> send(sender, port, 88) }
            await { c.runtime.state.value.statistics.sampleCount == 1L }
            assertEquals(1.75, c.application.latest(c.deviceId)!!.measurement.diameterMm, 1e-9)
            assertEquals(1, source.activeReceiverCount)
            assertEquals(1L, source.totalProducerStarts)
        } finally { c.close() }
        DatagramSocket(port).close()
    }

    @Test fun cancellationAfterPreviewReleasesDecodedPermitBeforeRestart() = runBlocking {
        val previewed = CompletableDeferred<Unit>()
        val resumeHandoff = CountDownLatch(1)
        val pauseOnce = AtomicBoolean(true)
        val composition = AtomicReference<BackendComposition>()
        val c = BackendComposition.open(temporary.root.toPath().resolve("cancel-handoff.db"),
            temporary.root.toPath().resolve("cancel-handoff-objects"),
            // Keep the real UDP source clock independent of the tap's deterministic handoff gate.
            inputSourceFactory = BackendInputSourceFactory { _, _, epoch, _, profile ->
                RaspberryPiUdpInputSource(profile, epochClock = EpochClock(epoch))
            }, monotonicMillis = {
                if (composition.get()?.previews?.metrics?.publishedFrames == 1L && pauseOnce.compareAndSet(true, false)) {
                    previewed.complete(Unit)
                    check(resumeHandoff.await(5, TimeUnit.SECONDS)) { "Handoff gate was not released" }
                }
                System.nanoTime() / 1_000_000
            })
        composition.set(c)
        val source = c.inputSource as RaspberryPiUdpInputSource
        val port = freePort()
        try {
            apply(c, profile(port))
            c.application.start(c.deviceId, "start", null, null)
            UdpTestSender().use { sender ->
                send(sender, port, 1)
                withTimeout(5000) { previewed.await() }
                assertEquals(0, source.pendingInputCount)
                assertEquals(0L, c.runtime.state.value.statistics.sampleCount)
                val stopping = async { c.application.stop(c.deviceId, "stop") }
                await { source.activeProducerCount == 0 } // Finalizer cancels the worker before stopping the source.
                resumeHandoff.countDown()
                withTimeout(5000) { stopping.await() }
                assertEquals(0L, c.runtime.state.value.statistics.sampleCount)

                c.application.start(c.deviceId, "restart", null, null)
                send(sender, port, 2)
                await { source.udpDiagnostics().let { it.framesCompleted == 2L || it.processingBackpressureDrops > 0 } }
                assertEquals("Cancelled tap must release the caller-owned decoded frame", 0L,
                    source.udpDiagnostics().processingBackpressureDrops)
                await { c.runtime.state.value.statistics.sampleCount == 1L }
                assertEquals(1.75, c.application.latest(c.deviceId)!!.measurement.diameterMm, 1e-9)
                assertEquals(2L, source.udpDiagnostics().framesCompleted)
                assertEquals(1, source.activeProducerCount)
                assertEquals(1, source.activeReceiverCount)
            }
        } finally { resumeHandoff.countDown(); c.close() }
    }

    @Test fun previewFailureReleasesDecodedPermitBeforeRestart() = runBlocking {
        val c = open("preview-failure")
        val source = c.inputSource as RaspberryPiUdpInputSource
        val port = freePort()
        try {
            apply(c, profile(port))
            c.application.start(c.deviceId, "start", null, null)
            c.previews.close() // Force the real publisher to throw before the Runtime receives the frame.
            UdpTestSender().use { sender ->
                send(sender, port, 1)
                await { c.runtime.state.value.lifecycle == MonitoringLifecycle.ERROR }
                assertEquals(0L, c.runtime.state.value.statistics.sampleCount)
                assertEquals(0, source.activeProducerCount)
                c.application.start(c.deviceId, "restart", null, null)
                send(sender, port, 2)
                await { source.udpDiagnostics().let { it.framesCompleted == 2L || it.processingBackpressureDrops > 0 } }
                assertEquals("A preview exception must release the decoded frame", 0L,
                    source.udpDiagnostics().processingBackpressureDrops)
                await { c.runtime.state.value.lifecycle == MonitoringLifecycle.ERROR }
                assertEquals(2L, source.udpDiagnostics().framesCompleted)
                assertEquals(2, c.application.errors(null).size)
                val reliable = (c.events.replayAfter(c.deviceId, 0) as EventReplay.Available).events
                assertEquals(2, reliable.count { it.event is DomainEvent.Error })
                assertEquals(2, reliable.count { it.event is DomainEvent.SessionFinalized })
            }
        } finally { c.close() }
    }

    @Test fun frameLeaseIsReleasedOnceOnlyAfterSynchronousVisionAcceptsIt() = runBlocking {
        val c = open("lease-order")
        val source = c.inputSource as RaspberryPiUdpInputSource
        val releases = AtomicInteger()
        val samplesAtRelease = AtomicLong(-1)
        val frame = CameraFrame(CameraId("camera"), 1000, 1000, 48, 8, PixelFormat.ARGB32,
            PixelBuffer.ints(IntArray(48 * 8) { if (it % 48 in 6 until 41) 0xff141414.toInt() else 0xffc8c8c8.toInt() }),
            0, false, 1, lease = FrameLease {
                samplesAtRelease.set(c.runtime.state.value.statistics.sampleCount)
                releases.incrementAndGet()
            })
        try {
            apply(c, profile(freePort()))
            c.application.start(c.deviceId, "start", null, null)
            source.offerInput(CameraInput.Image(frame))
            await { releases.get() > 0 }
            assertEquals(1L, samplesAtRelease.get())
            assertEquals(1.75, c.application.latest(c.deviceId)!!.measurement.diameterMm, 1e-9)
            c.application.stop(c.deviceId, "stop")
            frame.close()
            assertEquals(1, releases.get())
        } finally { c.close(); frame.close() }
    }

    @Test fun outOfOrderPiImagesProduceOneNeutralPreviewAndReliableDurableEvents() = runBlocking {
        val port = freePort()
        val decoded = AtomicInteger()
        val c = BackendComposition.open(temporary.root.toPath().resolve("structured.db"),
            temporary.root.toPath().resolve("structured-objects"),
            inputSourceFactory = BackendInputSourceFactory { _, _, _, _, profile ->
                RaspberryPiUdpInputSource(profile, compiledFactory = { p, observer ->
                    val real = CompiledRaspberryPiInputProfile.compile(p, observer)
                    CompiledRaspberryPiInputProfile(real.profile, real.envelopeParser, real.transportParser,
                        real.reassembler, PayloadDecoder { bytes ->
                            if (decoded.incrementAndGet() == 4) throw java.io.IOException("Injected decoder failure")
                            real.decoder.decode(bytes)
                        })
                })
            })
        val source = c.inputSource as RaspberryPiUdpInputSource
        val releaseInspector = CountDownLatch(1)
        var inspectorReader: Thread? = null
        try {
            apply(c, profile(port))
            val session = c.application.start(c.deviceId, "start", null, null).session!!.id
            val inspectorLease = source.packetInspector.createLease(256)
            val previewBlocked = CompletableDeferred<Unit>()
            val slowPreview = launch { c.previews.frames(c.previewKeys).collect {
                previewBlocked.complete(Unit); awaitCancellation()
            } }
            UdpTestSender().use { sender ->
                send(sender, port, 1, header = byteArrayOf(0, 1))
                await { source.udpDiagnostics().invalidHeaderCount == 4L }
                send(sender, port, 2)
                await { c.runtime.state.value.statistics.sampleCount == 1L }
                withTimeout(3000) { previewBlocked.await() }
                val inspectorLock = source.packetInspector.javaClass.getDeclaredField("lock").also { it.isAccessible = true }
                    .get(source.packetInspector) as ReentrantLock
                val inspectorBlocked = CountDownLatch(1)
                inspectorReader = thread(name = "congested-inspector-reader", isDaemon = true) {
                    inspectorLock.lock()
                    try { inspectorBlocked.countDown(); check(releaseInspector.await(10, TimeUnit.SECONDS)) }
                    finally { inspectorLock.unlock() }
                }
                assertTrue(inspectorBlocked.await(3, TimeUnit.SECONDS))
                assertEquals(1.75, c.application.latest(c.deviceId)!!.measurement.diameterMm, 1e-9)
                delay(140)
                send(sender, port, 3, width = 37)
                await { c.runtime.state.value.statistics.sampleCount == 2L }
                delay(140)
                send(sender, port, 4, width = 37)
                await { c.runtime.state.value.statistics.sampleCount == 3L }
            }
            assertTrue(source.udpDiagnostics().outOfOrderChunks!! > 0)
            assertEquals(1, source.activeProducerCount)
            assertEquals(1, source.activeReceiverCount)
            assertEquals(1L, source.totalProducerStarts)
            assertEquals(1, c.previews.metrics.retainedFrames)
            assertTrue(c.previews.metrics.pendingFrames <= 1)
            assertTrue(c.previews.metrics.droppedFrames > 0)
            val preview = c.previews.latest(c.previewKeys.single())!!
            assertEquals("camera", preview.key.cameraId.value)
            assertTrue(preview.payloadSize <= 1280 * 720 * 4)
            UdpTestSender().use { sender -> send(sender, port, 5, width = 37) }
            await { c.runtime.state.value.lifecycle == MonitoringLifecycle.ERROR }
            assertEquals(1, c.application.errors(null).size)
            c.application.stop(c.deviceId, "stop")
            val history = c.application.history(session, PageQuery()).items
            assertEquals(3, history.size)
            assertTrue(history.all { it.measurement.evidence.single().cameraId.value == "camera" })
            assertTrue(history.all { it.measurement.legacyFusion == null })
            assertEquals(1.85, history.last().measurement.diameterMm, 1e-9)
            val persistedAlarms = c.application.alarms(session)
            assertTrue(persistedAlarms.isNotEmpty())
            val replay = c.events.replayAfter(c.deviceId, 0) as EventReplay.Available
            val reliableMeasurements = replay.events.map { it.event }.filterIsInstance<DomainEvent.Measurement>()
            assertEquals(3, reliableMeasurements.size)
            assertEquals(history.map { it.id }.sorted(), reliableMeasurements.map { it.record.id }.sorted())
            val reliableAlarms = replay.events.map { it.event }.filterIsInstance<DomainEvent.Alarm>()
            assertEquals(persistedAlarms.size, reliableAlarms.size)
            assertEquals(persistedAlarms.map { it.id }.sorted(), reliableAlarms.map { it.record.id }.sorted())
            val finalizations = replay.events.map { it.event }.filterIsInstance<DomainEvent.SessionFinalized>()
            assertEquals(1, finalizations.size)
            assertEquals(session, finalizations.single().finalization.session.id)
            assertEquals(c.application.session(session).finalization, finalizations.single().finalization)
            assertEquals((1L..replay.latestSequence).toList(), replay.events.map { it.sequence })
            val finalAt = replay.events.indexOfFirst { it.event is DomainEvent.SessionFinalized }
            assertTrue(finalAt > replay.events.indexOfLast { it.event is DomainEvent.Measurement })
            assertTrue(finalAt > replay.events.indexOfLast { it.event is DomainEvent.Alarm })
            assertEquals(1, replay.events.count { it.event is DomainEvent.Error })
            assertTrue(finalAt > replay.events.indexOfLast { it.event is DomainEvent.Error })
            slowPreview.cancelAndJoin()
            // All three durable measurements and the alarm/finalization arrived while Inspector was locked.
            assertEquals(1L, releaseInspector.count)
            releaseInspector.countDown(); inspectorReader!!.join(3000)
            assertFalse(inspectorReader!!.isAlive)
            assertTrue(source.packetInspector.retainedPreviewBytes <= 512)
            source.packetInspector.releaseLease(inspectorLease.id)
            assertEquals(0, source.packetInspector.retainedPreviewBytes)
            assertEquals(0 to 0, c.runtime.queuedCounts())
        } finally { releaseInspector.countDown(); inspectorReader?.join(3000); c.close() }
        assertEquals(0, source.activeProducerCount)
        assertEquals(0, source.activeReceiverCount)
        assertEquals(0, source.pendingInputCount)
        assertEquals(0, c.previews.metrics.retainedFrames)
        DatagramSocket(port).close()
    }

    @Test fun parserApplyAndPortRestartUseOneRuntimeOwnedSource() = runBlocking {
        val c = open("apply")
        val source = c.inputSource as RaspberryPiUdpInputSource
        val port = freePort()
        try {
            apply(c, profile(port))
            c.application.start(c.deviceId, "start", null, null)
            UdpTestSender().use { sender ->
                send(sender, port, 10)
                await { c.runtime.state.value.statistics.sampleCount == 1L }
                val changed = profile(port).copy(envelope = DatagramEnvelopeProfile("BB 02", "0D 0A"))
                apply(c, changed)
                send(sender, port, 11)
                await { source.udpDiagnostics().invalidHeaderCount == 4L }
                send(sender, port, 12, header = byteArrayOf(0xbb.toByte(), 2))
                await { c.runtime.state.value.statistics.sampleCount == 2L }
                val next = changed.copy(listenPort = freePort())
                apply(c, next)
                DatagramSocket(port).close()
                send(sender, next.listenPort, 13, header = byteArrayOf(0xbb.toByte(), 2))
                await { c.runtime.state.value.statistics.sampleCount == 3L }
                assertEquals(next, source.effectiveProfile)
                assertEquals(next, c.udpProfileRepository.state().applied.profile)
                assertEquals(c.udpProfileRepository.state().applied.revision, checkNotNull(c.udpInputRouter).appliedRevision)
            }
            assertEquals(1, source.activeReceiverCount)
            assertEquals(1L, source.totalProducerStarts)
        } finally { c.close() }
    }

    @Test fun sourceFilterAndSentinelDropsNeverEnterVision() = runBlocking {
        val c = open("guards")
        val source = c.inputSource as RaspberryPiUdpInputSource
        val port = freePort()
        try {
            apply(c, profile(port).copy(raspberryPiIpv4 = "127.0.0.2"))
            c.application.start(c.deviceId, "start", null, null)
            val lease = source.packetInspector.createLease(64)
            UdpTestSender().use { sender ->
                sender.send(port, byteArrayOf(1))
                await { source.udpDiagnostics().sourceFilteredDatagrams == 1L }
                await { source.packetInspector.previewCopies == 1L }
                assertEquals(RawParseStatus.SOURCE_IP_MISMATCH, source.packetInspector.latest(lease.id)!!.raw.status)
                assertNull(source.packetInspector.latest(lease.id)!!.raw.transport)
                assertNull(source.packetInspector.latest(lease.id)!!.parsedFrame)
                apply(c, profile(port))
                sender.send(port, ByteArray(38 + 128 + 1))
                await { source.udpDiagnostics().oversizedOrTruncatedDatagrams == 1L }
                await { source.packetInspector.previewCopies == 2L }
                sender.send(port, ByteArray(65000))
                await { source.udpDiagnostics().oversizedOrTruncatedDatagrams == 2L }
                await { source.packetInspector.previewCopies == 3L }
                assertEquals(RawParseStatus.DATAGRAM_TOO_LARGE_OR_TRUNCATED, source.packetInspector.latest(lease.id)!!.raw.status)
            }
            assertEquals(0L, source.udpDiagnostics().framesCompleted)
            assertNull(c.application.latest(c.deviceId))
            assertEquals(0L, c.previews.metrics.publishedFrames)
            source.packetInspector.releaseLease(lease.id)
            assertEquals(0, source.packetInspector.retainedPreviewBytes)
        } finally { c.close() }
    }

    private suspend fun apply(c: BackendComposition, profile: RaspberryPiUdpInputProfile) {
        val service = checkNotNull(c.udpInputService)
        val saved = service.save(c.udpProfileRepository.state().saved.revision, profile)
        assertEquals(RaspberryPiUdpApplyOutcome.APPLIED, service.apply(saved.revision).outcome)
    }
    private suspend fun open(name: String) = BackendComposition.open(
        temporary.root.toPath().resolve("$name.db"), temporary.root.toPath().resolve("$name-objects"))
    private fun profile(port: Int) = RaspberryPiUdpInputProfile.defaults().copy(
        enabled = true, listenPort = port, payload = ImagePayloadProfile.Gray8(48, 8),
        transportLimits = TransportLimits(2, 1024, 500, 16, 128))
    private fun send(sender: UdpTestSender, port: Int, id: Long, width: Int = 35, header: ByteArray = byteArrayOf(0xaa.toByte(), 1)) {
        val left = (48 - width) / 2
        val pixels = ByteArray(48 * 8) { if (it % 48 in left until left + width) 20 else 200.toByte() }
        val chunks = pixels.asList().chunked(97).map { it.toByteArray() }
        for (i in chunks.indices.reversed()) {
            val chunk = chunks[i]
            sender.send(port, header + StructuredTransportCodec.encode(
                StructuredTransportHeader(1u, id.toULong(), i, chunks.size, id * 1000, chunk.size, pixels.size.toLong()),
                chunk) + byteArrayOf(13, 10))
        }
    }
    private suspend fun await(condition: suspend () -> Boolean) = withTimeout(5000) { while (!condition()) delay(5) }
    private fun freePort() = DatagramSocket(0).use { it.localPort }
}
