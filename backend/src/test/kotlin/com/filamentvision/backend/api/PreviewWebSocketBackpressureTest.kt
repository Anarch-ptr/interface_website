package com.filamentvision.backend.api

import com.filamentvision.backend.preview.*
import com.filamentvision.core.event.SequencedEventHub
import com.filamentvision.core.frame.CameraId
import com.filamentvision.core.preview.*
import io.ktor.client.plugins.websocket.*
import io.ktor.server.testing.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

class PreviewWebSocketBackpressureTest {
    @Test fun suspendedClientSendBoundsPendingAndRateWaitSelectsNewestWithInjectedClock() = runBlocking {
        val key = previewKey(); val hub = LatestPreviewHub(setOf(key), 100, 2)
        var now = 0L
        val sent = Channel<Long>(4)
        val sending = CompletableDeferred<Unit>(); val releaseSend = CompletableDeferred<Unit>()
        val rateWait = CompletableDeferred<Long>(); val releaseRate = CompletableDeferred<Unit>()
        val stream = launch {
            streamPreviews(hub, key, PngPreviewEncoder(), 5, nanos = { now }, pause = { millis ->
                rateWait.complete(millis); releaseRate.await(); now += millis * 1_000_000
            }) { metadata, bytes ->
                assertNotNull(ImageIO.read(ByteArrayInputStream(bytes)))
                if (metadata.sourceSequence == 1L) { sending.complete(Unit); releaseSend.await() }
                sent.send(metadata.sourceSequence)
            }
        }
        try {
            hub.publish(previewFrame(key, 1)); withTimeout(3000) { sending.await() }
            repeat(1000) { hub.publish(previewFrame(key, it + 2L)) }
            assertEquals(1, hub.metrics.pendingFrames); assertEquals(999L, hub.metrics.droppedFrames)
            releaseSend.complete(Unit)
            assertEquals(1L, withTimeout(3000) { sent.receive() })
            assertEquals(200L, withTimeout(3000) { rateWait.await() })
            hub.publish(previewFrame(key, 1002)) // replaces the previously selected frame during rate wait
            releaseRate.complete(Unit)
            assertEquals(1002L, withTimeout(3000) { sent.receive() })
            assertEquals(200_000_000L, now)
        } finally { stream.cancelAndJoin() }
        assertEquals(0, hub.metrics.subscribers); assertEquals(0, hub.metrics.pendingFrames)
    }

    @Test fun encodingFailureAndUnsupportedMediaCloseOnlyPreviewWithNoSecretLeak() = testApplication {
        val key = previewKey(); val hub = LatestPreviewHub(setOf(key), 100, 2)
        val events = SequencedEventHub(setOf("device"), 10)
        var failEncoding = true
        val encoder = PreviewEncoder {
            if (failEncoding) error("secret path") else EncodedPreview(byteArrayOf(1), "text/html")
        }
        application { installEventWebSocket(eventBackend(events), events, setOf("device")); installPreviewWebSocket(hub, setOf(key), encoder) }
        val ws = createClient { install(WebSockets) }
        ws.webSocket("/api/v1/events") {
            nextJson()
            repeat(2) {
                ws.webSocket("/api/v1/previews?cameraId=A&stage=RAW") {
                    hub.publish(previewFrame(key, it + 1L))
                    val failure = nextJson()
                    assertEquals("STREAM_UNAVAILABLE", failure["code"].asText()); assertFalse(failure.toString().contains("secret"))
                    assertEquals(1011.toShort(), withTimeout(3000) { closeReason.await() }!!.code)
                }
                events.publish(error()); assertEquals(it + 1L, nextJson()["sequence"].asLong())
                failEncoding = false
            }
            close()
        }
        withTimeout(3000) { while (hub.metrics.subscribers != 0) delay(5) }
    }

    @Test fun disconnectCancelsBlockedEncoderAndReleasesPendingFrame() = testApplication {
        val key = previewKey(); val hub = LatestPreviewHub(setOf(key), 100, 2)
        val entered = CompletableDeferred<Unit>(); val cancelled = CompletableDeferred<Unit>()
        val encoder = PreviewEncoder {
            try { entered.complete(Unit); awaitCancellation() }
            finally { cancelled.complete(Unit) }
        }
        application { installPreviewWebSocket(hub, setOf(key), encoder) }
        val ws = createClient { install(WebSockets) }
        ws.webSocket("/api/v1/previews?cameraId=A&stage=RAW") {
            hub.publish(previewFrame(key, 1)); withTimeout(3000) { entered.await() }
            hub.publish(previewFrame(key, 2)); assertEquals(1, hub.metrics.pendingFrames)
            close()
        }
        withTimeout(3000) { cancelled.await(); while (hub.metrics.subscribers != 0) delay(5) }
        assertEquals(0, hub.metrics.pendingFrames)
    }

    @Test fun grayscaleEncoderPreservesIntensity() = runBlocking {
        val frame = PreviewFrame(previewKey("A", PreviewStage.GRAYSCALE), 0, 0, 2, 1, PreviewFormat.GRAY8, byteArrayOf(0, -1))
        val encoded = PngPreviewEncoder().encode(frame)
        val image = ImageIO.read(ByteArrayInputStream(encoded.bytes))
        assertEquals(0xff000000.toInt(), image.getRGB(0, 0)); assertEquals(0xffffffff.toInt(), image.getRGB(1, 0))
    }

    @Test fun thousandFramesWhilePreviewIsBlockedKeepNewestAndAllReliableEvents() = testApplication {
        val key = previewKey()
        val hub = LatestPreviewHub(setOf(key), 100, 4)
        val events = SequencedEventHub(setOf("device"), 1100)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val png = PngPreviewEncoder()
        val encoder = PreviewEncoder { frame ->
            if (frame.sourceSequence == 1L) { entered.complete(Unit); release.await() }
            png.encode(frame)
        }
        application {
            installEventWebSocket(eventBackend(events), events, setOf("device"))
            installPreviewWebSocket(hub, setOf(key), encoder)
        }
        val ws = createClient { install(WebSockets) }
        ws.webSocket("/api/v1/events") {
            val eventSocket = this
            assertEquals("state.snapshot", nextJson()["type"].asText())
            ws.webSocket("/api/v1/previews?cameraId=A&stage=RAW&maxFps=30") {
                hub.publish(previewFrame(key, 1))
                withTimeout(3000) { entered.await() }
                repeat(1000) { index -> hub.publish(previewFrame(key, index + 2L)); events.publish(error()) }
                assertEquals(1, hub.metrics.pendingFrames)
                assertEquals(1, hub.metrics.retainedFrames)
                assertEquals(999L, hub.metrics.droppedFrames)
                repeat(1000) { assertEquals(it + 1L, eventSocket.nextJson()["sequence"].asLong()) }
                release.complete(Unit)
                assertImage(1, "A", "RAW")
                assertImage(1001, "A", "RAW")
                close()
            }
            close()
        }
        withTimeout(3000) { while (hub.metrics.subscribers != 0) delay(5) }
        assertEquals(0, hub.metrics.pendingFrames)
    }

    @Test fun cameraAndStageSubscriptionsRemainSeparateAndDisconnectCleansUp() = testApplication {
        val keys = setOf(previewKey(), previewKey("B"), previewKey("A", PreviewStage.EDGE))
        val hub = LatestPreviewHub(keys, 100, 4)
        keys.forEachIndexed { index, key -> hub.publish(previewFrame(key, index + 10L)) }
        application { installPreviewWebSocket(hub, keys, PngPreviewEncoder()) }
        val ws = createClient { install(WebSockets) }
        ws.webSocket("/api/v1/previews?cameraId=A&stage=RAW") {
            assertImage(10, "A", "RAW")
            ws.webSocket("/api/v1/previews?cameraId=B&stage=RAW") { assertImage(11, "B", "RAW"); close() }
            ws.webSocket("/api/v1/previews?cameraId=A&stage=EDGE") { assertImage(12, "A", "EDGE"); close() }
            close()
        }
        withTimeout(3000) { while (hub.metrics.subscribers != 0) delay(5) }
        assertEquals(0, hub.metrics.pendingFrames)
    }

    @Test fun malformedSubscriptionsAreRejectedWithoutSubscribing() = testApplication {
        val key = previewKey(); val hub = LatestPreviewHub(setOf(key), 100, 4)
        application { installPreviewWebSocket(hub, setOf(key), PngPreviewEncoder()) }
        val ws = createClient { install(WebSockets) }
        for (query in listOf("", "cameraId=X&stage=RAW", "cameraId=A&stage=NONE", "cameraId=A&stage=RAW&maxFps=0",
            "cameraId=A&stage=RAW&maxFps=31", "cameraId=A&stage=RAW&maxFps=NaN", "cameraId=A&cameraId=A&stage=RAW",
            "cameraId=A&stage=RAW&deviceId=unknown")) {
            ws.webSocket("/api/v1/previews?$query") {
                assertEquals("INVALID_SUBSCRIPTION", nextJson()["code"].asText())
                assertEquals(1008.toShort(), withTimeout(3000) { closeReason.await() }!!.code)
            }
        }
        assertEquals(0, hub.metrics.subscribers)
    }

    @Test fun backendEncoderProducesPngWithCorrectPixelsAndDimensions() = runBlocking {
        val encoded = PngPreviewEncoder().encode(previewFrame(previewKey(), 1))
        assertEquals("image/png", encoded.mediaType)
        assertArrayEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10), encoded.bytes.take(8).toByteArray())
        val image = ImageIO.read(ByteArrayInputStream(encoded.bytes))
        assertEquals(2, image.width); assertEquals(1, image.height)
        assertEquals(0xff112233.toInt(), image.getRGB(0, 0))
        assertEquals(0xffabcdef.toInt(), image.getRGB(1, 0))
    }

    @Test fun invalidDimensionsAndPixelLengthsCannotReachEncoder() {
        for ((width, height, bytes) in listOf(Triple(0, 1, 0), Triple(-1, 1, 0), Triple(Int.MAX_VALUE, 2, 0), Triple(2, 2, 4))) {
            assertThrows(IllegalArgumentException::class.java) {
                PreviewFrame(previewKey(), 0, 0, width, height, PreviewFormat.ARGB32, ByteArray(bytes))
            }
        }
    }
}

internal fun previewKey(camera: String = "A", stage: PreviewStage = PreviewStage.RAW) = PreviewKey("device", CameraId(camera), stage)
internal fun previewFrame(key: PreviewKey, sequence: Long) = PreviewFrame(key, 2000 + sequence, sequence, 2, 1,
    PreviewFormat.ARGB32, byteArrayOf(-1, 0x11, 0x22, 0x33, -1, 0xab.toByte(), 0xcd.toByte(), 0xef.toByte()))
internal suspend fun DefaultClientWebSocketSession.assertImage(sequence: Long, camera: String, stage: String) {
    val metadata = nextJson()
    assertEquals("preview.frame", metadata["type"].asText())
    assertEquals("device", metadata["deviceId"].asText())
    assertEquals(camera, metadata["cameraId"].asText()); assertEquals(stage, metadata["stage"].asText())
    assertEquals(sequence, metadata["sourceSequence"].asLong()); assertEquals(2000 + sequence, metadata["timestamp"].asLong())
    assertEquals(2, metadata["width"].asInt()); assertEquals(1, metadata["height"].asInt())
    assertTrue(metadata["configurationRevision"].isNull); assertTrue(metadata["draftRevision"].isNull); assertTrue(metadata["official"].isNull)
    assertEquals("image/png", metadata["mediaType"].asText())
    val bytes = withTimeout(3000) { (incoming.receive() as Frame.Binary).readBytes() }
    assertEquals(metadata["byteLength"].asInt(), bytes.size)
    val image = ImageIO.read(ByteArrayInputStream(bytes))
    assertEquals(2, image.width); assertEquals(1, image.height)
}
