package com.filamentvision.backend.persistence.sqlite

import com.filamentvision.core.repository.*
import com.filamentvision.core.config.*
import com.filamentvision.core.runtime.*
import com.filamentvision.core.session.SessionStatistics
import com.filamentvision.core.vision.CameraMeasurementSource
import com.filamentvision.backend.input.profile.*
import com.filamentvision.backend.input.runtime.*
import kotlinx.coroutines.runBlocking
import java.sql.DriverManager
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

    @Test fun measurementRetryTreatsSignedZerosAsStorageEquivalentButNoOtherDifference() {
        r.sessions.createSession(session)
        val template = measurement("signed-zero", 10)
        val zero = template.measurement.copy(fusedDiameter = -0.0, shapeDifference = -0.0, confidence = -0.0,
            cameraA = template.measurement.cameraA!!.copy(pixelWidth = -0.0, diameterMm = -0.0, confidence = -0.0),
            cameraB = template.measurement.cameraB!!.copy(pixelWidth = -0.0, diameterMm = -0.0, confidence = -0.0))
        val record = template.copy(measurement = zero)
        r.measurements.appendMeasurements(listOf(record))
        val stored = r.measurements.history("s").items.single()
        with(stored.measurement) {
            listOf(fusedDiameter, shapeDifference, confidence, cameraA!!.pixelWidth, cameraA!!.diameterMm,
                cameraA!!.confidence, cameraB!!.pixelWidth, cameraB!!.diameterMm, cameraB!!.confidence)
                .map(::requireNotNull)
                .forEach { assertEquals("SQLite stores positive zero", 0L, it.toBits()) }
        }
        r.measurements.appendMeasurements(listOf(record, stored, record))
        val smallest = Double.MIN_VALUE
        val changed = listOf(zero.copy(fusedDiameter = smallest), zero.copy(shapeDifference = smallest), zero.copy(confidence = smallest),
            zero.copy(cameraA = zero.cameraA!!.copy(pixelWidth = smallest)), zero.copy(cameraA = zero.cameraA!!.copy(diameterMm = smallest)),
            zero.copy(cameraA = zero.cameraA!!.copy(confidence = smallest)), zero.copy(cameraB = zero.cameraB!!.copy(pixelWidth = smallest)),
            zero.copy(cameraB = zero.cameraB!!.copy(diameterMm = smallest)), zero.copy(cameraB = zero.cameraB!!.copy(confidence = smallest)))
        changed.forEachIndexed { i, m -> fails(RepositoryFailure.CONFLICT) {
            r.measurements.appendMeasurements(listOf(measurement("new-$i", 20), record.copy(measurement = m)))
        } }
        assertEquals(listOf(stored), r.measurements.history("s").items)
        val nonzero = measurement("nonzero", 30)
        r.measurements.appendMeasurements(listOf(nonzero))
        fails(RepositoryFailure.CONFLICT) { r.measurements.appendMeasurements(listOf(nonzero.copy(
            measurement = nonzero.measurement.copy(fusedDiameter = Math.nextUp(nonzero.measurement.fusedDiameter))))) }
    }

    @Test fun alarmRetryTreatsSignedZerosAsStorageEquivalentButNoOtherDifference() {
        r.sessions.createSession(session)
        val zero = alarm.transition.copy(fusedDiameter = -0.0,
            from = alarm.transition.from.copy(deviationPercent = -0.0), to = alarm.transition.to.copy(deviationPercent = -0.0))
        val record = alarm.copy(transition = zero)
        r.alarms.appendAlarms(listOf(record))
        val stored = r.alarms.alarms("s").single()
        with(stored.transition) { listOf(fusedDiameter, from.deviationPercent, to.deviationPercent).forEach { assertEquals(0L, it.toBits()) } }
        r.alarms.appendAlarms(listOf(record, stored, record))
        listOf(zero.copy(fusedDiameter = Double.MIN_VALUE), zero.copy(from = zero.from.copy(deviationPercent = Double.MIN_VALUE)),
            zero.copy(to = zero.to.copy(deviationPercent = Double.MIN_VALUE))).forEachIndexed { i, changed ->
            fails(RepositoryFailure.CONFLICT) { r.alarms.appendAlarms(listOf(alarm.copy(id = "new-$i"), record.copy(transition = changed))) }
        }
        assertEquals(listOf(stored), r.alarms.alarms("s"))
        val nonzero = alarm.copy(id = "nonzero")
        r.alarms.appendAlarms(listOf(nonzero))
        fails(RepositoryFailure.CONFLICT) { r.alarms.appendAlarms(listOf(nonzero.copy(
            transition = nonzero.transition.copy(fusedDiameter = Math.nextUp(nonzero.transition.fusedDiameter))))) }
    }

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

    @Test fun versionOneAndVersionTwoDatabasesUpgradeTransactionallyToVersionThree() {
        listOf(1, 2).forEach { oldVersion ->
            val path = temporary.root.toPath().resolve("legacy-$oldVersion.db")
            DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}").use { connection ->
                (1..oldVersion).forEach { version ->
                    val resource = if (version == 1) "/db/migration/V001__phase2_foundation.sql"
                        else "/db/migration/V002__snapshot_source_sequence.sql"
                    val sql = checkNotNull(javaClass.getResourceAsStream(resource)).bufferedReader().use { it.readText() }
                    connection.createStatement().use { statement ->
                        sql.split(';').filter(String::isNotBlank).forEach(statement::execute)
                        statement.execute("PRAGMA user_version = $version")
                    }
                }
            }
            SqliteDatabase(path).use { upgraded ->
                upgraded.access { connection ->
                    assertEquals(3, connection.createStatement().use { it.executeQuery("PRAGMA user_version").use { row -> row.next(); row.getInt(1) } })
                    val columns = connection.createStatement().use { statement ->
                        statement.executeQuery("PRAGMA table_info(measurements)").use { rows -> buildSet { while (rows.next()) add(rows.getString("name")) } }
                    }
                    assertTrue(columns.containsAll(listOf("a_source", "b_source")))
                }
            }
        }
    }

    @Test fun udpProfilesPreserveSavedAndAppliedRevisionsWithCompareAndSet() = runBlocking {
        val repository = SqliteUdpInputProfileRepository(database, initialProfiles = udpProfiles(5201, 5202))
        assertEquals(1L, repository.latest().revision)
        assertEquals(1L, repository.appliedRevision())
        val revision2 = repository.save(udpProfiles(5301, 5302))
        assertEquals(2L, revision2.revision)
        assertFalse(repository.markApplied(2, 1))
        assertEquals(1L, repository.appliedRevision())
        assertTrue(repository.markApplied(2, 2))
        assertEquals(2L, repository.appliedRevision())
        assertEquals(revision2, repository.get(2))
        database.close()
        database = SqliteDatabase(temporary.root.toPath().resolve("test.db"))
        val reopened = SqliteUdpInputProfileRepository(database)
        assertEquals(revision2, reopened.latest())
        assertEquals(2L, reopened.appliedRevision())
    }

    @Test fun measurementSourceAndNullablePixelWidthRoundTripWhileLegacyRowsDefaultToVision() {
        r.sessions.createSession(session)
        val original = measurement("direct", 10)
        val direct = original.copy(measurement = original.measurement.copy(cameraA = original.measurement.cameraA!!.copy(
            pixelWidth = null, source = CameraMeasurementSource.DIRECT_DIAMETER)))
        r.measurements.appendMeasurements(listOf(direct))
        assertEquals(direct, r.measurements.history("s").items.single())

        database.access(true) { connection ->
            connection.prepareStatement("UPDATE measurements SET a_source=NULL WHERE id=?").use { statement ->
                statement.setString(1, "direct"); statement.executeUpdate()
            }
        }
        val legacy = r.measurements.history("s").items.single()
        assertEquals(CameraMeasurementSource.VISION, legacy.measurement.cameraA!!.source)
    }

    @Test fun sqlitePersistenceFailureAfterRuntimeSwapRestoresOldConsistentTriple() = runBlocking {
        val initial = udpProfiles(5401, 5402)
        val sqlite = SqliteUdpInputProfileRepository(database, initialProfiles = initial)
        val revision2 = sqlite.save(udpProfiles(5501, 5502))
        val failing = object : UdpInputProfileRepository by sqlite {
            override suspend fun markApplied(revision: Long, expectedSavedRevision: Long): Boolean =
                throw IllegalStateException("simulated transaction failure")
        }
        val router = UdpInputRouter(1, initial, UdpCameraRuntimeSwapper { _, _, _, _ -> })
        val service = UdpInputConfigurationService(failing, router)
        assertThrows(IllegalStateException::class.java) { runBlocking { service.apply(revision2.revision, revision2.revision) } }
        assertEquals(1L, sqlite.appliedRevision())
        assertEquals(1L, router.appliedRevision)
        assertEquals(initial, router.effectiveProfiles)
        assertEquals(initial, router.receiverEffectiveProfiles)
    }

    private fun udpProfiles(portA: Int, portB: Int) = UdpInputProfileSet(
        udpProfile(UdpCameraId.A, portA), udpProfile(UdpCameraId.B, portB),
    )
    private fun udpProfile(camera: UdpCameraId, port: Int) = UdpInputProfile(
        camera, true, "127.0.0.1", port, TransportMode.SINGLE_DATAGRAM,
        PayloadProfile.Diameter(DiameterValueEncoding.FLOAT32, DiameterByteOrder.BIG_ENDIAN),
        ApplicationFramingProfile(), TransportLimits(),
    )
}
