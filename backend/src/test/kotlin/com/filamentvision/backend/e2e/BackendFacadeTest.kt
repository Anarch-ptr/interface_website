package com.filamentvision.backend.e2e

import com.filamentvision.backend.BackendComposition
import com.filamentvision.backend.application.*
import com.filamentvision.backend.simulator.fixtures.*
import com.filamentvision.core.config.ConfigurationApplyOutcome
import com.filamentvision.core.runtime.MonitoringLifecycle
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class BackendFacadeTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun cancellationAfterConfigurationCommitCannotLeaveRuntimeRevisionHalfApplied() = runTest {
        val tasks = java.util.ArrayDeque<Runnable>()
        val io = object : CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { tasks.add(block) }
        }
        fun pump() {
            repeat(100) {
                runCurrent()
                if (tasks.isEmpty()) return
                tasks.removeFirst().run()
            }
            error("IO pump did not quiesce")
        }
        val d = StandardTestDispatcher(testScheduler)
        val opened = async { BackendComposition.open(temporary.root.toPath().resolve("cancel.db"), temporary.root.toPath().resolve("objects"), SimulatorBackendInputSourceFactory, d, io) }
        pump(); val c = opened.await()
        try {
            val command = async { c.application.saveAndApply("filamentvision-1", "configuration", BackendComposition.defaultConfiguration().copy(targetDiameter = 2.0)) }
            runCurrent()
            tasks.removeFirst().run() // SQLite saved/applied acknowledgement, before caller resumes.
            command.cancel(); pump()
            assertEquals(2L, c.revisions.appliedRevision)
            val retry = async { c.application.saveAndApply("filamentvision-1", "configuration", BackendComposition.defaultConfiguration()) }
            pump()
            assertEquals(2L, retry.await().revisions.appliedRevision)
            assertEquals(2.0, c.applied.targetDiameter, 0.0)
        } finally { val closing = async { c.close() }; pump(); closing.await() }
    }
    @Test fun liveSafeApplyStillRequiresStopSoBufferedSamplesRetainExactAppliedRevision() = runTest {
        val d = StandardTestDispatcher(testScheduler)
        val c = BackendComposition.open(temporary.root.toPath().resolve("provenance.db"), temporary.root.toPath().resolve("objects"), SimulatorBackendInputSourceFactory, d, d)
        try {
            val app = c.application
            val session = app.start("filamentvision-1", "start", null, null).session!!.id
            runCurrent()
            assertEquals(1 to 0, c.runtime.queuedCounts())
            val updated = BackendComposition.defaultConfiguration().copy(previewFramesPerSecond = 4)
            val result = app.saveAndApply("filamentvision-1", "live", updated)
            assertEquals(ConfigurationApplyOutcome.REQUIRES_STOP, result.outcome)
            assertEquals(1L, result.revisions.appliedRevision)
            app.stop("filamentvision-1", "stop")
            assertTrue(app.history(session, PageQuery()).items.all { it.appliedRevision == 1L })
            assertEquals(ConfigurationApplyOutcome.APPLIED, app.apply("filamentvision-1", "stopped", 2, 2).outcome)
        } finally { c.close() }
    }
    @Test fun unavailableSnapshotStoreCannotPreventInterruptedSessionFinalization() = runTest {
        val d = StandardTestDispatcher(testScheduler)
        val root = temporary.root.toPath().resolve("unavailable-objects")
        val c = BackendComposition.open(temporary.root.toPath().resolve("unavailable.db"), root, SimulatorBackendInputSourceFactory, d, d)
        try {
            val session = c.application.start("filamentvision-1", "start", null, null).session!!.id
            runCurrent(); java.nio.file.Files.delete(root)
            c.source.setScenario(SimulationScenario.CONNECTION_FAILURE)
            advanceTimeBy(125); runCurrent()
            c.runtime.state.first { it.lifecycle == MonitoringLifecycle.ERROR }
            assertNull(c.runtime.state.value.activeSession)
            assertNotNull(c.application.session(session).finalization)
            assertEquals(0 to 0, c.runtime.queuedCounts())
        } finally { runCatching { c.close() } }
    }
    @Test fun revisionsDraftsAndNextSessionProcessingUseSingleRuntime() = runTest {
        val d = StandardTestDispatcher(testScheduler)
        val c = BackendComposition.open(temporary.root.toPath().resolve("config.db"), temporary.root.toPath().resolve("objects"), SimulatorBackendInputSourceFactory, d, d)
        try {
            val app = c.application; val identity = c.runtime
            val old = app.configuration("filamentvision-1").saved!!.configuration
            val updated = old.copy(targetDiameter = 3.5, vision = old.vision.copy(calibration = old.vision.calibration.copy(mmPerPixel = .1)))
            app.start("filamentvision-1", "start", null, null); runCurrent()
            val saved = app.save("filamentvision-1", "save", updated)
            assertEquals(saved, app.save("filamentvision-1", "save", old))
            assertEquals(2L, saved.revisions.savedRevision); assertEquals(1L, saved.revisions.appliedRevision)
            assertEquals(ConfigurationApplyOutcome.REQUIRES_STOP, app.apply("filamentvision-1", "active-apply", 2, 2).outcome)
            app.stop("filamentvision-1", "stop")
            assertEquals(ConfigurationApplyOutcome.APPLIED, app.apply("filamentvision-1", "stopped-apply", 2, 2).outcome)
            app.start("filamentvision-1", "new-start", 3.5, 2); runCurrent()
            assertEquals(3.5, app.latest("filamentvision-1")!!.measurement.diameterMm, 1e-9)
            assertSame(identity, c.runtime); assertEquals(1, c.source.maximumActiveProducerCount)
            val draft = app.createDraft("filamentvision-1", "draft", "camera", old.vision)
            assertEquals(draft, app.createDraft("filamentvision-1", "draft", "camera", old.vision))
            assertEquals(2L, app.updateDraft("filamentvision-1", draft.id, "update", 1, updated.vision).revision)
            app.deleteDraft("filamentvision-1", draft.id, "delete"); app.deleteDraft("filamentvision-1", draft.id, "delete")
            app.stop("filamentvision-1", "new-stop")
            assertEquals(2, app.sessions(PageQuery()).items.size)
        } finally { c.close() }
    }
    @Test fun oversizedOptionalSnapshotCannotBlockDurableFailureFinalization() = runTest {
        val d = StandardTestDispatcher(testScheduler)
        val c = BackendComposition.open(temporary.root.toPath().resolve("large-snapshot.db"), temporary.root.toPath().resolve("large-objects"),
            SimulatorBackendInputSourceFactory, d, d, { 1000 + testScheduler.currentTime }, { testScheduler.currentTime })
        try {
            val bytes = ByteArray(1280 * 720 * 4)
            java.util.Random(17).nextBytes(bytes)
            for (i in bytes.indices step 4) bytes[i] = 255.toByte()
            val preview = com.filamentvision.core.preview.PreviewFrame(c.previewKeys.single(), 1000, 0,
                1280, 720, com.filamentvision.core.preview.PreviewFormat.ARGB32, bytes)
            assertTrue(com.filamentvision.backend.preview.PngPreviewEncoder().encode(preview).bytes.size > 1024 * 1024)
            val session = c.application.start(c.deviceId, "start", null, null).session!!.id
            runCurrent()
            c.previews.publish(preview)
            c.source.setScenario(SimulationScenario.CONNECTION_FAILURE)
            advanceTimeBy(125); runCurrent()
            c.runtime.state.first { it.lifecycle == MonitoringLifecycle.ERROR }
            assertEquals(com.filamentvision.core.runtime.FinalizationReason.INTERRUPTED, c.application.session(session).finalization!!.reason)
            val error = c.application.errors(session).single()
            assertTrue(c.application.snapshots(error.id).isEmpty())
            assertEquals(0L, c.snapshotCache.state().totalBytes)
            assertEquals(0 to 0, c.runtime.queuedCounts())
            val replay = c.events.replayAfter(c.deviceId, 0) as com.filamentvision.core.event.EventReplay.Available
            assertTrue(replay.events.any { it.event is com.filamentvision.core.event.DomainEvent.Error })
            assertTrue(replay.events.any { it.event is com.filamentvision.core.event.DomainEvent.SessionFinalized })
        } finally { c.close() }
    }

    @Test fun repeatedFailuresRespectSnapshotCooldownAndExposeOnlyStoredObjects() = runTest {
        val d = StandardTestDispatcher(testScheduler)
        val c = BackendComposition.open(temporary.root.toPath().resolve("snapshots.db"), temporary.root.toPath().resolve("objects"),
            SimulatorBackendInputSourceFactory, d, d, { 1000 + testScheduler.currentTime }, { testScheduler.currentTime })
        try {
            repeat(2) { attempt ->
                c.source.setScenario(SimulationScenario.NORMAL)
                val session = c.application.start("filamentvision-1", "start-$attempt", null, null).session!!.id
                runCurrent(); c.source.setScenario(SimulationScenario.CONNECTION_FAILURE)
                advanceTimeBy(125); runCurrent()
                c.runtime.state.first { it.lifecycle == MonitoringLifecycle.ERROR }
                val error = c.application.errors(session).single()
                val snapshots = c.application.snapshots(error.id)
                assertEquals(if (attempt == 0) 1 else 0, snapshots.size)
                snapshots.forEach {
                    val content = c.application.snapshot(it.id)
                    assertEquals("image/png", content.mediaType)
                    assertEquals(0x89.toByte(), content.bytes.first())
                    assertNotNull(it.sourceSequence)
                }
            }
            assertEquals(1, c.snapshotCache.state().entries)
            assertTrue(c.snapshotCache.state().totalBytes <= 1024 * 1024)
        } finally { c.close() }
        assertEquals(0, c.snapshotCache.state().entries)
        assertEquals(0L, c.snapshotCache.state().totalBytes)
    }
}
