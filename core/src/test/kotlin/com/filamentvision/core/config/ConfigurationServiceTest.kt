package com.filamentvision.core.config

import com.filamentvision.core.repository.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ConfigurationServiceTest {
    private val repository = MemoryConfigurationRepository()
    private val runtime = GuardedRuntime()
    private val service = ConfigurationService(repository, runtime)

    @Test fun saveDoesNotAlterAppliedRevisionOrRuntime() {
        service.saveAndApply(MonitoringConfiguration())
        val saved = service.save(MonitoringConfiguration(targetDiameter = 2.0))
        assertEquals(2L, saved.revision)
        assertEquals(ConfigurationRevisions(2, 1), repository.revisions())
        assertEquals(1.75, runtime.current!!.targetDiameter, 0.0)
    }
    @Test fun appliesExactlySelectedSavedRevision() {
        val first = service.save(MonitoringConfiguration(targetDiameter = 1.5))
        service.save(MonitoringConfiguration(targetDiameter = 2.0))
        val applied = service.apply(first.revision, 2)
        assertEquals(first, applied.saved)
        assertEquals(ConfigurationRevisions(2, 1), repository.revisions())
        assertEquals(1.5, runtime.current!!.targetDiameter, 0.0)
    }
    @Test fun staleExpectedRevisionRejectsWithoutRuntimeChange() {
        service.saveAndApply(MonitoringConfiguration())
        service.save(MonitoringConfiguration(targetDiameter = 2.0))
        val error = assertThrows(RepositoryException::class.java) { service.apply(2, 1) }
        assertEquals(RepositoryFailure.CONFLICT, error.failure)
        assertEquals(ConfigurationRevisions(2, 1), repository.revisions())
        assertEquals(1.75, runtime.current!!.targetDiameter, 0.0)
    }
    @Test fun activeRestartSensitiveApplyRequiresStopWithoutChangingState() {
        service.saveAndApply(MonitoringConfiguration())
        runtime.active = true
        val saved = service.save(MonitoringConfiguration(cameraA = VisionConfiguration(geometry = ImageGeometry(90))))
        assertEquals(ConfigurationApplyOutcome.REQUIRES_STOP, service.apply(saved.revision, saved.revision).outcome)
        assertEquals(ConfigurationRevisions(2, 1), repository.revisions())
        assertTrue(runtime.active)
        assertEquals(0, runtime.current!!.cameraA.geometry.rotationDegrees)
    }
    @Test fun runtimeSafeFieldsApplyWhileActive() {
        service.saveAndApply(MonitoringConfiguration())
        runtime.active = true
        val result = service.saveAndApply(MonitoringConfiguration(previewFramesPerSecond = 4, publicationIntervalMillis = 250))
        assertEquals(ConfigurationApplyOutcome.APPLIED, result.outcome)
        assertEquals(4, runtime.current!!.previewFramesPerSecond)
        assertEquals(ConfigurationRevisions(2, 2), repository.revisions())
    }
    @Test fun concurrentSaveCannotCauseSaveAndApplyToUseAnotherRevision() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blockingRuntime = object : ConfigurationRuntime, ConfigurationRuntimeState {
            var applied: MonitoringConfiguration? = null
            override val monitoringActive = false
            override val configuration get() = applied
            override fun publishConfiguration(configuration: MonitoringConfiguration) { applied = configuration }
            override fun <T> withConfigurationGuard(action: (ConfigurationRuntimeState) -> T): T {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                return action(this)
            }
        }
        val other = ConfigurationService(repository, blockingRuntime)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val pending = executor.submit<ConfigurationApplyResult> { other.saveAndApply(MonitoringConfiguration(targetDiameter = 1.5)) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val second = service.save(MonitoringConfiguration(targetDiameter = 2.0))
            assertEquals(2L, second.revision)
            release.countDown()
            val error = assertThrows(java.util.concurrent.ExecutionException::class.java) { pending.get(5, TimeUnit.SECONDS) }
            assertEquals(RepositoryFailure.CONFLICT, (error.cause as RepositoryException).failure)
            assertNull(blockingRuntime.applied)
            assertEquals(ConfigurationRevisions(2, null), repository.revisions())
            assertEquals(1.5, repository.configuration(1)!!.configuration.targetDiameter, 0.0)
        } finally { release.countDown(); executor.shutdownNow() }
    }
    @Test fun unknownRevisionAndInvalidConfigurationAreTypedFailures() {
        assertEquals(RepositoryFailure.NOT_FOUND, assertThrows(RepositoryException::class.java) { service.apply(99, 99) }.failure)
        assertEquals(RepositoryFailure.INVALID, assertThrows(RepositoryException::class.java) { service.save(MonitoringConfiguration(targetDiameter = Double.NaN)) }.failure)
        assertEquals(ConfigurationRevisions(), repository.revisions())
    }
}

private class GuardedRuntime : ConfigurationRuntime, ConfigurationRuntimeState {
    var current: MonitoringConfiguration? = null
    var active = false
    override val monitoringActive get() = active
    override val configuration get() = current
    override fun publishConfiguration(configuration: MonitoringConfiguration) { current = configuration }
    @Synchronized override fun <T> withConfigurationGuard(action: (ConfigurationRuntimeState) -> T): T = action(this)
}
private class MemoryConfigurationRepository : ConfigurationRepository {
    private val values = linkedMapOf<Long, ConfigurationRevision>()
    private var applied: Long? = null
    @Synchronized override fun saveConfiguration(configuration: MonitoringConfiguration): ConfigurationRevision {
        val value = ConfigurationRevision((values.keys.lastOrNull() ?: 0) + 1, configuration)
        values[value.revision] = value
        return value
    }
    @Synchronized override fun configuration(revision: Long) = values[revision]
    @Synchronized override fun revisions() = ConfigurationRevisions(values.keys.lastOrNull(), applied)
    @Synchronized override fun markApplied(revision: Long, expectedSavedRevision: Long) {
        if (revision !in values) throw RepositoryException(RepositoryFailure.NOT_FOUND, "revision")
        if (values.keys.last() != expectedSavedRevision) throw RepositoryException(RepositoryFailure.CONFLICT, "stale")
        applied = revision
    }
}
