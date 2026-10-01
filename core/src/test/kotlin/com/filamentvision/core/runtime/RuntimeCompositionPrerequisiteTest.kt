package com.filamentvision.core.runtime

import com.filamentvision.core.config.*
import com.filamentvision.core.frame.CameraId
import com.filamentvision.core.model.FusionConfiguration
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RuntimeCompositionPrerequisiteTest {
    @Test fun stalledAlarmWriterBoundsAcceptanceAndKeepsStoppingReservationUntilAcknowledged() = runTest {
        val h = Harness(backgroundScope)
        val release = CompletableDeferred<Unit>()
        h.sink.onAlarms = { release.await() }
        h.runtime.start(StartMonitoringCommand("start"))
        repeat(100) { h.source.input.send(Harness.frame(it.toLong(), width = if (it % 2 == 0) 5 else 4)) }
        runCurrent()
        assertEquals(8L, h.runtime.state.value.statistics.sampleCount)
        assertEquals(0 to 8, h.runtime.queuedCounts())
        val stop = async { h.runtime.stop(StopMonitoringCommand("stop")) }; runCurrent()
        assertFalse(stop.isCompleted)
        assertFalse(h.runtime.configureWhenStopped(MonitoringConfiguration(), CameraId("A"), CameraId("B")))
        assertEquals(0, h.sink.finalized.size)
        release.complete(Unit); stop.await()
        assertEquals(8, h.sink.alarms.size)
        assertEquals(0 to 0, h.runtime.queuedCounts())
        h.runtime.close()
        while (true) { (h.source.input.tryReceive().getOrNull() ?: break).close() }
    }
    @Test fun failedLiveAlarmWriteRetriesBeforeFinalizationWithoutDuplicateMeasurements() = runTest {
        val h = Harness(backgroundScope)
        var attempts = 0
        h.sink.onAlarms = {
            h.runtime.realtimeMeasurements() // Reentry cannot deadlock on the ownership lock.
            if (++attempts == 1) error("transient")
        }
        h.runtime.start(StartMonitoringCommand("start"))
        repeat(8) { h.source.input.send(Harness.frame(it.toLong(), width = 5)) }
        runCurrent()
        assertEquals(2, attempts)
        assertEquals(8, h.sink.measurements.size)
        assertEquals(1, h.sink.alarms.size)
        assertEquals(1, h.sink.finalized.size)
        assertEquals(0 to 0, h.runtime.queuedCounts())
        h.runtime.close()
    }

    @Test fun persistentAlarmFailureRetainsEvidenceAndRejectsConfigurationAndRestart() = runTest {
        val h = Harness(backgroundScope)
        h.sink.onAlarms = { error("unavailable") }
        h.runtime.start(StartMonitoringCommand("start"))
        repeat(8) { h.source.input.send(Harness.frame(it.toLong(), width = 5)) }
        runCurrent()
        assertEquals(0 to 1, h.runtime.queuedCounts())
        assertEquals(0, h.sink.finalized.size)
        assertFalse(h.runtime.configureWhenStopped(MonitoringConfiguration(), CameraId("A"), CameraId("B")))
        assertEquals(StartOutcome.FAILED, h.runtime.start(StartMonitoringCommand("restart")).outcome)
        assertTrue(runCatching { h.runtime.close() }.isFailure)
    }

    @Test fun stoppedConfigurationChangesNextSessionProcessingBatchTimerAndRing() = runTest {
        val h = Harness(backgroundScope)
        val c = MonitoringConfiguration(Harness.config.copy(calibration = Harness.config.calibration.copy(mmPerPixel = .2)),
            Harness.config, FusionConfiguration(1.0, 0.0, .7, 100), .8, 3, 250, 2)
        assertTrue(h.runtime.configureWhenStopped(c, CameraId("A"), CameraId("B")))
        h.runtime.start(StartMonitoringCommand("start"))
        assertFalse(h.runtime.configureWhenStopped(c.copy(targetDiameter = 4.0), CameraId("A"), CameraId("B")))
        h.send(3); runCurrent()
        assertEquals(.8, h.runtime.state.value.latestMeasurement!!.fusedDiameter, 0.0)
        assertEquals(3, h.sink.measurements.size)
        assertEquals(2, h.runtime.realtimeMeasurements().size)
        assertEquals(0, h.sink.alarms.size)
        h.source.input.send(Harness.frame(4, "B", 5)); runCurrent()
        assertEquals(.8, h.runtime.state.value.latestMeasurement!!.fusedDiameter, 0.0)
        advanceTimeBy(250); runCurrent()
        assertEquals(4, h.sink.measurements.size)
        h.runtime.close()
    }

    @Test fun alarmTransitionsDrainDurablyDuringMonitoringBeforeFinalEvent() = runTest {
        val h = Harness(backgroundScope)
        h.runtime.start(StartMonitoringCommand("start"))
        repeat(32) { h.source.input.send(Harness.frame(it.toLong(), width = if (it % 2 == 0) 5 else 4)) }
        runCurrent()
        assertEquals(MonitoringLifecycle.RUNNING, h.runtime.state.value.lifecycle)
        assertEquals(32, h.sink.alarms.size)
        assertEquals(32, h.sink.measurements.size)
        h.runtime.stop(StopMonitoringCommand("stop"))
        assertEquals(32, h.sink.alarms.size)
        assertEquals(listOf("finalize", "event"), h.sink.order.takeLast(2))
        h.runtime.close()
    }
}
