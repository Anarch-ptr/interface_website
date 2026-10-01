package com.filamentvision.backend.simulator

import com.filamentvision.core.frame.CameraFrame
import com.filamentvision.core.frame.CameraId
import com.filamentvision.core.frame.FrameLease
import com.filamentvision.core.input.ImageInputState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SimulatorImageInputSourceTest {
    private fun TestScope.source(factory: SimulatorFrameFactory = SimulatorFrameFactory()) = SimulatorImageInputSource(
        scope = backgroundScope, dispatcher = StandardTestDispatcher(testScheduler),
        cameraAId = CameraId("left"), cameraBId = CameraId("right"),
        epochMillis = { 1_000 + testScheduler.currentTime },
        monotonicNanos = { testScheduler.currentTime * 1_000_000 },
        factory = factory,
    )

    private fun trackingFactory(releases: MutableList<Int>) = SimulatorFrameFactory {
        val index = releases.size
        releases += 0
        FrameLease { releases[index]++ }
    }

    // Missing close on replacement or repeated close during stop would change actual release callbacks.
    @Test fun replacementAndStopReleaseEveryDiscardedLeaseExactlyOnce() = runTest {
        val releases = mutableListOf<Int>()
        val source = source(trackingFactory(releases))
        source.start(); runCurrent()
        assertEquals(listOf(0, 0), releases)
        advanceTimeBy(125); runCurrent()
        assertEquals(listOf(1, 1, 0, 0), releases)
        assertEquals(2L, source.diagnostics.value.droppedFrames)
        source.stop(); source.stop()
        assertEquals(listOf(1, 1, 1, 1), releases)
        assertEquals(4L, source.diagnostics.value.droppedFrames)
    }

    // Failure must release queued leases before stop, without a second release on repeated failure ticks.
    @Test fun failureReleasesQueuedLeasesExactlyOnce() = runTest {
        val releases = mutableListOf<Int>()
        val source = source(trackingFactory(releases))
        source.start(); runCurrent()
        source.setScenario(SimulationScenario.CONNECTION_FAILURE)
        advanceTimeBy(125); runCurrent()
        assertEquals(listOf(1, 1), releases)
        advanceTimeBy(250); runCurrent()
        source.stop()
        assertEquals(listOf(1, 1), releases)
        assertEquals(2L, source.diagnostics.value.droppedFrames)
    }

    // A cancelled emit has already removed the frame from the mailbox; source cleanup must still release it.
    @Test fun cancelledHandoffReleasesRemovedLeaseAndCountsOneDrop() = runTest {
        val releases = mutableListOf<Int>()
        val source = source(trackingFactory(releases))
        source.setScenario(SimulationScenario.CAMERA_A_ONLY)
        source.start(); runCurrent()
        val received = CompletableDeferred<CameraFrame>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            source.frames.collect { received.complete(it); awaitCancellation() }
        }
        val frame = received.await()
        assertEquals(listOf(0), releases)
        collector.cancelAndJoin()
        assertEquals(listOf(1), releases)
        assertEquals(1L, source.diagnostics.value.droppedFrames)
        assertEquals(1, source.activeProducerCount)
        frame.close()
        source.stop()
        assertEquals(listOf(1), releases)
        assertEquals(1L, source.diagnostics.value.droppedFrames)
    }

    // A downstream failure must propagate unchanged while cleanup remains safe if downstream already closed.
    @Test fun throwingHandoffCountsDropAndDoesNotReleaseLeaseTwice() = runTest {
        val releases = mutableListOf<Int>()
        val source = source(trackingFactory(releases))
        source.setScenario(SimulationScenario.CAMERA_A_ONLY)
        source.start(); runCurrent()
        val failure = IllegalStateException("consumer failure")
        val observed = CompletableDeferred<Throwable>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            try {
                source.frames.collect { it.close(); throw failure }
            } catch (error: IllegalStateException) {
                observed.complete(error)
            }
        }
        collector.join()
        assertSame(failure, observed.await())
        assertEquals(listOf(1), releases)
        assertEquals(1L, source.diagnostics.value.droppedFrames)
        source.stop()
        assertEquals(listOf(1), releases)
        assertEquals(1L, source.diagnostics.value.droppedFrames)
    }

    // Removing the start mutex would create multiple jobs; removing join/queue clearing leaks frames after stop.
    @Test fun concurrentStartsOwnOneProducerAndStopJoinsIt() = runTest {
        val source = source()
        val frames = mutableListOf<CameraFrame>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { source.frames.collect { frames += it } }
        List(20) { async { source.start() } }.awaitAll()
        runCurrent()
        assertEquals(1, source.activeProducerCount)
        assertEquals(1L, source.totalProducerStarts)
        assertEquals(ImageInputState.RUNNING, source.state.value)
        assertEquals(setOf(CameraId("left"), CameraId("right")), frames.map { it.cameraId }.toSet())
        List(20) { async { source.stop() } }.awaitAll()
        val count = frames.size
        advanceTimeBy(1_000); runCurrent()
        assertEquals(count, frames.size)
        assertEquals(0, source.activeProducerCount)
        assertEquals(ImageInputState.IDLE, source.state.value)
        frames.forEach { it.close(); it.close() }
    }

    // Resetting source counters on restart would repeat per-camera sequence or timestamp.
    @Test fun restartContinuesSequencesWithOneProducer() = runTest {
        val source = source()
        val frames = mutableListOf<CameraFrame>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { source.frames.collect { frames += it } }
        source.start(); runCurrent(); source.stop()
        source.start(); runCurrent(); source.stop()
        assertEquals(2L, source.totalProducerStarts)
        assertEquals(1, source.maximumActiveProducerCount)
        frames.groupBy { it.cameraId }.values.forEach { camera ->
            assertEquals(listOf(0L, 1L), camera.map { it.sequence })
            assertTrue(camera[1].capturedAtEpochMillis > camera[0].capturedAtEpochMillis)
        }
        frames.forEach { it.close() }
    }

    // A lifecycle race must never create overlapping producers or a RUNNING source with no owner.
    @Test fun startStopRacesRemainConsistent() = runTest {
        val source = source()
        repeat(30) {
            List(20) { index -> async(Dispatchers.Default) { if (index % 2 == 0) source.start() else source.stop() } }.awaitAll()
            runCurrent()
            assertTrue(source.activeProducerCount in 0..1)
            assertEquals(if (source.activeProducerCount == 0) ImageInputState.IDLE else ImageInputState.RUNNING, source.state.value)
        }
        source.stop()
        assertEquals(1, source.maximumActiveProducerCount)
        assertEquals(0, source.activeProducerCount)
    }

    // A failure must clear queued images and stop emission while keeping one owner for recovery.
    @Test fun connectionFailureStopsFramesAndRecordsErrorWithoutAnotherProducer() = runTest {
        val source = source()
        source.start(); runCurrent()
        source.setScenario(SimulationScenario.CONNECTION_FAILURE)
        advanceTimeBy(125); runCurrent()
        assertEquals(ImageInputState.ERROR, source.state.value)
        assertNotNull(source.diagnostics.value.lastError)
        assertEquals(2L, source.diagnostics.value.droppedFrames)
        val frames = mutableListOf<CameraFrame>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { source.frames.collect { frames += it } }
        List(20) { async { source.start() } }.awaitAll()
        advanceTimeBy(1_000); runCurrent()
        assertTrue(frames.isEmpty())
        assertEquals(1, source.activeProducerCount)
        assertEquals(1L, source.totalProducerStarts)
        source.stop()
        assertEquals(ImageInputState.IDLE, source.state.value)
        assertEquals(0, source.activeProducerCount)
    }

    // Recovery must clear the active error, continue sequences and use the already-owned producer.
    @Test fun explicitNormalRecoveryResumesFramesAndClearsErrorWithoutRestart() = runTest {
        val source = source()
        val frames = mutableListOf<CameraFrame>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { source.frames.collect { frames += it } }
        source.start(); runCurrent()
        source.setScenario(SimulationScenario.CONNECTION_FAILURE)
        advanceTimeBy(500); runCurrent()
        assertEquals(2, frames.size)
        assertEquals(ImageInputState.ERROR, source.state.value)
        source.setScenario(SimulationScenario.NORMAL)
        assertEquals(SimulationScenario.NORMAL, source.scenario.value)
        advanceTimeBy(125); runCurrent()
        assertEquals(ImageInputState.RUNNING, source.state.value)
        assertNull(source.diagnostics.value.lastError)
        assertEquals(1L, source.totalProducerStarts)
        assertEquals(1, source.maximumActiveProducerCount)
        assertEquals(4, frames.size)
        frames.groupBy { it.cameraId }.values.forEach { camera ->
            assertEquals(listOf(0L, 1L), camera.map { it.sequence })
            assertEquals(listOf(1_000L, 1_625L), camera.map { it.capturedAtEpochMillis })
        }
        source.stop()
        frames.forEach { it.close() }
    }

    // Invalid timing could spin the producer forever; identical IDs collapse the two-camera mailbox.
    @Test fun invalidPeriodsAndDuplicateCameraIdentitiesAreRejected() = runTest {
        for (period in listOf(0L, -1L)) {
            assertThrows(IllegalArgumentException::class.java) {
                SimulatorImageInputSource(backgroundScope, framePeriodMillis = period)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            SimulatorImageInputSource(backgroundScope, cameraAId = CameraId("same"), cameraBId = CameraId("same"))
        }
    }

    // Lifecycle discards are undelivered frames too and must not disappear from drop diagnostics.
    @Test fun stoppingCountsAndReleasesPendingFramesOnce() = runTest {
        val source = source()
        source.start(); runCurrent()
        source.stop(); source.stop()
        assertEquals(2L, source.diagnostics.value.droppedFrames)
        assertEquals(0, source.activeProducerCount)
    }

    // Unbounded delivery would replay stale sequences, and observer-owned production would change starts.
    @Test fun absentAndSlowCollectorsKeepOnlyLatestFramePerCamera() = runTest {
        val source = source()
        source.start(); runCurrent()
        advanceTimeBy(1_250); runCurrent()
        assertEquals(20L, source.diagnostics.value.droppedFrames)
        val frames = mutableListOf<CameraFrame>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            source.frames.collect { frames += it; delay(501) }
        }
        assertEquals(10L, frames.single().sequence)
        advanceTimeBy(501); runCurrent()
        assertEquals(14L, frames.last().sequence)
        collector.cancelAndJoin()
        val observers = List(20) { backgroundScope.launch { source.frames.collect { it.close() } } }
        runCurrent()
        observers.forEach { it.cancelAndJoin() }
        assertEquals(1L, source.totalProducerStarts)
        assertEquals(1, source.activeProducerCount)
        source.stop()
        frames.forEach { it.close() }
        val afterStop = mutableListOf<CameraFrame>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { source.frames.collect { afterStop += it } }
        runCurrent()
        assertTrue(afterStop.isEmpty())
    }
}
