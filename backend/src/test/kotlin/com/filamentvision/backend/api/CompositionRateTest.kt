package com.filamentvision.backend.api

import com.filamentvision.backend.preview.PngPreviewEncoder
import com.filamentvision.backend.api.dto.EventDto
import com.filamentvision.core.event.SequencedEventHub
import com.filamentvision.core.preview.LatestPreviewHub
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

class CompositionRateTest {
    @Test fun configuredPreviewMaximumCapsHigherClientRate() = runBlocking {
        val key = previewKey(); val hub = LatestPreviewHub(setOf(key), 100, 2)
        val sent = Channel<Long>(2); val waited = CompletableDeferred<Long>()
        val job = launch {
            streamPreviews(hub, key, PngPreviewEncoder(), 30, nanos = { 0 }, pause = { waited.complete(it) }, maximumFramesPerSecond = { 2 }) { meta, _ -> sent.send(meta.sourceSequence) }
        }
        try {
            hub.publish(previewFrame(key, 1)); withTimeout(3000) { sent.receive() }
            hub.publish(previewFrame(key, 2)); assertEquals(500L, withTimeout(3000) { waited.await() })
        } finally { job.cancelAndJoin() }
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun configuredEventPollIntervalNeverChangesReliableEventCount() = runTest {
        val hub = SequencedEventHub(setOf("device"), 20)
        val sent = Channel<EventDto>(20)
        backgroundScope.launch { streamEvents(eventBackend(hub), hub, "device", 0, { 0 }, pollIntervalMillis = { 125 }) { sent.send(it) } }
        runCurrent()
        repeat(3) { hub.publish(error()) }
        advanceTimeBy(124); runCurrent(); assertTrue(sent.tryReceive().isFailure)
        advanceTimeBy(1); runCurrent()
        assertEquals(listOf(1L, 2L, 3L), List(3) { sent.tryReceive().getOrThrow().sequence })
    }
}
