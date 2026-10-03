package com.filamentvision.backend.e2e

import com.filamentvision.backend.*
import com.filamentvision.backend.application.*
import com.filamentvision.backend.api.*
import com.filamentvision.backend.simulator.fixtures.*
import com.filamentvision.core.event.*
import com.filamentvision.core.runtime.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class SimulatedMonitoringE2eTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun imagesReachDurableMeasurementsAlarmsAndOrderedFinalEvent() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val c = BackendComposition.open(temporary.root.toPath().resolve("e2e.db"), temporary.root.toPath().resolve("objects"),
            SimulatorBackendInputSourceFactory,
            dispatcher, dispatcher, { 1000 + testScheduler.currentTime }, { testScheduler.currentTime })
        try {
            assertEquals(0, c.application.state("filamentvision-1").monitoring.activeProducerCount)
            val starts = (1..12).map { async { c.application.start("filamentvision-1", "start", 1.75, 1) } }.awaitAll()
            runCurrent()
            assertEquals(1, starts.map { it.session!!.id }.toSet().size)
            assertEquals(1L, c.source.totalProducerStarts)
            val session = starts.first().session!!.id
            val normal = c.runtime.state.value.latestMeasurement!!
            assertEquals(1.75, normal.evidence.single().diameterMm, 1e-9)
            assertEquals(0L, normal.evidence.single().sequence)
            assertEquals(1000L, normal.evidence.single().timestamp)
            c.source.setScenario(SimulationScenario.HIGH_DIAMETER)
            advanceTimeBy(250); runCurrent()
            val high = c.runtime.state.value.latestMeasurement!!
            assertEquals(1.85, high.evidence.single().diameterMm, 1e-9)
            assertEquals(1.85, high.diameterMm, 1e-9)
            assertEquals(2L, high.evidence.single().sequence)
            assertEquals(1250L, high.evidence.single().timestamp)
            val stops = (1..12).map { async { c.application.stop("filamentvision-1", "stop") } }.awaitAll()
            assertTrue(stops.all { it.error == null })
            val history = c.application.history(session, PageQuery()).items
            assertEquals(3, history.size)
            assertTrue(history.all { it.appliedRevision == 1L })
            assertEquals(high, history.last().measurement)
            assertEquals(3L, c.application.session(session).finalization!!.statistics.sampleCount)
            val statistics = c.application.session(session).finalization!!.statistics
            assertEquals(1.8, statistics.averageDiameter, 1e-12)
            assertEquals(0.0408248290463863, statistics.standardDeviation, 1e-12)
            assertEquals(1.75, statistics.minimumDiameter, 1e-12)
            assertEquals(1.85, statistics.maximumDiameter, 1e-12)
            assertEquals(2, statistics.abnormalEventCount)
            assertEquals(2, c.application.alarms(session).size)
            val replay = c.events.replayAfter("filamentvision-1", 0) as EventReplay.Available
            assertEquals((1L..replay.latestSequence).toList(), replay.events.map { it.sequence })
            val finalAt = replay.events.indexOfFirst { it.event is DomainEvent.SessionFinalized }
            assertTrue(finalAt > replay.events.indexOfLast { it.event is DomainEvent.Alarm })
            assertTrue(finalAt > replay.events.indexOfLast { it.event is DomainEvent.Measurement })
            assertEquals(0 to 0, c.runtime.queuedCounts())
            assertEquals(0, c.source.activeProducerCount)
            assertEquals(1, c.source.maximumActiveProducerCount)
            assertEquals(1, c.previews.metrics.retainedFrames)
        } finally { c.close() }
    }

    @Test fun reopenPreservesFinalStatisticsAndStartsNewSessionWithOneProducer() = runTest {
        val d = StandardTestDispatcher(testScheduler)
        val db = temporary.root.toPath().resolve("restart.db")
        val root = temporary.root.toPath().resolve("objects")
        val c = BackendComposition.open(db, root, SimulatorBackendInputSourceFactory, d, d, { 1000 + testScheduler.currentTime }, { testScheduler.currentTime })
        val first = c.application.start("filamentvision-1", "start", null, null).session!!.id
        runCurrent(); c.application.stop("filamentvision-1", "stop"); c.close()
        val next = BackendComposition.open(db, root, SimulatorBackendInputSourceFactory, d, d, { 2000 + testScheduler.currentTime }, { testScheduler.currentTime })
        try {
            assertEquals(0, next.source.activeProducerCount)
            assertEquals(1L, next.application.session(first).finalization!!.statistics.sampleCount)
            assertEquals(1, next.application.history(first, PageQuery()).items.size)
            val second = next.application.start("filamentvision-1", "start", null, null).session!!.id
            assertNotEquals(first, second)
            next.source.setScenario(SimulationScenario.CONNECTION_FAILURE)
            advanceTimeBy(125); runCurrent()
            next.runtime.state.first { it.lifecycle == MonitoringLifecycle.ERROR }
            assertEquals(MonitoringLifecycle.ERROR, next.runtime.state.value.lifecycle)
            assertEquals(FinalizationReason.INTERRUPTED, next.application.session(second).finalization!!.reason)
            assertEquals(1, next.application.errors(second).size)
            assertEquals(1, next.source.maximumActiveProducerCount)
        } finally { next.close() }
    }
}
