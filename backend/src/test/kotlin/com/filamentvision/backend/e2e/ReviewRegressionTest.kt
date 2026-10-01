package com.filamentvision.backend.e2e

import com.filamentvision.backend.*
import com.filamentvision.core.alarm.AlarmTransition
import com.filamentvision.core.event.*
import com.filamentvision.core.model.OfficialMeasurement
import com.filamentvision.core.repository.*
import com.filamentvision.core.runtime.*
import com.filamentvision.backend.simulator.SimulationScenario
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class, kotlinx.coroutines.InternalForInheritanceCoroutinesApi::class)
@Suppress("OPT_IN_TO_INHERITANCE") // Test-owned forwarding flow only; no production subclass.
class ReviewRegressionTest {
    @get:Rule val temporary = TemporaryFolder()
    private val device = "simulator-1"
    // Test-owned interception opens otherwise instruction-sized race windows. Production
    // runtime, simulator, persistence, event hub and facade remain the actual composition.
    private fun field(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).also { it.isAccessible = true }
    private fun replay(c: BackendComposition) = (c.events.replayAfter(device, 0) as EventReplay.Available).events

    @Test fun snapshotDoesNotExposeRuntimeAdvanceBeforeItsStatePublication() = runTest {
        val d = StandardTestDispatcher(testScheduler)
        val c = BackendComposition.open(temporary.root.toPath().resolve("ahead.db"), temporary.root.toPath().resolve("objects"), d, d)
        try {
            val before = c.application.state(device)
            val starting = async(start = CoroutineStart.UNDISPATCHED) { c.runtime.start(StartMonitoringCommand("start")) }
            assertEquals(MonitoringLifecycle.STARTING, c.runtime.state.value.lifecycle)
            assertEquals(before, c.application.state(device))
            runCurrent(); starting.await()
            val after = c.application.state(device)
            val published = replay(c).last { it.event is DomainEvent.State }
            assertEquals(published.sequence, after.eventSequence)
            assertEquals((published.event as DomainEvent.State).state, after.monitoring)
        } finally { c.close() }
    }

    @Test fun finalizedEventCannotBeSkippedBySnapshotWhileRuntimeStillStopping() = runTest {
        val ioTasks = java.util.ArrayDeque<Runnable>()
        val io = object : CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { ioTasks.add(block) }
        }
        fun pump() {
            repeat(100) { runCurrent(); if (ioTasks.isEmpty()) return; ioTasks.removeFirst().run() }
            error("IO did not quiesce")
        }
        val d = StandardTestDispatcher(testScheduler)
        val opening = async { BackendComposition.open(temporary.root.toPath().resolve("final.db"), temporary.root.toPath().resolve("objects"), d, io) }
        pump(); val c = opening.await()
        val owner = field(c.runtime, "ownership").get(c.runtime)
        val ownership = field(owner, "mutex").get(owner) as Mutex
        var held = false
        try {
            val starting = async { c.application.start(device, "start", null, null) }
            pump(); val session = starting.await().session!!.id
            val stopping = async { c.application.stop(device, "stop") }
            runCurrent()
            repeat(100) {
                if (c.repository.session(session)!!.finalization == null) {
                    check(ioTasks.isNotEmpty()); ioTasks.removeFirst().run()
                    if (c.repository.session(session)!!.finalization == null) runCurrent()
                }
            }
            assertNotNull(c.repository.session(session)!!.finalization)
            ownership.lock(); held = true
            runCurrent() // final event published; authoritative IDLE transition is held back.
            val final = replay(c).single { it.event is DomainEvent.SessionFinalized }
            assertEquals(MonitoringLifecycle.STOPPING, c.runtime.state.value.lifecycle)
            val snapshot = c.application.state(device)
            assertTrue("STOPPING snapshot must replay finalization", snapshot.eventSequence < final.sequence)
            assertTrue((c.events.replayAfter(device, snapshot.eventSequence) as EventReplay.Available).events.any { it == final })
            ownership.unlock(); held = false; pump(); stopping.await()
            val stopped = c.application.state(device)
            assertEquals(MonitoringLifecycle.IDLE, stopped.monitoring.lifecycle)
            assertTrue(stopped.eventSequence > final.sequence)
        } finally {
            if (held) ownership.unlock()
            val closing = async { c.close() }; pump(); closing.await()
        }
    }

    @Test fun duplicateStartReturnsOriginalResultBeforeChangedRevisionAndTargetValidation() = runTest {
        val d = StandardTestDispatcher(testScheduler)
        val c = BackendComposition.open(temporary.root.toPath().resolve("dedup.db"), temporary.root.toPath().resolve("objects"), d, d)
        try {
            val first = c.application.start(device, "start", 1.75, 1)
            c.application.stop(device, "stop")
            c.application.saveAndApply(device, "apply", BackendComposition.defaultConfiguration().copy(targetDiameter = 2.0))
            val sequence = c.events.metrics(device).published
            assertEquals(first, c.application.start(device, "start", 1.75, 1))
            assertEquals(sequence, c.events.metrics(device).published)
            assertEquals(1L, c.source.totalProducerStarts)
            assertNull(c.runtime.state.value.activeSession)
        } finally { c.close() }
    }

    @Test fun latestReadStraddlingStopAndApplyRetainsTheSamplesOriginalRevision() = runTest {
        val d = StandardTestDispatcher(testScheduler)
        val c = BackendComposition.open(temporary.root.toPath().resolve("latest.db"), temporary.root.toPath().resolve("objects"), d, d)
        val reader = Executors.newSingleThreadExecutor { Thread(it, "latest-reader") }.asCoroutineDispatcher()
        val captured = CountDownLatch(1); val release = CountDownLatch(1)
        val stateField = field(c.runtime, "state")
        val original = c.runtime.state
        try {
            val session = c.application.start(device, "start", null, null).session!!.id
            runCurrent()
            stateField.set(c.runtime, object : StateFlow<MonitoringState> by original {
                override val value: MonitoringState get() {
                    val value = original.value
                    if (Thread.currentThread().name.startsWith("latest-reader")) {
                        captured.countDown(); check(release.await(10, TimeUnit.SECONDS))
                    }
                    return value
                }
            })
            val latest = async(reader) { c.application.latest(device)!! }
            assertTrue(captured.await(5, TimeUnit.SECONDS))
            c.application.stop(device, "stop")
            c.application.saveAndApply(device, "apply", BackendComposition.defaultConfiguration().copy(targetDiameter = 2.0))
            release.countDown()
            val record = latest.await()
            assertEquals(session, record.sessionId)
            assertEquals(1L, record.appliedRevision)
            assertEquals(2L, c.revisions.appliedRevision)
            assertEquals(1L, c.application.latest(device)!!.appliedRevision)
        } finally { release.countDown(); stateField.set(c.runtime, original); reader.close(); c.close() }
    }

    @Test fun committedMeasurementWithLostAcknowledgementRetriesOnceWithoutRetainedReservation() = ambiguousAcknowledgement(false)
    @Test fun committedAlarmWithLostAcknowledgementRetriesOnceWithoutRetainedReservation() = ambiguousAcknowledgement(true)

    private fun ambiguousAcknowledgement(alarmFailure: Boolean) = runTest {
        val d = StandardTestDispatcher(testScheduler)
        val c = BackendComposition.open(temporary.root.toPath().resolve("ack.db"), temporary.root.toPath().resolve("objects"), d, d)
        val persistenceField = field(c.runtime, "persistence")
        val delegate = persistenceField.get(c.runtime) as RuntimePersistence
        var failed = false
        try {
            c.application.saveAndApply(device, "config", BackendComposition.defaultConfiguration().copy(writeBatchSize = 1))
            // The first A frame emits one accepted sample before failure. Width 36 gives WARNING.
            c.source.setScenario(SimulationScenario.HIGH_DIAMETER)
            persistenceField.set(c.runtime, object : RuntimePersistence by delegate {
                override suspend fun writeMeasurements(session: RuntimeSession, batch: List<OfficialMeasurement>) {
                    if (!alarmFailure && !failed) {
                        failed = true
                        c.repository.appendMeasurements(batch.mapIndexed { i, m -> MeasurementRecord(c.recordId(session.id, "m", i.toLong()), session.id, m, 2) })
                        throw RepositoryException(RepositoryFailure.STORAGE, "Commit acknowledgement lost")
                    }
                    delegate.writeMeasurements(session, batch)
                }
                override suspend fun writeAlarms(session: RuntimeSession, batch: List<AlarmTransition>) {
                    if (alarmFailure && !failed) {
                        failed = true
                        c.repository.appendAlarms(batch.mapIndexed { i, a -> AlarmEventRecord(c.recordId(session.id, "a", i.toLong()), session.id, a) })
                        throw RepositoryException(RepositoryFailure.STORAGE, "Commit acknowledgement lost")
                    }
                    delegate.writeAlarms(session, batch)
                }
            })
            val session = c.application.start(device, "start", null, null).session!!.id
            runCurrent()
            assertTrue(failed)
            c.runtime.state.first { it.lifecycle == MonitoringLifecycle.ERROR }
            assertNull("A successful exact retry must release ownership: ${c.runtime.state.value}", c.runtime.state.value.activeSession)
            assertNotNull(c.repository.session(session)!!.finalization)
            assertEquals(1, c.repository.history(session).items.size)
            assertEquals(1, c.repository.alarms(session).size)
            assertEquals(1, replay(c).count { it.event is DomainEvent.Measurement })
            assertEquals(1, replay(c).count { it.event is DomainEvent.Alarm })
            assertEquals(1, replay(c).count { it.event is DomainEvent.SessionFinalized })
            assertEquals(0 to 0, c.runtime.queuedCounts())
            assertEquals(0, c.source.activeProducerCount)
        } finally { runCatching { c.close() } }
    }
}
