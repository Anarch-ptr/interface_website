package com.filamentvision.backend.input.runtime

import com.filamentvision.backend.input.profile.*
import com.filamentvision.backend.persistence.sqlite.*
import com.filamentvision.core.input.ImageInputState
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.coroutines.CoroutineContext

class RaspberryPiUdpInputConfigurationServiceTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun saveDoesNotSwapAndApplySwapsBeforePersistenceWithIdempotentRetry() = runBlocking {
        val f = fixture()
        try {
            val next = f.initial.copy(envelope = DatagramEnvelopeProfile("bb02", "0e0b"))
            val saved = f.service.save(2, next)
            assertEquals(3L, saved.revision)
            assertEquals("BB 02", saved.profile.envelope.headerHex)
            f.assertApplied(2, f.initial)
            f.beforeMark = {
                assertEquals(3L, f.router.appliedRevision)
                assertEquals(saved.profile, f.router.effectiveProfile)
                assertEquals(saved.profile, f.source.effectiveProfile)
                assertEquals(2L, f.repository.state().applied.revision)
            }
            assertEquals(RaspberryPiUdpApplyOutcome.APPLIED, f.service.apply(3).outcome)
            f.assertApplied(3, saved.profile)
            f.beforeMark = { error("Idempotent Apply must not persist again") }
            f.service.save(3, next.copy(listenPort = 46011))
            assertEquals(RaspberryPiUdpApplyOutcome.APPLIED, f.service.apply(3).outcome)
            assertEquals(1, f.channels.size)
            assertEquals(1L, f.source.totalProducerStarts)
        } finally { f.close() }
    }

    @Test fun applyAndSaveAcrossServiceInstancesShareTheDeviceSerializationBoundary() = runBlocking {
        val f = fixture()
        try {
            val next = f.service.save(2, f.initial.copy(listenPort = 46011))
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            f.beforeMark = { entered.complete(Unit); withTimeout(3000) { release.await() } }
            val applying = async { f.service.apply(next.revision) }
            withTimeout(3000) { entered.await() }
            val secondService = RaspberryPiUdpInputConfigurationService(f.repositoryWithHook, f.router)
            val saving = async(start = CoroutineStart.UNDISPATCHED) {
                secondService.save(3, next.profile.copy(listenPort = 46012))
            }
            assertFalse(saving.isCompleted)
            release.complete(Unit)
            assertEquals(RaspberryPiUdpApplyOutcome.APPLIED, applying.await().outcome)
            assertEquals(4L, saving.await().revision)
            f.assertApplied(3, next.profile)
            assertEquals(4L, f.repository.state().saved.revision)
            assertEquals(RaspberryPiUdpApplyOutcome.CONFLICT, f.service.apply(2).outcome)
        } finally { f.close() }
    }

    @Test fun externalSaveWinsCasAfterRuntimeSwapAndRestoresOldProcessorSocketAndRevision() = runBlocking {
        val f = fixture()
        try {
            f.packet(task16Wire(7, index = 0, count = 2, payload = byteArrayOf(7), total = 2))
            val next = f.service.save(2, f.initial.copy(listenPort = 46011))
            f.beforeMark = { f.repository.save(3, next.profile.copy(listenPort = 46012)) }
            assertEquals(RaspberryPiUdpApplyOutcome.CONFLICT, f.service.apply(3).outcome)
            f.assertApplied(2, f.initial)
            assertEquals(listOf(46010, 46011, 46010), f.boundPorts())
            f.packet(task16Wire(7, index = 1, count = 2, payload = byteArrayOf(8), total = 2))
            assertEquals(1L, f.source.udpDiagnostics().framesCompleted)
            f.assertSingleOwner()
        } finally { f.close() }
    }

    @Test fun persistenceExceptionCompensatesWithoutLosingAdmittedFrameOrDecodedPermit() = runBlocking {
        val f = fixture()
        try {
            f.packet(task16Wire(1, payload = byteArrayOf(7, 8)))
            val admitted = f.take()
            try {
                f.service.save(2, f.initial.copy(listenPort = 46011))
                val failure = IllegalStateException("storage unavailable")
                f.beforeMark = { throw failure }
                val thrown = task16Failure { f.service.apply(3) }
                assertEquals(IllegalStateException::class.java, thrown.javaClass)
                assertSame(failure, generateSequence(thrown) { it.cause }.last())
                f.assertApplied(2, f.initial)
                f.packet(task16Wire(2, payload = byteArrayOf(9, 10)))
                assertEquals(0, f.source.pendingInputCount)
                assertEquals(1L, f.source.udpDiagnostics().processingBackpressureDrops)
                assertEquals(0xff070707.toInt(), admitted.frame.pixels[0])
            } finally { admitted.frame.close() }
            f.packet(task16Wire(3, payload = byteArrayOf(11, 12)))
            f.take().frame.close()
            f.assertSingleOwner()
        } finally { f.close() }
    }

    @Test fun cancellationDuringPersistenceStillCompletesRollback() = runBlocking {
        val f = fixture()
        try {
            f.service.save(2, f.initial.copy(listenPort = 46011))
            f.beforeMark = { throw CancellationException("cancelled storage call") }
            assertTrue(task16Failure { f.service.apply(3) } is CancellationException)
            f.assertApplied(2, f.initial)
            f.assertSingleOwner()
        } finally { f.close() }
    }

    @Test fun compensationFailureIsFatalAndCannotBeRetriedAsSuccess() = runBlocking {
        val f = fixture()
        try {
            f.service.save(2, f.initial.copy(listenPort = 46011))
            val persistence = IllegalStateException("storage unavailable")
            f.beforeMark = { f.failNextBind = true; throw persistence }
            val fatal = task16Failure { f.service.apply(3) }
            assertTrue(fatal is RaspberryPiUdpConsistencyException)
            assertSame(persistence, fatal.cause)
            assertEquals(1, fatal.suppressed.size)
            assertEquals(2L, f.repository.state().applied.revision)
            assertEquals(ImageInputState.ERROR, f.source.state.value)
            assertEquals(0, f.source.activeReceiverCount)
            assertTrue(task16Failure { f.service.apply(3) } is RaspberryPiUdpConsistencyException)
            assertTrue(task16Failure { f.service.save(3, f.initial) } is RaspberryPiUdpConsistencyException)
        } finally { f.close() }
    }

    @Test fun callerCancellationAfterSuccessfulCasCannotRevertOnlyTheRuntimeRevision() = runBlocking {
        val f = fixture()
        try {
            val saved = f.service.save(2, f.initial.copy(listenPort = 46011))
            lateinit var applying: Job
            f.beforeMark = { applying.cancel() }
            applying = launch(start = CoroutineStart.LAZY) { f.service.apply(3) }
            applying.start(); applying.join()
            assertTrue(applying.isCancelled)
            f.assertApplied(3, saved.profile)
            f.assertSingleOwner()
        } finally { f.close() }
    }

    @Test fun staleApplyAndInvalidSaveLeaveAllThreeTruthsUntouched() = runBlocking {
        val f = fixture()
        try {
            f.service.save(2, f.initial.copy(listenPort = 46011))
            f.service.save(3, f.initial.copy(listenPort = 46012))
            assertEquals(RaspberryPiUdpApplyOutcome.CONFLICT, f.service.apply(3).outcome)
            assertTrue(task16Failure { f.service.save(4, f.initial.copy(listenPort = 0)) } is IllegalArgumentException)
            f.assertApplied(2, f.initial)
            assertEquals(4L, f.repository.state().saved.revision)
            assertEquals(1, f.channels.size)
        } finally { f.close() }
    }

    @Test fun parserOnlyCasConflictRebindsReceiverThatFailedWhilePersistenceWasPending() = runBlocking {
        val f = fixture()
        try {
            f.packet(task16Wire(7, index = 0, count = 2, payload = byteArrayOf(7), total = 2))
            val next = f.service.save(2, f.initial.copy(transportLimits = f.initial.transportLimits.copy(maxChunkPayloadBytes = 9)))
            f.beforeMark = {
                f.failCurrentReceiver()
                f.repository.save(3, next.profile.copy(listenPort = 46012))
            }
            assertEquals(RaspberryPiUdpApplyOutcome.CONFLICT, f.service.apply(3).outcome)
            f.assertApplied(2, f.initial)
            f.assertSingleOwner()
            assertEquals(listOf(46010, 46010), f.boundPorts())
            assertEquals(UdpOperationalStatus.UDP_READY, f.source.operationalStatus().status)
            assertNull(f.source.diagnostics.value.lastError)
            f.packet(task16Wire(7, index = 1, count = 2, payload = byteArrayOf(8), total = 2))
            val restored = f.take()
            assertEquals(0xff070707.toInt(), restored.frame.pixels[0]); restored.frame.close()
            assertEquals(1L, f.source.totalProducerStarts)
        } finally { f.close() }
    }

    @Test fun parserOnlyPersistenceExceptionRebindsReceiverThatFailedWhilePersistenceWasPending() = runBlocking {
        val f = fixture()
        try {
            f.service.save(2, f.initial.copy(envelope = DatagramEnvelopeProfile("BB 02", "0E 0B")))
            val failure = IllegalStateException("storage unavailable after receiver failure")
            f.beforeMark = { f.failCurrentReceiver(); throw failure }
            val thrown = task16Failure { f.service.apply(3) }
            assertEquals(IllegalStateException::class.java, thrown.javaClass)
            assertSame(failure, generateSequence(thrown) { it.cause }.last())
            f.assertApplied(2, f.initial)
            f.assertSingleOwner()
            assertEquals(listOf(46010, 46010), f.boundPorts())
            assertEquals(UdpOperationalStatus.UDP_READY, f.source.operationalStatus().status)
            assertNull(f.source.diagnostics.value.lastError)
            f.packet(task16Wire(1, payload = byteArrayOf(7, 8)))
            f.take().frame.close()
        } finally { f.close() }
    }

    @Test fun failedRebindAfterParserOnlyReceiverFailureLatchesFatalConsistencyError() = runBlocking {
        val f = fixture()
        try {
            val next = f.service.save(2, f.initial.copy(transportLimits = f.initial.transportLimits.copy(maxChunkPayloadBytes = 9)))
            f.beforeMark = {
                f.failCurrentReceiver()
                f.failNextBind = true
                f.repository.save(3, next.profile.copy(listenPort = 46012))
            }
            val fatal = task16Failure { f.service.apply(3) }
            assertTrue(fatal is RaspberryPiUdpConsistencyException)
            assertEquals(2L, f.repository.state().applied.revision)
            assertEquals(2L, f.router.appliedRevision)
            assertEquals(ImageInputState.ERROR, f.source.state.value)
            assertEquals("UDP_APPLY_CONSISTENCY_FAILURE", f.source.diagnostics.value.lastError)
            assertEquals(0, f.source.activeReceiverCount)
            assertTrue(task16Failure { f.source.start() } is RaspberryPiUdpConsistencyException)
            assertTrue(task16Failure { f.service.apply(3) } is RaspberryPiUdpConsistencyException)
        } finally { f.close() }
    }

    @Test fun immediateReceiveFailureDuringCompensationCannotBeOverwrittenWithOldRunningState() = runBlocking {
        for (parserOnly in listOf(true, false)) {
            val dispatcher = ControlledDispatch()
            val f = fixture(dispatcher)
            try {
                val candidate = if (parserOnly) f.initial.copy(envelope = DatagramEnvelopeProfile("BB 02", "0E 0B"))
                    else f.initial.copy(listenPort = 46011)
                f.service.save(2, candidate)
                val persistence = IllegalStateException("storage unavailable")
                f.beforeMark = {
                    if (parserOnly) f.failCurrentReceiver()
                    f.onOpen = { socket ->
                        socket.failReceive()
                        // The next receiver launch runs to its injected failure before start returns.
                        dispatcher.runInline = true
                    }
                    throw persistence
                }
                val fatal = task16Failure { f.service.apply(3) }
                assertTrue("parserOnly=$parserOnly: $fatal", fatal is RaspberryPiUdpConsistencyException)
                assertSame(persistence, fatal.cause)
                assertEquals(1, fatal.suppressed.size)
                assertEquals(2L, f.repository.state().applied.revision)
                assertEquals(ImageInputState.ERROR, f.source.state.value)
                assertEquals("UDP_APPLY_CONSISTENCY_FAILURE", f.source.diagnostics.value.lastError)
                assertEquals(0, f.source.activeReceiverCount)
                assertEquals(0, f.channels.count { it.isOpen })
                assertTrue(task16Failure { f.service.save(3, f.initial) } is RaspberryPiUdpConsistencyException)
            } finally { f.close() }
        }
    }

    private suspend fun fixture(dispatcher: CoroutineDispatcher = Dispatchers.IO): ServiceFixture {
        val database = SqliteDatabase(temporary.newFile("input-${System.nanoTime()}.db").toPath())
        val repository = SqliteRaspberryPiUdpInputProfileRepository(database)
        val initial = task16Profile()
        repository.save(1, initial)
        check(repository.markApplied(1, 2))
        return ServiceFixture(database, repository, initial, dispatcher).also { it.source.start() }
    }

    private class ServiceFixture(
        private val database: SqliteDatabase,
        val repository: RaspberryPiUdpInputProfileRepository,
        val initial: RaspberryPiUdpInputProfile,
        dispatcher: CoroutineDispatcher,
    ) : Task16SourceHarness(initial, dispatcher) {
        var beforeMark: suspend () -> Unit = {}
        val repositoryWithHook = object : RaspberryPiUdpInputProfileRepository by repository {
            override suspend fun markApplied(expectedAppliedRevision: Long, revision: Long): Boolean {
                beforeMark()
                return repository.markApplied(expectedAppliedRevision, revision)
            }
        }
        val router = RaspberryPiUdpInputRouter(2, source)
        val service = RaspberryPiUdpInputConfigurationService(repositoryWithHook, router)
        suspend fun assertApplied(revision: Long, profile: RaspberryPiUdpInputProfile) {
            assertEquals(revision, router.appliedRevision)
            assertEquals(profile, router.effectiveProfile)
            assertEquals(profile, source.effectiveProfile)
            assertEquals(ProfileRevision(revision, profile), repository.state().applied)
            assertEquals(profile.listenPort, boundPorts().last())
            assertEquals(38 + profile.transportLimits.maxChunkPayloadBytes, source.receiverCapacity!!.acceptedLimit)
            assertEquals(ImageInputState.RUNNING, source.state.value)
        }
        suspend fun close() { source.stop(); database.close() }
        suspend fun failCurrentReceiver() {
            channels.last().failReceive()
            withTimeout(3000) { while (source.state.value != ImageInputState.ERROR || source.activeReceiverCount != 0) delay(2) }
        }
    }

    private class ControlledDispatch : CoroutineDispatcher() {
        @Volatile var runInline = false
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (runInline) {
                block.run()
            } else Dispatchers.IO.dispatch(context, block)
        }
    }
}
