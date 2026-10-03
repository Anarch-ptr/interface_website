package com.filamentvision.core.measurement

import com.filamentvision.core.config.*
import com.filamentvision.core.frame.*
import com.filamentvision.core.preview.*
import com.filamentvision.core.runtime.*
import com.filamentvision.core.vision.VisionProcessor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** Preserved algorithm/ownership behavior; dual identities are baseline migration targets. */
@OptIn(ExperimentalCoroutinesApi::class)
class SingleCameraBaselineCharacterizationTest {
    @Test fun fixedDarkStripPreservesVisionDiameterAndConfidence() {
        val (frame, configuration) = darkStripFixture()
        try {
            val result = VisionProcessor().process(frame, configuration)
            val measurement = requireNotNull(result.measurement)
            assertNull(result.error)
            assertEquals(4.0, requireNotNull(measurement.pixelWidth), 0.0)
            assertEquals(0.4, measurement.diameterMm, 0.0)
            assertEquals(1.0, measurement.confidence, 0.0)
            assertEquals(2, measurement.leftEdge)
            assertEquals(6, measurement.rightEdge)
            assertEquals(99L, measurement.timestamp)
            assertEquals(3L, measurement.sequence)
        } finally { frame.close() }
    }

    @Test fun concurrentRuntimeStartsNeverExceedOneProducerAcrossRestart() = runTest {
        val h = runtimeFixture(backgroundScope)
        try {
            val producerCounts = mutableListOf<Int>()
            val observer = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                h.runtime.state.collect { producerCounts += it.activeProducerCount }
            }
            repeat(2) { generation ->
                val gate = CompletableDeferred<Unit>()
                val calls = List(50) { index -> async(Dispatchers.Default) {
                    gate.await()
                    h.runtime.start(StartMonitoringCommand("start-$generation-$index"))
                } }
                gate.complete(Unit)
                val results = calls.awaitAll()
                assertEquals(1, results.map { it.session }.distinct().size)
                assertEquals(1, h.runtime.state.value.activeProducerCount)
                h.runtime.stop(StopMonitoringCommand("stop-$generation"))
                assertEquals(0, h.runtime.state.value.activeProducerCount)
                assertEquals(0, h.source.activeProducerCount)
            }
            assertEquals(1, producerCounts.maxOrNull())
            assertTrue(producerCounts.all { it in 0..1 })
            assertEquals(1, h.source.maximum.get())
            assertEquals(2, h.sink.created.size)
            assertEquals(2, h.sink.finalized.size)
            observer.cancelAndJoin()
        } finally { h.runtime.close() }
    }

    @Test fun blockedPreviewObserverKeepsOnlyNewestPendingFrame() = runTest {
        val key = previewKeyFixture()
        val hub = LatestPreviewHub(setOf(key), maxPayloadBytes = 4, maxSubscribers = 1)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val newestDelivered = CompletableDeferred<Unit>()
        val seen = mutableListOf<Long>()
        val observer = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            hub.frames(setOf(key)).collect { frame ->
                seen += frame.sourceSequence
                if (frame.sourceSequence == 0L) { entered.complete(Unit); release.await() }
                if (frame.sourceSequence == 100L) newestDelivered.complete(Unit)
            }
        }
        fun publish(sequence: Long) = hub.publish(PreviewFrame(
            key, 1_000 + sequence, sequence, 1, 1, PreviewFormat.ARGB32, byteArrayOf(1, 2, 3, 4),
        ))
        publish(0)
        entered.await()
        repeat(100) { publish(it + 1L) }
        assertEquals(1, hub.metrics.retainedFrames)
        assertEquals(1, hub.metrics.pendingFrames)
        assertEquals(99L, hub.metrics.droppedFrames)
        assertEquals(100L, requireNotNull(hub.latest(key)).sourceSequence)
        release.complete(Unit)
        newestDelivered.await()
        assertEquals(listOf(0L, 100L), seen)
        observer.cancelAndJoin()
        assertEquals(0, hub.metrics.pendingFrames)
        assertEquals(0, hub.metrics.subscribers)
    }

    private fun darkStripFixture(): Pair<CameraFrame, VisionConfiguration> {
        val pixels = IntArray(32) { index ->
            val intensity = if (index % 8 in 2..5) 20 else 100
            0xff000000.toInt() or (intensity shl 16) or (intensity shl 8) or intensity
        }
        return CameraFrame(CameraId("A"), 99, 100, 8, 4, PixelFormat.ARGB32,
            PixelBuffer.ints(pixels), 0, false, 3) to VisionConfiguration(CalibrationProfile(
            roiRight = 8, roiBottom = 4, grayscaleMethod = GrayscaleMethod.AVERAGE,
            manualThreshold = 40, edgeSensitivity = 30, minimumPixelWidth = 3,
            maximumPixelWidth = 6, mmPerPixel = 0.1, minimumConfidence = 0.5,
        ))
    }
    private fun runtimeFixture(scope: CoroutineScope) = Harness(scope)
    private fun previewKeyFixture() = PreviewKey("device", CameraId("A"), PreviewStage.RAW)
}
