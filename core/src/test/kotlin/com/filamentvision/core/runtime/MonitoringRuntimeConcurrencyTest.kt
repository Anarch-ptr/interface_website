package com.filamentvision.core.runtime

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MonitoringRuntimeConcurrencyTest {
    @Test fun repeatedStartResetsAssemblerOnlyForANewSession() = runTest {
        val single = com.filamentvision.core.measurement.SingleCameraMeasurementAssembler()
        val resets = mutableListOf<Int>()
        var accepted = 0
        val assembler = object : com.filamentvision.core.measurement.MeasurementAssembler {
            override fun reset() { resets += accepted; accepted = 0; single.reset() }
            override fun accept(evidence: com.filamentvision.core.vision.CameraMeasurement): List<com.filamentvision.core.model.OfficialMeasurement> {
                accepted++
                return single.accept(evidence)
            }
        }
        val h = Harness(backgroundScope, assembler = assembler)
        val first = h.runtime.start(StartMonitoringCommand("start"))
        h.send(1); runCurrent()
        val duplicate = h.runtime.start(StartMonitoringCommand("another-start"))
        assertEquals(StartOutcome.ALREADY_RUNNING, duplicate.outcome)
        assertEquals(first.session, duplicate.session)
        assertEquals(listOf(0), resets)
        h.runtime.stop(StopMonitoringCommand("stop"))
        h.runtime.start(StartMonitoringCommand("restart"))
        h.send(1, 1); runCurrent()
        h.runtime.stop(StopMonitoringCommand("stop-again"))
        assertEquals(listOf(0, 1), resets)
        h.assertFinalized(2, 2)
        h.runtime.close()
    }

    @Test fun slowWriteDoesNotRestartTheOneSecondPendingMeasurementDeadline() = runTest {
        val h = Harness(backgroundScope)
        val writeStarts = mutableListOf<Long>()
        h.sink.onWrite = {
            writeStarts += testScheduler.currentTime
            if (writeStarts.size == 1) delay(800)
        }
        h.runtime.start(StartMonitoringCommand("start"))
        h.send(5); runCurrent()
        advanceTimeBy(1001); runCurrent()
        assertEquals(listOf(1000L), writeStarts)
        h.send(1, 5); runCurrent()
        assertEquals(6L, h.runtime.state.value.statistics.sampleCount)
        advanceTimeBy(799); runCurrent()
        assertEquals(5, h.sink.measurements.size)
        assertEquals(MonitoringLifecycle.RUNNING, h.runtime.state.value.lifecycle)
        advanceTimeBy(201); runCurrent()
        // The sample accepted at 1001 must be written by 2001, without stop forcing a flush.
        assertEquals(6, h.sink.measurements.size)
        assertEquals(listOf(1000L, 2000L), writeStarts)
        assertEquals(MonitoringLifecycle.RUNNING, h.runtime.state.value.lifecycle)
        h.runtime.close()
        h.assertFinalized(6)
        assertEquals(listOf(5, 1), h.sink.batchSizes)
    }

    @Test fun overdueFlushTicksConflateAndDrainImmediatelyAfterLongWrite() = runTest {
        val h = Harness(backgroundScope)
        val writeStarts = mutableListOf<Long>()
        h.sink.onWrite = {
            writeStarts += testScheduler.currentTime
            if (writeStarts.size == 1) delay(3800)
        }
        h.runtime.start(StartMonitoringCommand("start"))
        h.send(5); runCurrent()
        advanceTimeBy(1001); runCurrent()
        h.send(1, 5); runCurrent()
        advanceTimeBy(3799); runCurrent()
        assertEquals(MonitoringLifecycle.RUNNING, h.runtime.state.value.lifecycle)
        assertEquals(listOf(1000L, 4800L), writeStarts)
        assertEquals(6, h.sink.measurements.size)
        // Multiple elapsed ticks cause one overdue flush, then the timer waits normally.
        advanceTimeBy(199); runCurrent()
        assertEquals(2, writeStarts.size)
        h.runtime.close()
        h.assertFinalized(6)
        assertEquals(listOf(5, 1), h.sink.batchSizes)
    }

    @Test fun injectedAssemblerAndIdentityCallbacksCanReadRuntimeAndBurstKeepsBatchThreshold() = runTest {
        lateinit var h: Harness
        val callbackReads = mutableListOf<Boolean>()
        fun readRuntime() {
            callbackReads += runBlocking { withTimeoutOrNull(100) { h.runtime.realtimeMeasurements(); true } } ?: false
        }
        val single = com.filamentvision.core.measurement.SingleCameraMeasurementAssembler()
        val assembler = object : com.filamentvision.core.measurement.MeasurementAssembler {
            override fun reset() { readRuntime() }
            override fun accept(evidence: com.filamentvision.core.vision.CameraMeasurement): List<com.filamentvision.core.model.OfficialMeasurement> {
                readRuntime()
                return List(9) { single.accept(evidence).single() }
            }
        }
        h = Harness(backgroundScope, assembler = assembler, epochMillis = { readRuntime(); 1234L }, sessionId = { readRuntime(); "session" })
        h.runtime.start(StartMonitoringCommand("start"))
        h.send(1); runCurrent()
        h.runtime.close()
        assertEquals(listOf(true, true, true, true, true), callbackReads)
        assertEquals(listOf(8, 1), h.sink.batchSizes)
        h.assertFinalized(9)
    }

    @Test fun cancellationInsensitiveStartCannotActivateAfterCleanupAndEscapeOwnership() = runTest {
        val h = Harness(backgroundScope)
        val gate = CompletableDeferred<Unit>()
        h.source.startGate = gate
        h.source.activateAfterCancelledStart = true
        val start = async { h.runtime.start(StartMonitoringCommand("start")) }
        h.source.enteredStart.await()
        val stop = async { h.runtime.stop(StopMonitoringCommand("stop")) }
        runCurrent()
        assertEquals(1, h.source.stopCalls.get())
        assertEquals(MonitoringLifecycle.STOPPING, h.runtime.state.value.lifecycle)
        assertFalse(stop.isCompleted)
        assertEquals(StartOutcome.INTERRUPTED, h.runtime.start(StartMonitoringCommand("during-stop")).outcome)
        gate.complete(Unit)
        runCurrent()
        stop.await(); start.await()
        assertEquals(0, h.source.activeProducerCount)
        h.assertFinalized(0)
        h.runtime.close()
    }

    @Test fun batchPersistenceCanReadRuntimeWithoutReentrantOwnershipDeadlock() = runTest {
        val h = Harness(backgroundScope)
        val snapshots = mutableListOf<Int>()
        h.sink.onWrite = {
            snapshots += withTimeoutOrNull(100) { h.runtime.realtimeMeasurements().size } ?: -1
        }
        h.runtime.start(StartMonitoringCommand("start"))
        h.send(8); runCurrent()
        advanceTimeBy(101); runCurrent()
        assertEquals(listOf(8), snapshots)
        h.runtime.close()
        h.assertFinalized(8)
        assertEquals(listOf(8), h.sink.batchSizes)
    }

    @Test fun timerWriteAcknowledgesOnlyClaimedPrefixWhileNewMeasurementsArrive() = runTest {
        val h = Harness(backgroundScope)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var first = true
        h.sink.onWrite = {
            if (first) {
                first = false
                entered.complete(Unit)
                release.await()
            }
        }
        h.runtime.start(StartMonitoringCommand("start"))
        h.send(5); runCurrent()
        advanceTimeBy(1000); runCurrent()
        entered.await()
        h.send(3, 5); runCurrent()
        val acceptedDuringWrite = withTimeoutOrNull(100) { h.runtime.realtimeMeasurements().size }
        release.complete(Unit); runCurrent()
        h.runtime.close()
        assertEquals(8, acceptedDuringWrite)
        h.assertFinalized(8)
        assertEquals(listOf(5, 3), h.sink.batchSizes)
        assertEquals((0L..7L).toList(), h.sink.measurements.map { it.evidence.single().sequence })
    }

    @Test fun unconfinedStartupPersistenceIsNotInvokedInsideOwnershipTransition() = runTest {
        val h = Harness(CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)))
        var canRead = false
        h.sink.onCreate = { canRead = withTimeoutOrNull(100) { h.runtime.realtimeMeasurements(); true } ?: false }
        h.runtime.start(StartMonitoringCommand("start"))
        assertTrue(canRead)
        h.runtime.close()
        h.assertFinalized(0)
    }

    @Test fun failedTimerClaimIsRetriedWithoutLosingLaterAcceptedMeasurements() = runTest {
        val h = Harness(backgroundScope)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val attemptedSizes = mutableListOf<Int>()
        h.sink.onWrite = { batch ->
            attemptedSizes += batch.size
            assertEquals(8, withTimeoutOrNull(100) {
                if (attemptedSizes.size == 1) {
                    entered.complete(Unit)
                    release.await()
                }
                h.runtime.realtimeMeasurements().size
            })
            if (attemptedSizes.size == 1) error("first write rejected before acknowledgement")
        }
        h.runtime.start(StartMonitoringCommand("start"))
        h.send(5); runCurrent()
        advanceTimeBy(1000); runCurrent()
        entered.await()
        h.send(3, 5); runCurrent()
        release.complete(Unit); runCurrent()
        assertEquals(MonitoringLifecycle.ERROR, h.runtime.state.value.lifecycle)
        assertEquals(listOf(5, 5, 3), attemptedSizes)
        assertEquals(listOf(5, 3), h.sink.batchSizes)
        assertEquals((0L..7L).toList(), h.sink.measurements.map { it.evidence.single().sequence })
        h.assertFinalized(8)
        h.runtime.close()
    }

    @Test fun fiftyDistinctConcurrentStartsStillReserveOnlyOneProducer() = runTest {
        val h = Harness(backgroundScope)
        val gate = CompletableDeferred<Unit>()
        val calls = List(50) { n -> async(Dispatchers.Default) { gate.await(); h.runtime.start(StartMonitoringCommand("s$n")) } }
        gate.complete(Unit)
        val results = calls.awaitAll()
        assertEquals(1, results.map { it.session }.distinct().size)
        assertEquals(1, h.source.starts.get())
        h.runtime.stop(StopMonitoringCommand("stop"))
        h.assertFinalized(0)
        h.runtime.close()
    }

    @Test fun concurrentDuplicateStartCreatesOneSessionAndProducer() = runTest {
        val h = Harness(backgroundScope)
        val gate = CompletableDeferred<Unit>()
        val calls = List(50) { async(Dispatchers.Default) { gate.await(); h.runtime.start(StartMonitoringCommand("same")) } }
        gate.complete(Unit)
        val results = calls.awaitAll()
        assertEquals(1, results.distinct().size)
        assertEquals(StartOutcome.STARTED, results.first().outcome)
        assertEquals(1, h.source.starts.get())
        assertEquals(1, h.runtime.state.value.activeProducerCount)
        h.runtime.stop(StopMonitoringCommand("stop"))
        h.assertFinalized(0)
        h.runtime.close()
    }

    @Test fun startRacingStopEndsWithZeroOrOneProducerMatchingState() = runTest {
        val h = Harness(backgroundScope)
        repeat(30) { n ->
            val gate = CompletableDeferred<Unit>()
            val start = async(Dispatchers.Default) { gate.await(); h.runtime.start(StartMonitoringCommand("s$n")) }
            val stop = async(Dispatchers.Default) { gate.await(); h.runtime.stop(StopMonitoringCommand("t$n")) }
            gate.complete(Unit)
            start.await(); stop.await()
            val snapshot = h.runtime.state.value
            assertEquals(if (snapshot.lifecycle == MonitoringLifecycle.RUNNING) 1 else 0, snapshot.activeProducerCount)
            assertEquals(snapshot.activeProducerCount, h.source.activeProducerCount)
            h.runtime.stop(StopMonitoringCommand("cleanup$n"))
        }
        h.assertFinalized(0, 30)
        h.runtime.close()
    }

    @Test fun stopDuringStartingCancelsAndFinalizesOnce() = runTest {
        val h = Harness(backgroundScope)
        h.source.startGate = CompletableDeferred()
        val start = async { h.runtime.start(StartMonitoringCommand("start")) }
        h.source.enteredStart.await()
        assertEquals(MonitoringLifecycle.STARTING, h.runtime.state.value.lifecycle)
        assertEquals(1, h.runtime.state.value.activeProducerCount)
        withTimeout(1000) { h.runtime.stop(StopMonitoringCommand("stop")) }
        assertEquals(StartOutcome.INTERRUPTED, start.await().outcome)
        h.assertFinalized(0)
        h.runtime.close()
    }

    @Test fun restartAfterCompletedSessionCreatesOneNewSession() = runTest {
        val h = Harness(backgroundScope)
        val first = h.runtime.start(StartMonitoringCommand("first"))
        h.send(3); runCurrent()
        h.runtime.stop(StopMonitoringCommand("stop1"))
        val second = h.runtime.start(StartMonitoringCommand("second"))
        assertNull(h.runtime.state.value.latestMeasurement)
        assertEquals(0L, h.runtime.state.value.visionDiagnostics.processedFrames)
        h.send(2, 3); runCurrent()
        assertNotEquals(first.session, second.session)
        assertEquals("camera", h.runtime.state.value.latestMeasurement!!.evidence.single().cameraId.value)
        assertNull(h.runtime.state.value.latestMeasurement!!.legacyFusion)
        h.runtime.stop(StopMonitoringCommand("stop2"))
        h.assertFinalized(5, 2)
        assertEquals(2, h.source.starts.get())
        h.runtime.close()
    }

    @Test fun duplicateCommandIdsReturnOriginalResultAndCacheIsBounded() = runTest {
        val h = Harness(backgroundScope, cacheCapacity = 3)
        val start = h.runtime.start(StartMonitoringCommand("start"))
        val stop = h.runtime.stop(StopMonitoringCommand("stop"))
        assertEquals(start, h.runtime.start(StartMonitoringCommand("start")))
        assertEquals(stop, h.runtime.stop(StopMonitoringCommand("stop")))
        repeat(20) { h.runtime.stop(StopMonitoringCommand("noop$it")) }
        assertEquals(3, h.runtime.state.value.cachedCommandCount)
        h.assertFinalized(0)
        h.runtime.close()
    }

    @Test fun lateFramesFromClosedGenerationAreReleasedAndRejected() = runTest {
        val h = Harness(backgroundScope)
        h.runtime.start(StartMonitoringCommand("first"))
        h.send(1); runCurrent()
        var released = false
        val late = Harness.frame(400, lease = com.filamentvision.core.frame.FrameLease { released = true })
        h.source.lateOnStop = late
        h.runtime.stop(StopMonitoringCommand("stop1"))
        assertTrue(released)
        h.source.lateOnStop = null
        h.runtime.start(StartMonitoringCommand("second"))
        h.source.collectors.first().emit(Harness.frame(999))
        h.send(1, 1); runCurrent()
        h.runtime.stop(StopMonitoringCommand("stop2"))
        h.assertFinalized(2, 2)
        assertEquals(listOf(0L, 1L), h.sink.measurements.map { it.evidence.single().sequence })
        h.runtime.close()
    }

    @Test fun realtimeDropsOldestWhileWriteBufferFlushesAtEightAndOneSecond() = runTest {
        val h = Harness(backgroundScope)
        h.runtime.start(StartMonitoringCommand("start"))
        h.send(7); runCurrent()
        assertEquals(0, h.sink.measurements.size)
        advanceTimeBy(999); runCurrent()
        assertEquals(0, h.sink.measurements.size)
        advanceTimeBy(1); runCurrent()
        assertEquals(7, h.sink.measurements.size)
        h.send(8, 7); runCurrent()
        assertEquals(listOf(7, 8), h.sink.batchSizes)
        h.send(290, 15); runCurrent()
        val recent = h.runtime.realtimeMeasurements()
        assertEquals(300, recent.size)
        assertEquals(5L, recent.first().evidence.single().sequence)
        assertEquals(304L, recent.last().evidence.single().sequence)
        h.runtime.stop(StopMonitoringCommand("stop"))
        h.assertFinalized(305)
        h.runtime.close()
    }
}
