package com.filamentvision.backend.e2e

import com.filamentvision.backend.BackendComposition
import com.filamentvision.backend.application.PageQuery
import com.filamentvision.backend.input.profile.*
import com.filamentvision.backend.input.runtime.*
import com.filamentvision.backend.input.transport.StructuredTransportCodec
import com.filamentvision.backend.input.transport.StructuredTransportHeader
import com.filamentvision.backend.input.transport.EpochClock
import com.filamentvision.core.event.DomainEvent
import com.filamentvision.core.event.EventReplay
import com.filamentvision.core.frame.*
import com.filamentvision.core.input.CameraInput
import com.filamentvision.core.runtime.MonitoringLifecycle
import java.net.DatagramSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UdpInputEndToEndTest {
    @get:Rule val temporary = TemporaryFolder()

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
        val c = open("structured")
        val source = c.inputSource as RaspberryPiUdpInputSource
        try {
            apply(c, profile(port))
            val session = c.application.start(c.deviceId, "start", null, null).session!!.id
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
            c.application.stop(c.deviceId, "stop")
            val history = c.application.history(session, PageQuery()).items
            assertEquals(3, history.size)
            assertTrue(history.all { it.measurement.evidence.single().cameraId.value == "camera" })
            assertTrue(history.all { it.measurement.legacyFusion == null })
            assertEquals(1.85, history.last().measurement.diameterMm, 1e-9)
            assertTrue(c.application.alarms(session).isNotEmpty())
            val replay = c.events.replayAfter(c.deviceId, 0) as EventReplay.Available
            assertEquals((1L..replay.latestSequence).toList(), replay.events.map { it.sequence })
            val finalAt = replay.events.indexOfFirst { it.event is DomainEvent.SessionFinalized }
            assertTrue(finalAt > replay.events.indexOfLast { it.event is DomainEvent.Measurement })
            assertTrue(finalAt > replay.events.indexOfLast { it.event is DomainEvent.Alarm })
            slowPreview.cancelAndJoin()
            assertEquals(0 to 0, c.runtime.queuedCounts())
        } finally { c.close() }
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
            UdpTestSender().use { sender ->
                sender.send(port, byteArrayOf(1))
                await { source.udpDiagnostics().sourceFilteredDatagrams == 1L }
                apply(c, profile(port))
                sender.send(port, ByteArray(38 + 128 + 1))
                await { source.udpDiagnostics().oversizedOrTruncatedDatagrams == 1L }
            }
            assertEquals(0L, source.udpDiagnostics().framesCompleted)
            assertNull(c.application.latest(c.deviceId))
            assertEquals(0L, c.previews.metrics.publishedFrames)
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
                StructuredTransportHeader(UdpCameraId.A, id.toULong(), i, chunks.size, id * 1000, chunk.size, pixels.size.toLong()),
                chunk) + byteArrayOf(13, 10))
        }
    }
    private suspend fun await(condition: suspend () -> Boolean) = withTimeout(5000) { while (!condition()) delay(5) }
    private fun freePort() = DatagramSocket(0).use { it.localPort }
}
