package com.filamentvision.backend.e2e

import com.filamentvision.backend.BackendComposition
import com.filamentvision.backend.application.PageQuery
import com.filamentvision.backend.simulator.fixtures.*
import com.filamentvision.backend.input.profile.*
import com.filamentvision.backend.input.runtime.*
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
            var profile = RaspberryPiUdpInputProfile.defaults().copy(enabled = true,
                listenPort = DatagramSocket(0).use { it.localPort })
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
}
