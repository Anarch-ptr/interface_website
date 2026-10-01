package com.filamentvision.backend.persistence.sqlite

import com.filamentvision.core.repository.*
import com.filamentvision.core.config.*
import com.filamentvision.core.runtime.*
import com.filamentvision.core.session.SessionStatistics
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SqliteRepositoryContractTest : RepositoryConformance() {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var database: SqliteDatabase
    override fun openRepositories(): RepositorySet {
        database = SqliteDatabase(temporary.root.toPath().resolve("test.db"))
        return repositorySet(SqliteRepositories(database))
    }
    @After fun close() { database.close() }

    @Test fun closeAndReopenRetainsSessionsHistoryAlarmsAndBothConfigurationRevisions() {
        r.sessions.createSession(session)
        r.measurements.appendMeasurements(listOf(measurement("m", 10)))
        r.alarms.appendAlarms(listOf(alarm))
        val finalized = SessionFinalization(session, 99, FinalizationReason.COMPLETED, SessionStatistics(1, 1.77, 1.77, 1.77, 0.0, .92, 1))
        r.sessions.finalizeSession(finalized)
        r.configurations.saveConfiguration(configuration)
        r.configurations.markApplied(1, 1)
        r.configurations.saveConfiguration(configuration.copy(targetDiameter = 2.0))
        database.close()
        database = SqliteDatabase(temporary.root.toPath().resolve("test.db"))
        val reopened = repositorySet(SqliteRepositories(database))
        assertEquals(SessionRecord(session, finalized), reopened.sessions.session("s"))
        assertEquals(listOf(measurement("m", 10)), reopened.measurements.history("s").items)
        assertEquals(listOf(alarm), reopened.alarms.alarms("s"))
        assertEquals(ConfigurationRevisions(2, 1), reopened.configurations.revisions())
        assertEquals(configuration, reopened.configurations.configuration(1)!!.configuration)
    }
    @Test fun closedDatabaseFailuresAreTypedForReadsAndWrites() {
        database.close()
        fails(RepositoryFailure.STORAGE) { r.sessions.session("s") }
        fails(RepositoryFailure.STORAGE) { r.sessions.createSession(session) }
        fails(RepositoryFailure.STORAGE) { r.sessions.finalizeSession(SessionFinalization(session, 2, FinalizationReason.COMPLETED, SessionStatistics())) }
        fails(RepositoryFailure.STORAGE) { r.measurements.history("s") }
        fails(RepositoryFailure.STORAGE) { r.measurements.appendMeasurements(listOf(measurement("m", 10))) }
        fails(RepositoryFailure.STORAGE) { r.alarms.alarms("s") }
        fails(RepositoryFailure.STORAGE) { r.alarms.appendAlarms(listOf(alarm)) }
        fails(RepositoryFailure.STORAGE) { r.configurations.revisions() }
        fails(RepositoryFailure.STORAGE) { r.configurations.configuration(1) }
        fails(RepositoryFailure.STORAGE) { r.configurations.saveConfiguration(configuration) }
        fails(RepositoryFailure.STORAGE) { r.configurations.markApplied(1, 1) }
        fails(RepositoryFailure.STORAGE) { r.snapshots.snapshots("s") }
        fails(RepositoryFailure.STORAGE) { r.snapshots.saveSnapshot(SnapshotRecord("p", "s", 1, "A", "object")) }
        fails(RepositoryFailure.STORAGE) { r.errors.errors(null) }
        fails(RepositoryFailure.STORAGE) { r.errors.saveError(ErrorRecord("e", null, 1, "INPUT", "failed")) }
    }

    @Test fun separateConnectionsAllocateUniqueRevisionsUnderConcurrency() {
        val anotherDatabase = SqliteDatabase(temporary.root.toPath().resolve("test.db"))
        val another = SqliteRepositories(anotherDatabase)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures = listOf(r.configurations, another).mapIndexed { index, repository ->
                executor.submit<List<ConfigurationRevision>> {
                    ready.countDown()
                    check(start.await(5, TimeUnit.SECONDS))
                    (1..10).map { repository.saveConfiguration(configuration.copy(targetDiameter = 1.0 + index + it / 100.0)) }
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            val saved = futures.flatMap { it.get(10, TimeUnit.SECONDS) }
            assertEquals((1L..20L).toSet(), saved.map { it.revision }.toSet())
            saved.forEach { assertEquals(it, r.configurations.configuration(it.revision)) }
            assertEquals(ConfigurationRevisions(20, null), r.configurations.revisions())
        } finally { start.countDown(); executor.shutdownNow(); anotherDatabase.close() }
    }

    @Test fun concurrentExternalSaveProducesConflictWithoutPublishingWrongConfiguration() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var published: MonitoringConfiguration? = null
        val runtime = object : ConfigurationRuntime {
            override fun <T> withConfigurationGuard(action: (ConfigurationRuntimeState) -> T): T {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                return action(object : ConfigurationRuntimeState {
                    override val monitoringActive = false
                    override val configuration = published
                    override fun publishConfiguration(configuration: MonitoringConfiguration) { published = configuration }
                })
            }
        }
        val executor = Executors.newSingleThreadExecutor()
        val anotherDatabase = SqliteDatabase(temporary.root.toPath().resolve("test.db"))
        try {
            val service = ConfigurationService(r.configurations, runtime)
            val pending = executor.submit<ConfigurationApplyResult> { service.saveAndApply(configuration) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            SqliteRepositories(anotherDatabase).saveConfiguration(configuration.copy(targetDiameter = 2.0))
            release.countDown()
            val error = assertThrows(java.util.concurrent.ExecutionException::class.java) { pending.get(5, TimeUnit.SECONDS) }
            assertEquals(RepositoryFailure.CONFLICT, (error.cause as RepositoryException).failure)
            assertNull(published)
            assertEquals(ConfigurationRevisions(2, null), r.configurations.revisions())
            assertEquals(configuration, r.configurations.configuration(1)!!.configuration)
        } finally { release.countDown(); executor.shutdownNow(); anotherDatabase.close() }
    }

    @Test fun sqlOpeningFailuresDoNotEscapeAsSqlExceptions() {
        val failure = assertThrows(RepositoryException::class.java) {
            SqliteDatabase(temporary.root.toPath().resolve("missing-parent").resolve("test.db"))
        }
        assertEquals(RepositoryFailure.STORAGE, failure.failure)
        assertNull(failure.cause)
    }
}
