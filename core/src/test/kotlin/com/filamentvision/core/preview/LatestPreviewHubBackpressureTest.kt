package com.filamentvision.core.preview

import com.filamentvision.core.event.*
import com.filamentvision.core.frame.CameraId
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch

class LatestPreviewHubBackpressureTest {
    private val raw = PreviewKey("device", CameraId("A"), PreviewStage.RAW)
    private val edge = raw.copy(stage = PreviewStage.EDGE)
    private val otherCamera = raw.copy(cameraId = CameraId("B"))
    private val otherDevice = raw.copy(deviceId = "other")

    @Test fun blockedPreviewConsumerDropsOldOnlyAndCannotBlockReliablePublication() = runBlocking {
        val preview = LatestPreviewHub(setOf(raw), 4, 1)
        val reliable = SequencedEventHub(setOf("device"), 5)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val seen = mutableListOf<Long>()
        val observer = launch { preview.frames(setOf(raw)).collect {
            seen.add(it.sourceSequence)
            if (it.sourceSequence == 0L) { entered.complete(Unit); release.await() }
        } }
        awaitCondition { preview.metrics.subscribers == 1 }
        preview.publish(frame(0))
        entered.await()
        val events = domainEvents()
        repeat(9_999) {
            preview.publish(frame(it + 1L))
            if (it < events.size) reliable.publish(events[it])
            assertEquals(1, preview.metrics.retainedFrames)
            assertEquals(1, preview.metrics.pendingFrames)
        }
        assertEquals(PreviewHubMetrics(10_000, 1, 9_998, 9_999, 1, 1, 1), preview.metrics)
        val replay = reliable.replayAfter("device", 0) as EventReplay.Available
        assertEquals(events, replay.events.map { it.event })
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), replay.events.map { it.sequence })
        assertEquals(EventHubMetrics(5, 5, 5), reliable.metrics("device"))
        release.complete(Unit)
        awaitCondition { seen.size == 2 }
        assertEquals(listOf(0L, 9_999L), seen)
        assertEquals(2L, preview.metrics.deliveredFrames)
        assertEquals(0, preview.metrics.pendingFrames)
        observer.cancelAndJoin()
        assertEquals(0, preview.metrics.subscribers)
    }

    @Test fun eachCameraStageAndDeviceKeepsOwnLatestAndDeliveryIsLatestFirst() = runBlocking {
        val keys = setOf(raw, edge, otherCamera, otherDevice)
        val hub = LatestPreviewHub(keys, 4, 1)
        val seen = mutableListOf<PreviewFrame>()
        val collector = launch { hub.frames(keys).collect { seen.add(it) } }
        awaitCondition { hub.metrics.subscribers == 1 }
        hub.publish(frame(40, edge))
        hub.publish(frame(50, otherCamera))
        hub.publish(frame(60, otherDevice))
        repeat(100) { hub.publish(frame(it.toLong())) }
        assertEquals(4, hub.metrics.retainedFrames)
        assertEquals(4, hub.metrics.pendingFrames)
        awaitCondition { seen.size == 4 }
        assertEquals(listOf(99L, 60L, 50L, 40L), seen.map { it.sourceSequence })
        for ((key, sequence) in listOf(raw to 99L, edge to 40L, otherCamera to 50L, otherDevice to 60L)) {
            val latest = hub.latest(key)!!
            assertEquals(key, latest.key)
            assertEquals(sequence, latest.sourceSequence)
            assertEquals(1000L + sequence, latest.timestamp)
            assertEquals(1, latest.width)
            assertEquals(1, latest.height)
            assertEquals(PreviewFormat.ARGB32, latest.format)
        }
        collector.cancelAndJoin()
    }

    @Test fun newSubscribersGetLatestWithoutRepublishingAndFiltersAreIsolated() = runBlocking {
        val hub = LatestPreviewHub(setOf(raw, edge), 4, 2)
        assertNull(hub.latest(raw))
        hub.publish(frame(1))
        hub.publish(frame(2, edge))
        assertEquals(1L, hub.frames(setOf(raw)).first().sourceSequence)
        assertEquals(2L, hub.frames(setOf(edge)).first().sourceSequence)
        assertEquals(0, hub.metrics.subscribers)
        assertEquals(0, hub.metrics.pendingFrames)
        assertEquals(2L, hub.metrics.deliveredFrames)
    }

    @Test fun cancellationAndSubscriberFailureReleasePendingStateAndSlots() = runBlocking {
        val hub = LatestPreviewHub(setOf(raw), 4, 1)
        repeat(20) {
            val entered = CompletableDeferred<Unit>()
            val collector = launch { hub.frames(setOf(raw)).collect { entered.complete(Unit); awaitCancellation() } }
            awaitCondition { hub.metrics.subscribers == 1 }
            hub.publish(frame(it.toLong()))
            withTimeout(5_000) { entered.await() }
            hub.publish(frame(it + 100L))
            assertEquals(1, hub.metrics.pendingFrames)
            collector.cancelAndJoin()
            assertEquals(0, hub.metrics.pendingFrames)
            assertEquals(0, hub.metrics.subscribers)
        }
        val thrown = runCatching { hub.frames(setOf(raw)).collect { error("subscriber failed") } }.exceptionOrNull()
        assertEquals("subscriber failed", thrown?.message)
        assertEquals(0, hub.metrics.subscribers)
        hub.publish(frame(9999))
        assertEquals(9999L, hub.frames(setOf(raw)).first().sourceSequence)
    }

    @Test fun slowSubscriberDoesNotDelayOrReplaceFastSubscriberDelivery() = runBlocking {
        val hub = LatestPreviewHub(setOf(raw), 4, 2)
        val slow = launch { hub.frames(setOf(raw)).collect { awaitCancellation() } }
        val seen = mutableListOf<Long>()
        val fast = launch { hub.frames(setOf(raw)).collect { seen.add(it.sourceSequence) } }
        awaitCondition { hub.metrics.subscribers == 2 }
        repeat(100) {
            hub.publish(frame(it.toLong()))
            awaitCondition { seen.size == it + 1 }
            if (it == 0) awaitCondition { hub.metrics.deliveredFrames == 2L }
        }
        assertEquals((0L..99L).toList(), seen)
        assertEquals(1, hub.metrics.pendingFrames)
        assertEquals(98L, hub.metrics.droppedFrames)
        slow.cancelAndJoin()
        fast.cancelAndJoin()
        assertEquals(0, hub.metrics.subscribers)
    }

    @Test fun immutablePayloadCopiesBothInputAndOutput() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val frame = PreviewFrame(raw, 123, 456, 1, 1, PreviewFormat.ARGB32, bytes)
        bytes[0] = 9
        val returned = frame.copyPayload()
        returned[1] = 9
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), frame.copyPayload())
        assertEquals(4, frame.payloadSize)
    }

    @Test fun invalidDimensionsMetadataOrPayloadAreRejected() {
        fun invalid(width: Int = 1, height: Int = 1, timestamp: Long = 1, sequence: Long = 1, payload: ByteArray = ByteArray(4)) {
            assertThrows(IllegalArgumentException::class.java) { PreviewFrame(raw, timestamp, sequence, width, height, PreviewFormat.ARGB32, payload) }
        }
        invalid(width = 0)
        invalid(height = -1)
        invalid(width = Int.MAX_VALUE, height = Int.MAX_VALUE)
        invalid(timestamp = -1)
        invalid(sequence = -1)
        invalid(payload = ByteArray(3))
        assertThrows(IllegalArgumentException::class.java) { PreviewFrame(raw.copy(deviceId = ""), 1, 1, 1, 1, PreviewFormat.ARGB32, ByteArray(4)) }
        assertEquals(1, PreviewFrame(edge, 1, 1, 1, 1, PreviewFormat.GRAY8, byteArrayOf(42)).payloadSize)
    }

    @Test fun payloadKeyAndSubscriberLimitsCannotGrowUnboundedState() = runBlocking {
        val mutableKeys = mutableSetOf(raw)
        val hub = LatestPreviewHub(mutableKeys, 4, 1)
        mutableKeys.add(edge)
        assertThrows(IllegalArgumentException::class.java) { hub.publish(frame(1, edge)) }
        assertThrows(IllegalArgumentException::class.java) { hub.publish(PreviewFrame(raw, 1, 1, 2, 1, PreviewFormat.ARGB32, ByteArray(8))) }
        assertThrows(IllegalArgumentException::class.java) { hub.latest(edge) }
        assertNotNull(runCatching { hub.frames(setOf(edge)).first() }.exceptionOrNull())
        val collector = launch { hub.frames(setOf(raw)).collect { awaitCancellation() } }
        awaitCondition { hub.metrics.subscribers == 1 }
        assertTrue(runCatching { hub.frames(setOf(raw)).first() }.exceptionOrNull() is IllegalStateException)
        assertEquals(1, hub.metrics.subscribers)
        assertEquals(0L, hub.metrics.publishedFrames)
        collector.cancelAndJoin()
        hub.publish(frame(2))
        assertEquals(2L, hub.frames(setOf(raw)).first().sourceSequence)
    }

    @Test fun invalidHubBoundsFailBeforeAnyPublication() {
        assertThrows(IllegalArgumentException::class.java) { LatestPreviewHub(emptySet(), 4, 1) }
        assertThrows(IllegalArgumentException::class.java) { LatestPreviewHub(setOf(raw), 0, 1) }
        assertThrows(IllegalArgumentException::class.java) { LatestPreviewHub(setOf(raw), 4, 0) }
    }

    @Test fun concurrentPreviewPublishersKeepOnePendingAndWakeConsumerWithFinalPublication() = runBlocking {
        withTimeout(10_000) {
            val hub = LatestPreviewHub(setOf(raw), 4, 1)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val newest = CompletableDeferred<Long>()
            val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                hub.frames(setOf(raw)).collect {
                    if (it.sourceSequence == 0L) { entered.complete(Unit); release.await() }
                    if (it.sourceSequence == 20_000L) newest.complete(it.sourceSequence)
                }
            }
            hub.publish(frame(0))
            entered.await()
            coroutineScope {
                repeat(4) { worker -> launch(Dispatchers.Default) {
                    repeat(2_500) { hub.publish(frame(1L + worker * 2_500 + it)) }
                } }
            }
            hub.publish(frame(20_000))
            assertEquals(PreviewHubMetrics(10_002, 1, 10_000, 10_001, 1, 1, 1), hub.metrics)
            release.complete(Unit)
            assertEquals(20_000L, newest.await())
            collector.cancelAndJoin()
            assertEquals(0, hub.metrics.pendingFrames)
            assertEquals(0, hub.metrics.subscribers)
        }
    }

    @Test fun unconfinedBlockingCollectorNeverExecutesOnPublisher() = runBlocking {
        val hub = LatestPreviewHub(setOf(raw), 4, 1)
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val collector = launch(Dispatchers.Unconfined) {
            hub.frames(setOf(raw)).collect { entered.complete(Unit); release.await() }
        }
        val publication = async(Dispatchers.Default) { hub.publish(frame(1)) }
        try {
            withTimeout(5_000) { entered.await() }
            val completedWithoutSubscriber = withTimeoutOrNull(500) { publication.await(); true } ?: false
            assertTrue("publish waited for blocking Unconfined collector", completedWithoutSubscriber)
        } finally {
            release.countDown()
            publication.await()
            collector.cancelAndJoin()
        }
    }

    private suspend fun awaitCondition(condition: () -> Boolean) = withTimeout(5_000) {
        while (!condition()) delay(1)
    }

    private fun frame(sequence: Long, key: PreviewKey = raw) = PreviewFrame(key, 1000 + sequence, sequence, 1, 1, PreviewFormat.ARGB32, byteArrayOf(1, 2, 3, 4))
}
