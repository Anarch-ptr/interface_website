package com.filamentvision.backend.e2e

import com.filamentvision.backend.BackendComposition
import com.filamentvision.backend.application.PageQuery
import com.filamentvision.backend.simulator.fixtures.*
import com.filamentvision.backend.input.profile.*
import com.filamentvision.backend.input.network.*
import com.filamentvision.backend.input.runtime.*
import com.filamentvision.backend.input.decoder.*
import com.filamentvision.backend.input.reassembly.*
import com.filamentvision.core.config.VisionConfiguration
import com.filamentvision.core.frame.CameraFrame
import com.filamentvision.core.vision.*
import com.filamentvision.backend.preview.PngPreviewEncoder
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.imageio.ImageIO
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import com.filamentvision.core.input.CameraInput
import com.filamentvision.core.metrics.MetricCounter
import com.filamentvision.core.repository.HistoryCursor
import java.net.DatagramSocket
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.time.Duration.Companion.minutes

@OptIn(ExperimentalCoroutinesApi::class)
class LongRunBoundednessTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun eightMegapixelRgbAndJpegOverlapHasExplicitBoundAndReleasesOnRestart() = runBlocking {
        for (jpeg in listOf(false, true)) {
            val width = 3840; val height = 2160
            val payload = if (jpeg) ByteArrayOutputStream().use { output ->
                val raster = BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR)
                for (y in 0 until height) for (x in 0 until width)
                    raster.setRGB(x, y, if (x in 1150 until 1270) 0xff141414.toInt() else 0xffc8c8c8.toInt())
                check(ImageIO.write(raster, "jpeg", output)); output.toByteArray()
            } else ByteArray(24_883_200) { if ((it / 3) % width in 1150 until 1270) 20 else 200.toByte() }
            val encoded = AtomicReference<ByteArray?>()
            val decoderPixels = AtomicReference<IntArray?>()
            val compiled = AtomicReference<CompiledRaspberryPiInputProfile>()
            val atVision = CompletableDeferred<Long>()
            val resume = CountDownLatch(1)
            val c = BackendComposition.open(temporary.root.toPath().resolve("8mp-$jpeg.db"),
                temporary.root.toPath().resolve("8mp-$jpeg-objects"),
                inputSourceFactory = BackendInputSourceFactory { _, _, _, _, profile ->
                    RaspberryPiUdpInputSource(profile, compiledFactory = { p, observer ->
                        val real = CompiledRaspberryPiInputProfile.compile(p, observer)
                        CompiledRaspberryPiInputProfile(real.profile, real.envelopeParser, real.transportParser,
                            real.reassembler, PayloadDecoder { bytes ->
                                // Stress-only retention extends real decoder scratch through the Vision gate.
                                // This deliberately overestimates production lifetime; no replacement decoder.
                                encoded.set(bytes)
                                real.decoder.decode(bytes).also { result ->
                                    if (result is PayloadDecodeResult.Success)
                                        decoderPixels.set((result.value as DecodedInput.Image).image.argb)
                                }
                            }).also(compiled::set)
                    })
                })
            val source = c.inputSource as RaspberryPiUdpInputSource
            val processorField = c.runtime.javaClass.getDeclaredField("processor").also { it.isAccessible = true }
            val original = processorField.get(c.runtime)
            processorField.set(c.runtime, object : VisionAnalyzer {
                override fun process(frame: CameraFrame, configuration: VisionConfiguration, decodeNanos: Long): VisionResult {
                    val allocations = mutableListOf<Int>()
                    val result = RoiVisionProcessor(allocationObserver = allocations::add).process(frame, configuration, decodeNanos)
                    assertEquals(listOf(307200, 307200, 307200, 307200), allocations)
                    val roi = listOf(result.rawStage.pixels.count, result.grayscaleStage.intensities.count,
                        result.thresholdStage.intensities.count, result.edgeStage.intensities.count).sum().toLong() * 4
                    assertEquals(4_915_200L, roi)
                    assertEquals(33_177_600, frame.pixels.count * 4)
                    atVision.complete(roi)
                    check(resume.await(20, TimeUnit.SECONDS)) { "8MP Vision overlap gate timed out" }
                    return result
                }
            })
            val port = DatagramSocket(0).use { it.localPort }
            try {
                val baseline = BackendComposition.defaultConfiguration()
                val calibration = baseline.vision.calibration.copy(roiLeft = 1000, roiTop = 700,
                    roiRight = 1640, roiBottom = 1180, maximumPixelWidth = 300, mmPerPixel = .01)
                c.application.saveAndApply(c.deviceId, "8mp-config", baseline.copy(vision = VisionConfiguration(calibration)))
                val udpDefaults = RaspberryPiUdpInputProfile.defaults()
                val profile = udpDefaults.copy(enabled = true, network = udpDefaults.network.copy(
                    destination = AdvertisedDestinationProfile.Manual("127.0.0.1"), listenPort = port),
                    payload = if (jpeg) ImagePayloadProfile.Jpeg(16 * 1024 * 1024, width, height)
                        else ImagePayloadProfile.Rgb888(width, height, RgbChannelOrder.RGB),
                    transportLimits = TransportLimits(2, 32 * 1024 * 1024, 10000, 32768, 60000))
                val service = checkNotNull(c.udpInputService)
                val saved = service.save(1, profile); service.apply(saved.revision)
                c.application.start(c.deviceId, "start", null, null)
                val lease = source.packetInspector.createLease(256)
                UdpTestSender().use { sender ->
                    suspend fun chunk(id: ULong, bytes: ByteArray, index: Int) {
                        val received = source.udpDiagnostics().datagramsReceived
                        sender.sendStructured(port, id, bytes, 60000, listOf(index))
                        withTimeout(5000) { while (source.udpDiagnostics().datagramsReceived != received + 1) delay(1) }
                        // Observe the reliable processing path, not the deliberately lossy Inspector tap.
                        // A malformed first envelope must still fail before the complete 8MP decode.
                        if (id == 1uL && index == 0) withTimeout(5000) {
                            while (source.udpDiagnostics().framesStarted == 0L &&
                                source.udpDiagnostics().invalidHeaderCount == 0L) delay(1)
                        }
                        assertEquals(0L, source.udpDiagnostics().invalidHeaderCount)
                    }
                    for (index in 0 until (payload.size + 59999) / 60000) chunk(1u, payload, index)
                    val roi = withTimeout(15000) { atVision.await() }
                    assertEquals(payload.size, encoded.get()!!.size)
                    assertEquals(8_294_400, decoderPixels.get()!!.size)
                    // While the first decoded lease is held, two new incomplete frames coexist.
                    val incomplete = ByteArray(120000)
                    chunk(2u, incomplete, 0); chunk(3u, incomplete, 0)
                    withTimeout(5000) { while (compiled.get().reassembler.bufferedBytes != 120000L) delay(1) }
                    assertEquals(2, compiled.get().reassembler.inFlightFrameCount)
                    assertEquals(0, source.pendingInputCount) // Runtime owns this image, not a duplicate mailbox image.
                    val preview = c.previews.latest(c.previewKeys.single())!!
                    assertEquals(3_686_400, preview.payloadSize)
                    val png = PngPreviewEncoder().encode(preview)
                    assertTrue(png.bytes.size <= 4 * 1024 * 1024)
                    assertTrue(source.packetInspector.retainedPreviewBytes <= 512)
                    val retained = 120000L + encoded.get()!!.size + 2L * 33_177_600 + roi +
                        preview.payloadSize + png.bytes.size + source.packetInspector.retainedPreviewBytes
                    // Conservative array-payload ceiling also reserves the full reassembly/encoded caps,
                    // both decoded ownership/scratch arrays, sampler/PNG copies/raster, and PNG output growth.
                    val ceiling = 67_108_864L + 33_554_432 + 66_355_200 + 4_915_200 +
                        5L * 3_686_400 + 3L * 4_194_304 + 60_039 + 512
                    assertTrue(retained <= ceiling)
                    assertTrue(2L * 33_177_600 <= 67_108_864)
                    assertEquals(1, source.activeProducerCount); assertEquals(1, source.activeReceiverCount)
                    println("UDP_8MP jpeg=$jpeg encoded=${payload.size} reassembly=120000 decodedAndAdapter=66355200 roi=$roi preview=3686400 png=${png.bytes.size} inspector=${source.packetInspector.retainedPreviewBytes} mailbox=0 retained=$retained conservativeArrayCeiling=$ceiling producers=1 receivers=1")
                    resume.countDown()
                    withTimeout(5000) { while (c.runtime.state.value.statistics.sampleCount != 1L) delay(1) }
                    assertEquals(1.2, c.application.latest(c.deviceId)!!.measurement.diameterMm, 1e-9)
                    encoded.set(null); decoderPixels.set(null)
                    c.application.stop(c.deviceId, "stop")
                    source.packetInspector.releaseLease(lease.id)
                    assertEquals(0, source.pendingInputCount); assertEquals(0, source.activeReceiverCount)
                    assertEquals(0, source.packetInspector.retainedPreviewBytes)
                    assertNull(source.javaClass.getDeclaredField("processor").also { it.isAccessible = true }.get(source))
                    val permit = source.javaClass.getDeclaredField("decodedPermit").also { it.isAccessible = true }
                        .get(source) as DecodedFramePermit
                    val releasedPermit = permit.tryAcquire()
                    assertNotNull("Stop completed but the decoded lease is still retained", releasedPermit)
                    releasedPermit!!.close()
                    compiled.set(null) // Drop the observer's test-only reference to the discarded old reassembler.
                    assertTrue(c.previews.metrics.retainedFrames <= 1)
                    // A source restart must discard incomplete reassembly and allow another decoded lease.
                    processorField.set(c.runtime, original)
                    c.application.start(c.deviceId, "restart", null, null)
                    assertEquals(0L, compiled.get().reassembler.bufferedBytes)
                    assertEquals(0, compiled.get().reassembler.inFlightFrameCount)
                    val small = service.save(saved.revision, profile.copy(payload = ImagePayloadProfile.Gray8(48, 8)))
                    service.apply(small.revision)
                    val smallPayload = ByteArray(384) { if (it % 48 in 6 until 41) 20 else 200.toByte() }
                    sender.sendStructured(port, 4u, smallPayload, 128)
                    withTimeout(5000) { while (source.udpDiagnostics().framesCompleted != 2L) delay(1) }
                    assertEquals(0L, source.udpDiagnostics().processingBackpressureDrops)
                }
            } finally {
                resume.countDown(); c.close(); encoded.set(null); decoderPixels.set(null)
            }
            assertEquals(0, c.previews.metrics.retainedFrames)
            assertEquals(0, source.pendingInputCount)
            assertEquals(0, source.activeProducerCount)
        }
    }

    @Test fun twoIncompleteMaximumDeclarationsCannotRetainMoreThan64MiB() {
        val defaults = RaspberryPiUdpInputProfile.defaults()
        val compiled = CompiledRaspberryPiInputProfile.compile(defaults.copy(
            transportLimits = defaults.transportLimits.copy(maxChunkPayloadBytes = 60000)))
        val reassembler = compiled.reassembler
        val source = InetSocketAddress("127.0.0.1", 12345)
        val frameBytes = 33_554_432
        // Use the real reassembly boundary, bypassing only the socket to exercise the exact hard byte edge.
        fun input(id: Long, index: Int, bytes: ByteArray) = StructuredChunkInput(UdpCameraId.A, source,
            id, index, 561, frameBytes.toLong(), id, bytes, receivedAtMonotonicNanos = 0, receivedAtEpochMillis = 0)
        val chunk = ByteArray(60000)
        for (id in 1L..2L) {
            for (index in 0 until 559) assertEquals(FrameReassemblyResult.Pending, reassembler.accept(input(id, index, chunk)))
            reassembler.accept(input(id, 559, ByteArray(14431))) // Exactly one declared byte is missing.
        }
        assertEquals(2, reassembler.inFlightFrameCount)
        assertEquals(67_108_862L, reassembler.bufferedBytes)
        reassembler.accept(input(3, 0, byteArrayOf(1)))
        assertEquals(2, reassembler.inFlightFrameCount)
        assertEquals(33_554_432L, reassembler.bufferedBytes)
        assertEquals(1L, reassembler.inFlightEvictions)
        reassembler.expire(1_000_000_001)
        assertEquals(0L, reassembler.bufferedBytes)
        assertEquals(0, reassembler.inFlightFrameCount)
        assertEquals(33_177_600, DecodedAllocationPolicy.validateImage(3840, 2160).decodedBytes)
        assertEquals(67_108_864, DecodedAllocationPolicy.validateImage(8192, 2048).decodedBytes)
        assertThrows(DecodeValidationException::class.java) { DecodedAllocationPolicy.validateImage(8192, 2049) }
        assertThrows(DecodeValidationException::class.java) { DecodedAllocationPolicy.validateImage(Int.MAX_VALUE, 2160) }
    }

    @Test fun realSocketMailboxRetainsOneDecodedLeaseAndReleasesItOnStopAndRestart() = runBlocking {
        val port = DatagramSocket(0).use { it.localPort }
        val source = RaspberryPiUdpInputSource(RaspberryPiUdpInputProfile.defaults().copy(enabled = true,
            listenPort = port, payload = ImagePayloadProfile.Gray8(48, 8)))
        val bytes = ByteArray(384) { if (it % 48 in 6 until 41) 20 else 200.toByte() }
        try {
            source.start()
            UdpTestSender().use { sender ->
                sender.sendStructured(port, 1u, bytes, 128)
                withTimeout(5000) { while (source.pendingInputCount != 1) delay(1) }
                sender.sendStructured(port, 2u, bytes, 128)
                withTimeout(5000) { while (source.udpDiagnostics().processingBackpressureDrops != 1L) delay(1) }
                assertEquals(1, source.pendingInputCount)
                assertEquals(1L, source.udpDiagnostics().framesCompleted)
                source.stop()
                assertEquals(0, source.pendingInputCount)
                source.start()
                sender.sendStructured(port, 3u, bytes, 128)
                val input = withTimeout(5000) { source.inputs.first() } as CameraInput.Image
                assertEquals(384, input.frame.pixels.count)
                assertEquals(0, source.pendingInputCount)
                input.frame.close()
                assertEquals(2L, source.udpDiagnostics().framesCompleted)
                assertEquals(1, source.activeReceiverCount)
            }
        } finally { source.stop(); source.packetInspector.close() }
        assertEquals(0, source.activeProducerCount)
        assertEquals(0, source.activeReceiverCount)
    }
    @Test fun eightHoursAtEightHzRetainFixedMemoryAndPersistEveryAcceptedSample() = runTest(timeout = 20.minutes) {
        val d = StandardTestDispatcher(testScheduler)
        val c = BackendComposition.open(temporary.root.toPath().resolve("eight-hours.db"), temporary.root.toPath().resolve("objects"),
            SimulatorBackendInputSourceFactory, d, d, { 1000 + testScheduler.currentTime }, { testScheduler.currentTime })
        try {
            // Supported batch configuration reduces physical fsyncs, never frame processing or rate.
            c.application.saveAndApply("filamentvision-1", "long-config", BackendComposition.defaultConfiguration().copy(writeBatchSize = 256, flushIntervalMillis = 16000))
            val session = c.application.start("filamentvision-1", "start", null, null).session!!.id
            runCurrent()
            var maxQueued = 0; var maxAlarms = 0
            repeat(230399) { tick ->
                if (tick % 1000 == 0) {
                    c.source.setScenario(if (tick % 2000 == 0) SimulationScenario.HIGH_DIAMETER else SimulationScenario.NORMAL)
                    c.application.start("filamentvision-1", "observe-$tick", null, null)
                    c.application.save("filamentvision-1", "save-$tick", BackendComposition.defaultConfiguration())
                }
                advanceTimeBy(125); runCurrent()
                val queued = c.runtime.queuedCounts()
                maxQueued = maxOf(maxQueued, queued.first); maxAlarms = maxOf(maxAlarms, queued.second)
                assertTrue(queued.first <= 256); assertTrue(queued.second <= 256)
                if (tick % 1000 == 0) {
                    assertTrue(c.runtime.realtimeMeasurements().size <= 300)
                    assertTrue(c.events.metrics("filamentvision-1").retained <= 256)
                    assertEquals(1, c.previews.metrics.retainedFrames)
                    assertTrue(c.previews.metrics.pendingFrames <= 32)
                    assertTrue(c.snapshotCache.state().entries <= 1)
                    assertTrue(c.snapshotCache.state().totalBytes <= 1024 * 1024)
                    assertTrue(c.runtime.state.value.cachedCommandCount <= 128)
                    assertTrue(c.cachedFacadeCommands <= 128)
                }
            }
            c.application.stop("filamentvision-1", "stop")
            val accepted = c.runtime.state.value.statistics.sampleCount
            assertEquals(230400L, accepted)
            assertEquals(230400L, c.metrics.snapshot().cameras.values.sumOf { it.processed.count })
            var cursor: HistoryCursor? = null; var persisted = 0L
            do {
                val page = c.application.history(session, PageQuery(after = cursor, limit = 500))
                persisted += page.items.size; cursor = page.nextCursor
            } while (cursor != null)
            assertEquals(accepted, persisted)
            assertEquals(230400L, c.metrics.snapshot().counters.getValue(MetricCounter.DATABASE_ROW).count)
            assertEquals(accepted, c.application.session(session).finalization!!.statistics.sampleCount)
            assertEquals(0 to 0, c.runtime.queuedCounts())
            assertEquals(1, c.source.maximumActiveProducerCount)
            assertEquals(1L, c.source.totalProducerStarts)
            assertEquals(0, c.source.activeProducerCount)
            println("LONG_RUN ticks=230400 frames=230400 accepted=$accepted persisted=$persisted virtualMillis=${testScheduler.currentTime + 125} maxQueued=$maxQueued maxAlarms=$maxAlarms realtime=${c.runtime.realtimeMeasurements().size} replay=${c.events.metrics("filamentvision-1").retained} previews=${c.previews.metrics.retainedFrames} commands=${c.runtime.state.value.cachedCommandCount}/${c.cachedFacadeCommands} dbBatches=${c.metrics.snapshot().counters.getValue(MetricCounter.DATABASE_BATCH).count}")
        } finally { c.close() }
    }

    @Test fun repeatedUdpApplyStartStopKeepsOneReceiverAndMailboxBounded() = runBlocking {
        val c = BackendComposition.open(temporary.root.toPath().resolve("udp-cycles.db"),
            temporary.root.toPath().resolve("udp-cycle-objects"))
        val source = c.inputSource as RaspberryPiUdpInputSource
        val service = checkNotNull(c.udpInputService)
        try {
            val udpDefaults = RaspberryPiUdpInputProfile.defaults()
            var profile = udpDefaults.copy(enabled = true, network = udpDefaults.network.copy(
                destination = AdvertisedDestinationProfile.Manual("127.0.0.1"),
                listenPort = DatagramSocket(0).use { it.localPort }))
            var saved = service.save(1, profile)
            service.apply(saved.revision)
            repeat(16) { cycle ->
                c.application.start(c.deviceId, "udp-start-$cycle", null, null)
                assertEquals(1, source.activeProducerCount)
                assertEquals(1, source.activeReceiverCount)
                profile = profile.copy(envelope = DatagramEnvelopeProfile(
                    if (cycle % 2 == 0) "AA 01" else "BB 02", "0D 0A"))
                saved = service.save(saved.revision, profile)
                assertEquals(RaspberryPiUdpApplyOutcome.APPLIED, service.apply(saved.revision).outcome)
                c.application.stop(c.deviceId, "udp-stop-$cycle")
                assertEquals(0, source.activeProducerCount)
                assertEquals(0, source.activeReceiverCount)
                assertEquals(0, source.pendingInputCount)
                assertEquals(0, c.previews.metrics.retainedFrames)
                assertTrue(c.runtime.state.value.cachedCommandCount <= 128)
                assertTrue(c.cachedFacadeCommands <= 128)
            }
            assertEquals(16L, source.totalProducerStarts)
            assertEquals(1, c.runtime.state.value.maximumActiveProducerCount)
        } finally { c.close() }
    }

    @Test fun flexibleNetworkPreviewReapplyAndFailuresRemainBounded() = runBlocking {
        val lookupAnswers = AtomicReference(listOf("127.0.0.1"))
        val resolver = HostResolver(HostLookup {
            Thread.sleep(10)
            lookupAnswers.get()
        }, timeoutMillis = 1_000)
        val planner = EffectiveUdpNetworkPlanner(Ipv4NetworkInterfaceProvider { emptyList() }, Ipv4RouteProbe { null })
        val previewService = NetworkResolutionPreviewService(resolver, planner, minimumStartIntervalMillis = 0)
        val port = DatagramSocket(0).use { it.localPort }
        val defaults = RaspberryPiUdpInputProfile.defaults()
        val profile = defaults.copy(enabled = true, network = defaults.network.copy(
            destination = AdvertisedDestinationProfile.Manual("backend.local"), listenPort = port),
            payload = ImagePayloadProfile.Gray8(48, 8),
            transportLimits = TransportLimits(2, 1024, 500, 16, 128))
        try {
            coroutineScope {
                (1..100).map {
                    async {
                        previewService.preview("filamentvision-1",
                            PreviewProfile(true, profile.network, 1, 1), null)
                    }
                }.awaitAll()
            }
            val resolverPreviewBounds = resolver.bounds()
            val previewBounds = previewService.bounds()
            assertTrue(resolverPreviewBounds.maximumActiveLookups <= 2)
            assertTrue(resolverPreviewBounds.maximumQueuedLookups <= 2)
            assertEquals(0, resolverPreviewBounds.retainedResults)
            assertTrue(previewBounds.maximumActive <= 1)
            assertTrue(previewBounds.maximumQueued <= 1)
            assertEquals(1, previewBounds.retainedResults)

            val c = BackendComposition.open(temporary.root.toPath().resolve("flexible-bounds.db"),
                temporary.root.toPath().resolve("flexible-bounds-objects"))
            val source = c.inputSource as RaspberryPiUdpInputSource
            val service = RaspberryPiUdpInputConfigurationService(c.udpProfileRepository,
                checkNotNull(c.udpInputRouter), resolver, planner)
            try {
                var saved = service.save(c.udpProfileRepository.state().saved.revision, profile)
                assertEquals(RaspberryPiUdpApplyOutcome.APPLIED,
                    service.apply(saved.revision, forceReapply = true).outcome)
                c.application.start(c.deviceId, "bounded-start", null, null)

                val restarted = profile.copy(network = profile.network.copy(
                    listenPort = DatagramSocket(0).use { it.localPort }))
                saved = service.save(saved.revision, restarted)
                assertEquals(RaspberryPiUdpApplyOutcome.APPLIED, service.apply(saved.revision).outcome)
                repeat(25) {
                    val state = c.udpProfileRepository.state()
                    assertEquals(RaspberryPiUdpApplyOutcome.APPLIED,
                        service.apply(saved.revision, forceReapply = true,
                            expectedAppliedRevision = state.applied.revision,
                            expectedResolutionGeneration = state.resolutionGeneration).outcome)
                }
                repeat(50) {
                    val state = c.udpProfileRepository.state()
                    assertEquals(RaspberryPiUdpApplyOutcome.CONFLICT,
                        service.apply(saved.revision, forceReapply = true,
                            expectedAppliedRevision = state.applied.revision,
                            expectedResolutionGeneration = state.resolutionGeneration - 1).outcome)
                }

                val beforeResolutionFailure = c.udpProfileRepository.state()
                lookupAnswers.set(emptyList())
                val resolutionFailure = service.apply(saved.revision, forceReapply = true,
                    expectedAppliedRevision = beforeResolutionFailure.applied.revision,
                    expectedResolutionGeneration = beforeResolutionFailure.resolutionGeneration)
                assertEquals(RaspberryPiUdpApplyOutcome.INVALID, resolutionFailure.outcome)
                assertEquals(beforeResolutionFailure, c.udpProfileRepository.state())
                lookupAnswers.set(listOf("127.0.0.1"))

                DatagramSocket(0).use { occupied ->
                    val failed = restarted.copy(network = restarted.network.copy(listenPort = occupied.localPort))
                    val failedSaved = service.save(saved.revision, failed)
                    assertTrue(runCatching { service.apply(failedSaved.revision) }.isFailure)
                }
                val sourceBounds = source.bounds()
                assertTrue(sourceBounds.maximumActiveProducers <= 1)
                assertTrue(sourceBounds.maximumCommittedReceivers <= 1)
                assertTrue(sourceBounds.maximumProvisionalReceivers <= 1)
                assertEquals(1, sourceBounds.committedReceivers)
                assertEquals(0, sourceBounds.provisionalReceivers)
                assertTrue(sourceBounds.mailboxEntries <= 1)
                assertTrue(source.packetInspector.retainedPreviewBytes <= 512)
                println("FLEX_NETWORK_BOUNDS resolverActive=${resolverPreviewBounds.maximumActiveLookups} " +
                    "resolverQueued=${resolverPreviewBounds.maximumQueuedLookups} previewActive=${previewBounds.maximumActive} " +
                    "previewQueued=${previewBounds.maximumQueued} previewRetained=${previewBounds.retainedResults} " +
                    "committed=${sourceBounds.maximumCommittedReceivers} provisional=${sourceBounds.maximumProvisionalReceivers} " +
                    "producer=${sourceBounds.maximumActiveProducers} mailbox=${sourceBounds.mailboxEntries}")
                c.application.stop(c.deviceId, "bounded-stop")
                assertEquals(0, source.activeProducerCount)
                assertEquals(0, source.activeReceiverCount)
            } finally { c.close() }
        } finally {
            previewService.close()
            resolver.close()
        }
    }
}
