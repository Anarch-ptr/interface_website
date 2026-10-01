package com.filamentvision.core.runtime

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MonitoringRuntimeConcurrencyTest {
    @Test fun injectedPairingAndIdentityCallbacksCanReadRuntimeAndBurstKeepsBatchThreshold() = runTest {
        lateinit var h: Harness
        val callbackReads = mutableListOf<Boolean>()
        fun readRuntime() {
            callbackReads += runBlocking { withTimeoutOrNull(100) { h.runtime.realtimeMeasurements(); true } } ?: false
        }
        val pairing = object : com.filamentvision.core.fusion.FramePairingStrategy {
            override fun reset() { readRuntime() }
            override fun offer(measurement: com.filamentvision.core.vision.CameraMeasurement): List<com.filamentvision.core.fusion.MeasurementPair> {
                readRuntime()
                return List(9) { com.filamentvision.core.fusion.MeasurementPair(measurement, null) }
            }
        }
        h = Harness(backgroundScope, pairing = pairing, epochMillis = { readRuntime(); 1234L }, sessionId = { readRuntime(); "session" })
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
        assertEquals((0L..7L).toList(), h.sink.measurements.map { it.cameraA!!.sequence })
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
        assertEquals((0L..7L).toList(), h.sink.measurements.map { it.cameraA!!.sequence })
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
        h.send(2, 3, "B"); runCurrent()
        assertNotEquals(first.session, second.session)
        assertNull(h.runtime.state.value.latestMeasurement!!.cameraA)
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
        assertEquals(listOf(0L, 1L), h.sink.measurements.map { it.cameraA!!.sequence })
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
        assertEquals(5L, recent.first().cameraA!!.sequence)
        assertEquals(304L, recent.last().cameraA!!.sequence)
        h.runtime.stop(StopMonitoringCommand("stop"))
        h.assertFinalized(305)
        h.runtime.close()
    }
}
