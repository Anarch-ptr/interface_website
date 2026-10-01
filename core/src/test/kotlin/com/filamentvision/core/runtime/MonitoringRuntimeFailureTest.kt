package com.filamentvision.core.runtime

import com.filamentvision.core.alarm.AlarmLevel
import com.filamentvision.core.fusion.FusionReason
import com.filamentvision.core.input.ImageInputState
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MonitoringRuntimeFailureTest {
    @Test fun failureWithoutMessageStillPublishesError() = runTest {
        val h = Harness(backgroundScope)
        h.source.startFailure = IllegalStateException()
        assertEquals(StartOutcome.FAILED, h.runtime.start(StartMonitoringCommand("start")).outcome)
        assertEquals(MonitoringLifecycle.ERROR, h.runtime.state.value.lifecycle)
        assertNotNull(h.runtime.state.value.error)
        h.assertFinalized(0)
        h.runtime.close()
    }

    @Test fun dualCameraPipelinePreservesWeightedFusionAlarmAndPopulationStatistics() = runTest {
        val h = Harness(backgroundScope)
        h.runtime.start(StartMonitoringCommand("start"))
        h.source.input.send(Harness.frame(3)); runCurrent()
        h.source.input.send(Harness.frame(4, "B", width = 5)); runCurrent()
        val m = h.runtime.state.value.latestMeasurement!!
        assertEquals(.475, m.fusedDiameter, 1e-12)
        assertEquals(.1, m.shapeDifference, 1e-12)
        assertEquals(FusionReason.BOTH_CAMERAS, m.fusionReason)
        assertEquals(3L, m.cameraA!!.sequence)
        assertEquals(4L, m.cameraB!!.sequence)
        assertEquals(102L, m.cameraA!!.timestamp)
        assertEquals(103L, m.cameraB!!.timestamp)
        assertEquals(AlarmLevel.CRITICAL, h.runtime.state.value.latestAlarm.level)
        h.runtime.close()
        val stats = h.sink.finalized.single().statistics
        assertEquals(.4375, stats.averageDiameter, 1e-12)
        assertEquals(.0375, stats.standardDeviation, 1e-12)
        assertEquals(1.0, stats.averageConfidence, 0.0)
        assertEquals(1, stats.abnormalEventCount)
        assertEquals(m, h.sink.measurements.last())
        h.assertFinalized(2)
    }

    @Test fun failedStopCannotReleaseOwnershipWhileSourceStillProduces() = runTest {
        val h = Harness(backgroundScope)
        h.runtime.start(StartMonitoringCommand("start"))
        h.source.stopFailure = IllegalStateException("stop failed")
        val stopped = h.runtime.stop(StopMonitoringCommand("stop"))
        assertNotNull(stopped.error)
        assertEquals(MonitoringLifecycle.ERROR, h.runtime.state.value.lifecycle)
        assertEquals(1, h.runtime.state.value.activeProducerCount)
        assertEquals(StartOutcome.FAILED, h.runtime.start(StartMonitoringCommand("unsafe-restart")).outcome)
        assertEquals(1, h.source.starts.get())
        assertEquals(0, h.sink.finalized.size)
        // The adapter could not stop: close must report failure, never claim completed cleanup.
        assertNotNull(runCatching { h.runtime.close() }.exceptionOrNull())
        h.source.stopFailure = null
        h.source.stop()
    }

    @Test fun failedFinalFlushIsVisibleAndCannotBeDiscardedByRestartOrClose() = runTest {
        val h = Harness(backgroundScope)
        h.runtime.start(StartMonitoringCommand("start"))
        h.send(3); runCurrent()
        h.sink.writeFailure = IllegalStateException("storage unavailable")
        assertNotNull(h.runtime.stop(StopMonitoringCommand("stop")).error)
        assertEquals(StartOutcome.FAILED, h.runtime.start(StartMonitoringCommand("unsafe-restart")).outcome)
        assertEquals(3L, h.runtime.state.value.statistics.sampleCount)
        assertEquals(0, h.sink.finalized.size)
        assertEquals(0, h.sink.events.size)
        assertNotNull(runCatching { h.runtime.close() }.exceptionOrNull())
    }

    @Test fun failureDuringSourceStartFinalizesSessionAndAllowsRestart() = runTest {
        val h = Harness(backgroundScope)
        h.source.startFailure = IllegalStateException("start failed")
        assertEquals(StartOutcome.FAILED, h.runtime.start(StartMonitoringCommand("start")).outcome)
        h.assertFinalized(0)
        h.source.startFailure = null
        h.runtime.start(StartMonitoringCommand("restart"))
        h.send(2); runCurrent()
        h.runtime.close()
        h.assertFinalized(2, 2)
    }

    @Test fun producerFailureUsesInterruptedFinalizePipeline() = runTest {
        val h = Harness(backgroundScope)
        h.runtime.start(StartMonitoringCommand("start"))
        h.send(5); runCurrent()
        h.source.input.close(IllegalStateException("producer failed"))
        runCurrent()
        assertEquals(MonitoringLifecycle.ERROR, h.runtime.state.value.lifecycle)
        assertEquals(FinalizationReason.INTERRUPTED, h.sink.finalized.single().reason)
        assertEquals(listOf("measurements", "finalize", "event"), h.sink.order)
        h.assertFinalized(5)
        h.runtime.close()
    }

    @Test fun closeDuringRunningFlushesBeforeReturning() = runTest {
        val h = Harness(backgroundScope)
        h.runtime.start(StartMonitoringCommand("start"))
        h.source.input.send(Harness.frame(3, width = 5)); runCurrent()
        assertEquals(1L, h.runtime.state.value.statistics.sampleCount)
        assertEquals(AlarmLevel.CRITICAL, h.runtime.state.value.latestAlarm.level)
        h.runtime.close()
        assertEquals(listOf("measurements", "alarms", "finalize", "event"), h.sink.order)
        assertEquals(1, h.sink.alarms.size)
        assertEquals(1, h.sink.finalized.single().statistics.abnormalEventCount)
        h.assertFinalized(1)
        h.runtime.close()
        assertEquals(1, h.sink.events.size)
        assertEquals(StartOutcome.CLOSED, h.runtime.start(StartMonitoringCommand("closed")).outcome)
    }

    @Test fun runtimePreservesGoldenMetadataNullableEvidenceAndStatistics() = runTest {
        val h = Harness(backgroundScope)
        h.runtime.start(StartMonitoringCommand("start"))
        h.source.input.send(Harness.frame(3)); runCurrent()
        val m = h.runtime.state.value.latestMeasurement!!
        assertEquals(.4, m.fusedDiameter, 0.0)
        assertEquals(1.0, m.confidence, 0.0)
        assertEquals("A", m.cameraA!!.cameraId.value)
        assertEquals(3L, m.cameraA!!.sequence)
        assertEquals(102L, m.timestamp)
        assertNull(m.cameraB)
        assertEquals(FusionReason.CAMERA_B_MISSING, m.fusionReason)
        h.runtime.stop(StopMonitoringCommand("stop"))
        assertEquals(.4, h.sink.finalized.single().statistics.averageDiameter, 0.0)
        assertEquals(0.0, h.sink.finalized.single().statistics.standardDeviation, 0.0)
        assertEquals(m, h.sink.measurements.single())
        h.assertFinalized(1)
        h.runtime.close()
    }

    @Test fun sourceErrorStateFinalizesAndAllowsRestartWithoutOverlappingProducers() = runTest {
        val h = Harness(backgroundScope)
        h.runtime.start(StartMonitoringCommand("start"))
        h.send(2); runCurrent()
        h.source.state.value = ImageInputState.ERROR
        runCurrent()
        assertEquals(MonitoringLifecycle.ERROR, h.runtime.state.value.lifecycle)
        h.runtime.start(StartMonitoringCommand("restart"))
        h.send(3, 2); runCurrent()
        h.runtime.stop(StopMonitoringCommand("stop"))
        h.assertFinalized(5, 2)
        h.runtime.close()
    }

    @Test fun parentCancellationFinalizesAcceptedWork() = runTest {
        val parent = SupervisorJob()
        val h = Harness(CoroutineScope(coroutineContext + parent))
        h.runtime.start(StartMonitoringCommand("start"))
        h.send(3); runCurrent()
        parent.cancel(); runCurrent()
        h.runtime.close()
        h.assertFinalized(3)
        assertEquals(FinalizationReason.INTERRUPTED, h.sink.finalized.single().reason)
    }
}
