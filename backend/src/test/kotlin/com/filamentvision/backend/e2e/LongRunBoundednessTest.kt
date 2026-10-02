package com.filamentvision.backend.e2e

import com.filamentvision.backend.BackendComposition
import com.filamentvision.backend.application.PageQuery
import com.filamentvision.backend.simulator.SimulationScenario
import com.filamentvision.backend.input.profile.ApplicationFramingProfile
import com.filamentvision.backend.input.profile.FramingFormat
import com.filamentvision.backend.input.profile.FramingScope
import com.filamentvision.backend.input.runtime.UdpCameraInputSource
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
    @Test fun eightHoursAtEightHzPerCameraRetainFixedMemoryAndPersistEveryAcceptedSample() = runTest(timeout = 20.minutes) {
        val d = StandardTestDispatcher(testScheduler)
        val c = BackendComposition.open(temporary.root.toPath().resolve("eight-hours.db"), temporary.root.toPath().resolve("objects"),
            d, d, { 1000 + testScheduler.currentTime }, { testScheduler.currentTime })
        try {
            // Supported batch configuration reduces physical fsyncs, never frame processing or rate.
            c.application.saveAndApply("simulator-1", "long-config", BackendComposition.defaultConfiguration().copy(writeBatchSize = 256, flushIntervalMillis = 16000))
            val session = c.application.start("simulator-1", "start", null, null).session!!.id
            runCurrent()
            var maxQueued = 0; var maxAlarms = 0
            repeat(230399) { tick ->
                if (tick % 1000 == 0) {
                    c.source.setScenario(if (tick % 2000 == 0) SimulationScenario.HIGH_DIAMETER else SimulationScenario.NORMAL)
                    c.application.start("simulator-1", "observe-$tick", null, null)
                    c.application.save("simulator-1", "save-$tick", BackendComposition.defaultConfiguration())
                }
                advanceTimeBy(125); runCurrent()
                val queued = c.runtime.queuedCounts()
                maxQueued = maxOf(maxQueued, queued.first); maxAlarms = maxOf(maxAlarms, queued.second)
                assertTrue(queued.first <= 256); assertTrue(queued.second <= 256)
                if (tick % 1000 == 0) {
                    assertTrue(c.runtime.realtimeMeasurements().size <= 300)
                    assertTrue(c.events.metrics("simulator-1").retained <= 256)
                    assertEquals(2, c.previews.metrics.retainedFrames)
                    assertTrue(c.previews.metrics.pendingFrames <= 64)
                    assertTrue(c.snapshotCache.state().entries <= 2)
                    assertTrue(c.snapshotCache.state().totalBytes <= 1024 * 1024)
                    assertTrue(c.runtime.state.value.cachedCommandCount <= 128)
                    assertTrue(c.cachedFacadeCommands <= 128)
                }
            }
            c.application.stop("simulator-1", "stop")
            val accepted = c.runtime.state.value.statistics.sampleCount
            assertEquals(460800L, accepted)
            assertEquals(460800L, c.metrics.snapshot().cameras.values.sumOf { it.processed.count })
            var cursor: HistoryCursor? = null; var persisted = 0L
            do {
                val page = c.application.history(session, PageQuery(after = cursor, limit = 500))
                persisted += page.items.size; cursor = page.nextCursor
            } while (cursor != null)
            assertEquals(accepted, persisted)
            assertEquals(460800L, c.metrics.snapshot().counters.getValue(MetricCounter.DATABASE_ROW).count)
            assertEquals(accepted, c.application.session(session).finalization!!.statistics.sampleCount)
            assertEquals(0 to 0, c.runtime.queuedCounts())
            assertEquals(1, c.source.maximumActiveProducerCount)
            assertEquals(1L, c.source.totalProducerStarts)
            assertEquals(0, c.source.activeProducerCount)
            println("LONG_RUN ticks=230400 frames=460800 accepted=$accepted persisted=$persisted virtualMillis=${testScheduler.currentTime + 125} maxQueued=$maxQueued maxAlarms=$maxAlarms realtime=${c.runtime.realtimeMeasurements().size} replay=${c.events.metrics("simulator-1").retained} previews=${c.previews.metrics.retainedFrames} commands=${c.runtime.state.value.cachedCommandCount}/${c.cachedFacadeCommands} dbBatches=${c.metrics.snapshot().counters.getValue(MetricCounter.DATABASE_BATCH).count}")
        } finally { c.close() }
    }

    @Test fun repeatedUdpApplyStartStopKeepsReceiversAndAllRetainedStateBounded() = runBlocking {
        val first = DatagramSocket(0)
        val second = DatagramSocket(0)
        val ports = try { first.localPort to second.localPort } finally { first.close(); second.close() }
        val c = BackendComposition.open(
            temporary.root.toPath().resolve("udp-cycles.db"),
            temporary.root.toPath().resolve("udp-cycle-objects"),
        )
        try {
            var profiles = BackendComposition.defaultUdpInputProfiles().let {
                it.copy(cameraA = it.cameraA.copy(listenPort = ports.first), cameraB = it.cameraB.copy(listenPort = ports.second))
            }
            var saved = c.application.saveUdpInput(c.deviceId, "udp-save-initial", profiles)
            c.application.applyUdpInput(c.deviceId, "udp-apply-initial", saved.savedRevision, saved.savedRevision)
            val source = c.inputSource as UdpCameraInputSource

            repeat(16) { cycle ->
                c.application.start(c.deviceId, "udp-start-$cycle", null, null)
                assertEquals(1, source.activeProducerCount)
                assertEquals(2, source.activeReceiverCount)
                profiles = profiles.copy(cameraA = profiles.cameraA.copy(applicationFraming = ApplicationFramingProfile(
                    headerEnabled = true,
                    headerFormat = FramingFormat.ASCII,
                    headerBytes = if (cycle % 2 == 0) "A" else "B",
                    headerScope = FramingScope.FRAME,
                )))
                saved = c.application.saveUdpInput(c.deviceId, "udp-save-$cycle", profiles)
                c.application.applyUdpInput(c.deviceId, "udp-apply-$cycle", saved.savedRevision, saved.savedRevision)
                c.application.stop(c.deviceId, "udp-stop-$cycle")

                val bounds = source.boundedState()
                assertEquals(0, bounds.inFlightFrames)
                assertEquals(0L, bounds.bufferedReassemblyBytes)
                assertTrue(bounds.reassemblyTombstones <= 128)
                assertEquals(0, bounds.receiverJobs)
                assertTrue(bounds.pendingInputs <= 2)
                assertTrue(c.packetInspector.retainedSnapshotCount <= 2)
                assertTrue(c.packetInspector.retainedPreviewBytes <= 1_024)
                assertTrue(c.runtime.state.value.cachedCommandCount <= 128)
                assertTrue(c.cachedFacadeCommands <= 128)
            }
            assertEquals(1, source.maximumActiveProducerCount)
            assertEquals(0, source.activeProducerCount)
            println("UDP_BOUNDED cycles=16 maxProducer=${source.maximumActiveProducerCount} " +
                "receivers=${source.activeReceiverCount} reassembly=${source.boundedState().bufferedReassemblyBytes} " +
                "inspector=${c.packetInspector.retainedPreviewBytes} commands=${c.runtime.state.value.cachedCommandCount}/${c.cachedFacadeCommands}")
        } finally { c.close() }
        Unit
    }
}
