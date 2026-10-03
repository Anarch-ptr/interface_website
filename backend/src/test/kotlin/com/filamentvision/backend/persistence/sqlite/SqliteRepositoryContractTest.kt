package com.filamentvision.backend.persistence.sqlite

import com.filamentvision.backend.migration.fixtures.*
import com.filamentvision.core.repository.*
import com.filamentvision.core.config.*
import com.filamentvision.core.runtime.*
import com.filamentvision.core.session.SessionStatistics
import com.filamentvision.core.vision.CameraMeasurementSource
import com.filamentvision.backend.input.profile.*
import com.filamentvision.backend.input.runtime.*
import kotlinx.coroutines.runBlocking
import java.sql.DriverManager
import java.sql.Connection
import java.nio.file.Path
import java.nio.ByteBuffer
import com.filamentvision.core.model.*
import com.filamentvision.core.fusion.FusionReason
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
        val zero = template.measurement.copy(diameterMm = -0.0, confidence = -0.0,
            evidence = listOf(template.measurement.evidence.single().copy(pixelWidth = -0.0, diameterMm = -0.0, confidence = -0.0)))
        val record = template.copy(measurement = zero)
        r.measurements.appendMeasurements(listOf(record))
        val stored = r.measurements.history("s").items.single()
        with(stored.measurement) {
            listOf(diameterMm, confidence, evidence.single().pixelWidth, evidence.single().diameterMm, evidence.single().confidence)
                .map(::requireNotNull)
                .forEach { assertEquals("SQLite stores positive zero", 0L, it.toBits()) }
        }
        r.measurements.appendMeasurements(listOf(record, stored, record))
        val smallest = Double.MIN_VALUE
        val changed = listOf(zero.copy(diameterMm = smallest), zero.copy(evidence = zero.evidence, confidence = smallest),
            zero.copy(evidence = listOf(zero.evidence.single().copy(pixelWidth = smallest))),
            zero.copy(evidence = listOf(zero.evidence.single().copy(diameterMm = smallest))),
            zero.copy(evidence = listOf(zero.evidence.single().copy(confidence = smallest))))
        changed.forEachIndexed { i, m -> fails(RepositoryFailure.CONFLICT) {
            r.measurements.appendMeasurements(listOf(measurement("new-$i", 20), record.copy(measurement = m)))
        } }
        assertEquals(listOf(stored), r.measurements.history("s").items)
        val nonzero = measurement("nonzero", 30)
        r.measurements.appendMeasurements(listOf(nonzero))
        fails(RepositoryFailure.CONFLICT) { r.measurements.appendMeasurements(listOf(nonzero.copy(
            measurement = nonzero.measurement.copy(diameterMm = Math.nextUp(nonzero.measurement.diameterMm))))) }
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

    @Test fun versionOneTwoAndThreeUpgradeWithoutRewritingHistoryOrConfiguration() = runBlocking {
        listOf(1, 2, 3).forEach { oldVersion ->
            val path = temporary.root.toPath().resolve("legacy-$oldVersion.db")
            val legacyConfig = configuration.copy(legacy = LegacyMonitoringConfiguration(VisionConfiguration(), FusionConfiguration(.3, .7, .8, 321)))
            val bytes = ConfigurationCodec.encode(legacyConfig)
            lateinit var originalRows: Map<String, Pair<List<String>, List<List<String?>>>>
            createLegacyDatabase(path, oldVersion) { connection ->
                connection.prepareStatement("INSERT INTO configuration_revisions(payload) VALUES (?)").use { it.setBytes(1, bytes); it.executeUpdate() }
                connection.createStatement().use { it.execute("UPDATE configuration_state SET applied_revision=1") }
                insertLegacySession(connection)
                insertLegacyMeasurement(connection, "both", 10)
                insertLegacyMeasurement(connection, "only-a", 11, cameraB = false)
                insertLegacyMeasurement(connection, "only-b", 12, cameraA = false)
                connection.createStatement().use { it.execute("INSERT INTO snapshots(id,session_id,timestamp,camera_id,object_key) VALUES ('old-snapshot','s',13,'A','retained')") }
                connection.createStatement().use {
                    it.execute("INSERT INTO alarms VALUES ('old-alarm','s',14,1.86,'NORMAL','NONE',0,'CRITICAL','HIGH',6)")
                    it.execute("INSERT INTO errors VALUES ('old-error','s',15,'INPUT','retained error')")
                    if (oldVersion >= 2) it.execute("UPDATE snapshots SET source_sequence=123")
                }
                originalRows = legacyRows(connection)
            }
            SqliteDatabase(path).use { upgraded ->
                val repositories = SqliteRepositories(upgraded)
                val history = repositories.history("s").items
                assertEquals(listOf("both", "only-a", "only-b"), history.map { it.id })
                assertEquals(listOf(2, 1, 1), history.map { it.measurement.evidence.size })
                assertEquals(listOf("A", "B"), history.first().measurement.evidence.map { it.cameraId.value })
                assertEquals(1.77, history.first().measurement.diameterMm, 0.0)
                assertEquals(.1, history.first().measurement.legacyFusion!!.shapeDifference, 0.0)
                assertEquals(FusionReason.BOTH_CAMERAS, history.first().measurement.legacyFusion!!.fusionReason)
                assertEquals(legacyConfig, repositories.configuration(1)!!.configuration)
                assertEquals(ConfigurationRevisions(1, 1), repositories.revisions())
                assertEquals("retained", repositories.snapshot("old-snapshot")!!.objectKey)
                assertEquals(RaspberryPiUdpInputProfile.defaults(), SqliteRaspberryPiUdpInputProfileRepository(upgraded).state().applied.profile)
                upgraded.access { connection ->
                    assertEquals(originalRows, legacyRows(connection, originalRows.mapValues { it.value.first }))
                    assertEquals(4L, scalar(connection, "PRAGMA user_version"))
                    assertEquals(3L, scalar(connection, "SELECT COUNT(*) FROM measurements"))
                    assertEquals(0L, scalar(connection, "SELECT COUNT(*) FROM measurements_v2"))
                    connection.createStatement().use { statement -> statement.executeQuery("SELECT payload FROM configuration_revisions WHERE revision=1").use { row -> row.next(); assertArrayEquals(bytes, row.getBytes(1)) } }
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
        val direct = original.copy(measurement = original.measurement.copy(evidence = listOf(original.measurement.evidence.single().copy(
            pixelWidth = null, source = CameraMeasurementSource.DIRECT_DIAMETER))))
        r.measurements.appendMeasurements(listOf(direct))
        assertEquals(direct, r.measurements.history("s").items.single())

        database.access(true) { connection ->
            insertLegacyMeasurement(connection, "legacy", 11)
            connection.createStatement().use { it.execute("UPDATE measurements SET a_source=NULL WHERE id='legacy'") }
        }
        val legacy = r.measurements.history("s").items.last()
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

    @Test fun freshVersionFourSeedsDisabledProfileExactlyOnce() = runBlocking {
        val state = SqliteRaspberryPiUdpInputProfileRepository(database).state()
        assertEquals(1L, state.saved.revision)
        assertEquals(state.saved, state.applied)
        assertEquals(RaspberryPiUdpInputProfile.defaults(), state.saved.profile)
        database.access { c ->
            assertEquals(4L, scalar(c, "PRAGMA user_version"))
            assertEquals(0L, scalar(c, "SELECT COUNT(*) FROM udp_input_revisions"))
        }
        database.close()
        database = SqliteDatabase(temporary.root.toPath().resolve("test.db"))
        assertEquals(state, SqliteRaspberryPiUdpInputProfileRepository(database).state())
        database.access { assertEquals(1L, scalar(it, "SELECT COUNT(*) FROM raspberry_pi_udp_input_revisions")) }
    }

    @Test fun migrationCarriesLatestCompatibleCameraAWithoutChangingOldProfiles() = runBlocking {
        val payloads = listOf(
            PayloadProfile.Jpeg(2_000_000, 640, 480) to ImagePayloadProfile.Jpeg(2_000_000, 640, 480),
            PayloadProfile.Gray8(640, 480) to ImagePayloadProfile.Gray8(640, 480),
            PayloadProfile.Binary(640, 480, BinaryPacking.BIT_PACKED) to ImagePayloadProfile.Binary(640, 480, BinaryPacking.BIT_PACKED),
            PayloadProfile.Rgb888(640, 480, RgbChannelOrder.BGR) to ImagePayloadProfile.Rgb888(640, 480, RgbChannelOrder.BGR),
        )
        payloads.forEachIndexed { index, (oldPayload, expectedPayload) ->
            val path = temporary.root.toPath().resolve("compatible-$index.db")
            val old = udpProfiles(5701, 5702).let { it.copy(cameraA = it.cameraA.copy(
                sourceIp = "192.168.1.50", transportMode = TransportMode.STRUCTURED_CHUNKED,
                payload = oldPayload, applicationFraming = safeFraming(),
                transportLimits = TransportLimits(2, 3_000_000, 1200, 3000, 1300))) }
            val bytes = UdpInputProfileCodec.encode(old)
            createLegacyDatabase(path, 3) { c ->
                insertLegacyProfile(c, UdpInputProfileCodec.encode(udpProfiles(5601, 5602)))
                insertLegacyProfile(c, bytes)
                c.createStatement().use { it.execute("UPDATE udp_input_state SET applied_revision=1") }
            }
            SqliteDatabase(path).use { db ->
                val state = SqliteRaspberryPiUdpInputProfileRepository(db).state()
                assertEquals(state.saved, state.applied)
                assertEquals(RaspberryPiUdpInputProfile(false, NetworkInterfaceSelection.Auto, 5701, "192.168.1.50",
                    expectedPayload, DatagramEnvelopeProfile("AB CD", "12 EF"), old.cameraA.transportLimits), state.saved.profile)
                db.access { c ->
                    assertEquals(2L, scalar(c, "SELECT COUNT(*) FROM udp_input_revisions"))
                    assertEquals(1L, scalar(c, "SELECT applied_revision FROM udp_input_state"))
                    c.createStatement().use { s -> s.executeQuery("SELECT payload FROM udp_input_revisions WHERE revision=2").use { row -> row.next(); assertArrayEquals(bytes, row.getBytes(1)) } }
                }
            }
        }
    }

    @Test fun migrationUsesExactDefaultEnvelopeForEveryUnsafeLegacyFraming() = runBlocking {
        val safe = safeFraming()
        listOf(ApplicationFramingProfile(), safe.copy(headerFormat = FramingFormat.ASCII, headerBytes = "AB"),
            safe.copy(footerScope = FramingScope.FRAME), safe.copy(stripHeader = false), safe.copy(stripFooter = false),
            safe.copy(headerBytes = "AB"), safe.copy(footerBytes = "AB CD EF"), safe.copy(footerEnabled = false))
            .forEachIndexed { index, framing ->
                val path = temporary.root.toPath().resolve("unsafe-$index.db")
                createLegacyDatabase(path, 3) { c ->
                    val profiles = udpProfiles(5801, 5802)
                    insertLegacyProfile(c, UdpInputProfileCodec.encode(profiles.copy(cameraA = profiles.cameraA.copy(applicationFraming = framing))))
                }
                SqliteDatabase(path).use { db ->
                    val profile = SqliteRaspberryPiUdpInputProfileRepository(db).state().saved.profile
                    assertFalse(profile.enabled)
                    assertEquals(5801, profile.listenPort)
                    assertEquals(DatagramEnvelopeProfile("AA 01", "0D 0A"), profile.envelope)
                    assertTrue(profile.payload is ImagePayloadProfile.Jpeg)
                    assertTrue(RaspberryPiUdpInputProfileValidator().validate(profile).isValid)
                }
            }
    }

    @Test fun versionThreeMigrationClearsLegacyUnicodeSourceIpAndRetainsOtherCompatibleFields() = runBlocking {
        // UDP profiles first exist in V003; V001/V002 upgrades are covered without profile rows.
        val path = temporary.root.toPath().resolve("unicode-source-ip.db")
        val old = udpProfiles(5901, 5902).let { it.copy(cameraA = it.cameraA.copy(
            sourceIp = "１２７.0.0.1", transportMode = TransportMode.STRUCTURED_CHUNKED,
            payload = PayloadProfile.Gray8(640, 480), applicationFraming = safeFraming(),
            transportLimits = TransportLimits(2, 3_000_000, 1200, 3000, 1300))) }
        val bytes = UdpInputProfileCodec.encode(old)
        assertEquals(old, UdpInputProfileCodec.decode(bytes))
        lateinit var originalRows: Map<String, Pair<List<String>, List<List<String?>>>>
        createLegacyDatabase(path, 3) { c ->
            insertLegacySession(c)
            insertLegacyMeasurement(c, "legacy", 10)
            insertLegacyProfile(c, bytes)
            c.createStatement().use { it.execute("UPDATE udp_input_state SET applied_revision=1") }
            originalRows = legacyRows(c)
        }
        SqliteDatabase(path).use { db ->
            val state = SqliteRaspberryPiUdpInputProfileRepository(db).state()
            assertEquals(1L, state.saved.revision)
            assertEquals(state.saved, state.applied)
            assertEquals(RaspberryPiUdpInputProfile(false, NetworkInterfaceSelection.Auto, 5901, null,
                ImagePayloadProfile.Gray8(640, 480), DatagramEnvelopeProfile("AB CD", "12 EF"),
                TransportLimits(2, 3_000_000, 1200, 3000, 1300)), state.saved.profile)
            db.access { c ->
                assertEquals(4L, scalar(c, "PRAGMA user_version"))
                assertEquals(originalRows, legacyRows(c, originalRows.mapValues { it.value.first }))
                assertEquals(1L, scalar(c, "SELECT COUNT(*) FROM udp_input_revisions"))
                assertEquals(1L, scalar(c, "SELECT applied_revision FROM udp_input_state"))
                c.createStatement().use { s -> s.executeQuery("SELECT payload FROM udp_input_revisions WHERE revision=1").use { row ->
                    row.next(); assertArrayEquals(bytes, row.getBytes(1))
                    assertEquals("１２７.0.0.1", UdpInputProfileCodec.decode(row.getBytes(1)).cameraA.sourceIp)
                } }
            }
        }
    }

    @Test fun failedMigrationRollsBackDdlSeedAndVersionWithoutChangingLegacyBytes() {
        val path = temporary.root.toPath().resolve("corrupt-profile.db")
        val corrupt = byteArrayOf(0, 0, 0, 99)
        createLegacyDatabase(path, 3) { insertLegacyProfile(it, corrupt) }
        fails(RepositoryFailure.STORAGE) { SqliteDatabase(path) }
        DriverManager.getConnection("jdbc:sqlite:$path").use { c ->
            assertEquals(3L, scalar(c, "PRAGMA user_version"))
            assertEquals(0L, scalar(c, "SELECT COUNT(*) FROM sqlite_master WHERE name IN ('measurements_v2','raspberry_pi_udp_input_revisions','raspberry_pi_udp_input_state')"))
            c.createStatement().use { s -> s.executeQuery("SELECT payload FROM udp_input_revisions").use { row -> row.next(); assertArrayEquals(corrupt, row.getBytes(1)) } }
        }
    }

    @Test fun raspberryPiSavedAndAppliedCasRejectsStaleSaveAndApplyAndSurvivesReopen() = runBlocking {
        val repo = SqliteRaspberryPiUdpInputProfileRepository(database)
        val initial = repo.state()
        val second = repo.save(1, initial.saved.profile.copy(enabled = true, listenPort = 6001,
            envelope = DatagramEnvelopeProfile("abcd", "12ef")))
        assertEquals(2L, second.revision)
        assertEquals(DatagramEnvelopeProfile("AB CD", "12 EF"), second.profile.envelope)
        assertEquals(initial.applied, repo.state().applied)
        fails(RepositoryFailure.CONFLICT) { runBlocking { repo.save(1, initial.saved.profile) } }
        assertEquals(second, repo.state().saved)
        assertFalse(repo.markApplied(99, 2))
        assertFalse(repo.markApplied(1, 99))
        val third = repo.save(2, second.profile.copy(listenPort = 6002))
        assertFalse(repo.markApplied(1, 2))
        assertEquals(initial.applied, repo.state().applied)
        assertTrue(repo.markApplied(1, 3))
        assertFalse(repo.markApplied(1, 3))
        assertTrue(repo.markApplied(3, 3))
        assertEquals(third, repo.state().applied)
        fails(RepositoryFailure.INVALID) { runBlocking { repo.save(3, third.profile.copy(listenPort = 0)) } }
        assertEquals(third, repo.state().saved)
        database.close()
        database = SqliteDatabase(temporary.root.toPath().resolve("test.db"))
        assertEquals(RaspberryPiUdpProfileState(third, third), SqliteRaspberryPiUdpInputProfileRepository(database).state())
    }

    @Test fun separateConnectionsCannotBothWinTheSameProfileSaveCas() {
        SqliteDatabase(temporary.root.toPath().resolve("test.db")).use { other ->
            val ready = CountDownLatch(2)
            val start = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val results = listOf(database, other).mapIndexed { index, db -> executor.submit<RepositoryFailure?> {
                    ready.countDown(); check(start.await(5, TimeUnit.SECONDS))
                    try { runBlocking { SqliteRaspberryPiUdpInputProfileRepository(db).save(1, RaspberryPiUdpInputProfile.defaults().copy(listenPort = 6100 + index)) }; null }
                    catch (error: RepositoryException) { error.failure }
                } }
                assertTrue(ready.await(5, TimeUnit.SECONDS)); start.countDown()
                assertEquals(listOf(null, RepositoryFailure.CONFLICT).toSet(), results.map { it.get(10, TimeUnit.SECONDS) }.toSet())
                database.access { assertEquals(2L, scalar(it, "SELECT COUNT(*) FROM raspberry_pi_udp_input_revisions")) }
                runBlocking { assertEquals(1L, SqliteRaspberryPiUdpInputProfileRepository(database).state().applied.revision) }
            } finally { start.countDown(); executor.shutdownNow() }
        }
    }

    @Test fun profileSqlFailureRollsBackInsertedRevisionAndBothPointers() = runBlocking {
        val repo = SqliteRaspberryPiUdpInputProfileRepository(database)
        val initial = repo.state()
        database.access(true) { c -> c.createStatement().use {
            it.execute("CREATE TRIGGER fail_profile_save BEFORE UPDATE OF saved_revision ON raspberry_pi_udp_input_state BEGIN SELECT RAISE(ABORT,'test failure'); END")
        } }
        assertThrows(RepositoryException::class.java) { runBlocking { repo.save(1, initial.saved.profile.copy(listenPort = 6201)) } }
        assertEquals(initial, repo.state())
        database.access(true) { c ->
            assertEquals(1L, scalar(c, "SELECT COUNT(*) FROM raspberry_pi_udp_input_revisions"))
            c.createStatement().use { it.execute("DROP TRIGGER fail_profile_save") }
        }
        val second = repo.save(1, initial.saved.profile.copy(listenPort = 6201))
        assertEquals(2L, second.revision)
        database.access(true) { c -> c.createStatement().use {
            it.execute("CREATE TRIGGER fail_profile_apply BEFORE UPDATE OF applied_revision ON raspberry_pi_udp_input_state BEGIN SELECT RAISE(ABORT,'test failure'); END")
        } }
        assertThrows(RepositoryException::class.java) { runBlocking { repo.markApplied(1, 2) } }
        assertEquals(RaspberryPiUdpProfileState(second, initial.applied), repo.state())
    }

    @Test fun newWritesUseNeutralSchemaAndMonitoringCodecVersionTwoEvenWithLegacyMetadata() {
        r.sessions.createSession(session)
        val sample = measurement("neutral", 20)
        r.measurements.appendMeasurements(listOf(sample))
        val saved = r.configurations.saveConfiguration(configuration.copy(legacy = LegacyMonitoringConfiguration(VisionConfiguration(), FusionConfiguration(.3, .7, .8, 321))))
        assertEquals(configuration, saved.configuration)
        assertEquals(saved, r.configurations.configuration(saved.revision))
        database.access { c ->
            assertEquals(0L, scalar(c, "SELECT COUNT(*) FROM measurements"))
            assertEquals(1L, scalar(c, "SELECT COUNT(*) FROM measurements_v2"))
            c.createStatement().use { statement ->
                statement.executeQuery("SELECT payload FROM configuration_revisions").use { row -> row.next(); assertEquals(2, ByteBuffer.wrap(row.getBytes(1)).int) }
                statement.executeQuery("PRAGMA table_info(measurements_v2)").use { rows ->
                    val columns = buildSet { while (rows.next()) add(rows.getString("name")) }
                    assertTrue(columns.containsAll(listOf("diameter_mm", "source_id", "pixel_width", "source_timestamp")))
                    assertFalse(columns.any { it.startsWith("a_") || it.startsWith("b_") || it.contains("fusion") || it == "shape_difference" })
                }
            }
        }
        assertEquals(sample, r.measurements.history("s").items.single())
    }

    @Test fun unionHistoryUsesGlobalKeysetAndRangeAndChecksIdentityAcrossBothTables() {
        r.sessions.createSession(session)
        database.access(true) { c -> insertLegacyMeasurement(c, "a", 10); insertLegacyMeasurement(c, "d", 11); insertLegacyMeasurement(c, "f", 15) }
        r.measurements.appendMeasurements(listOf(measurement("b", 10), measurement("c", 10), measurement("e", 12)))
        val first = r.measurements.history("s", limit = 2)
        val second = r.measurements.history("s", first.nextCursor, 2)
        val third = r.measurements.history("s", second.nextCursor, 2)
        assertEquals(listOf("a", "b"), first.items.map { it.id })
        assertEquals(HistoryCursor(10, "b"), first.nextCursor)
        assertEquals(listOf("c", "d"), second.items.map { it.id })
        assertEquals(HistoryCursor(11, "d"), second.nextCursor)
        assertEquals(listOf("e", "f"), third.items.map { it.id })
        assertNull(third.nextCursor)
        assertEquals(listOf("a", "b", "c"), r.measurements.history("s", from = 10, through = 10).items.map { it.id })
        r.measurements.appendMeasurements(listOf(first.items.first())) // Exact historical retry is a no-op.
        fails(RepositoryFailure.CONFLICT) { r.measurements.appendMeasurements(listOf(measurement("new", 20), measurement("a", 10))) }
        assertEquals(6, r.measurements.history("s").items.size)
        database.access { c -> assertEquals(3L, scalar(c, "SELECT COUNT(*) FROM measurements")); assertEquals(3L, scalar(c, "SELECT COUNT(*) FROM measurements_v2")) }
    }

    @Test fun neutralWritesRejectMissingMultipleOrLegacyEvidenceWithoutPartialBatch() {
        r.sessions.createSession(session)
        val sample = measurement("bad", 10)
        val m = sample.measurement
        val legacy = OfficialMeasurement(m.evidence.single(), null, m.diameterMm, 0.0, m.confidence, m.status, m.timestamp, FusionReason.CAMERA_B_MISSING)
        listOf(m.copy(evidence = emptyList()), m.copy(evidence = m.evidence + m.evidence), legacy,
            m.copy(diameterMm = 9.0), m.copy(evidence = m.evidence, confidence = .1)).forEach { invalid ->
            fails(RepositoryFailure.INVALID) { r.measurements.appendMeasurements(listOf(measurement("ok", 1), sample.copy(measurement = invalid))) }
            assertTrue(r.measurements.history("s").items.isEmpty())
        }
    }

    private fun safeFraming() = ApplicationFramingProfile(true, FramingFormat.HEX, "abcd", FramingScope.DATAGRAM, true,
        true, FramingFormat.HEX, "12ef", FramingScope.DATAGRAM, true)

    private fun createLegacyDatabase(path: Path, version: Int, seed: (Connection) -> Unit) {
        DriverManager.getConnection("jdbc:sqlite:$path").use { c ->
            val resources = listOf("V001__phase2_foundation.sql", "V002__snapshot_source_sequence.sql", "V003__udp_input_profiles_and_measurement_source.sql")
            resources.take(version).forEach { name ->
                val sql = checkNotNull(javaClass.getResourceAsStream("/db/migration/$name")).bufferedReader().use { it.readText() }
                c.createStatement().use { s -> sql.split(';').filter(String::isNotBlank).forEach(s::execute) }
            }
            c.createStatement().use { it.execute("PRAGMA user_version=$version") }
            seed(c)
        }
    }

    private fun insertLegacySession(c: Connection) {
        c.createStatement().use { it.execute("INSERT INTO sessions(id,generation,started_at) VALUES ('s',7,1)") }
    }
    private fun insertLegacyProfile(c: Connection, bytes: ByteArray) {
        c.prepareStatement("INSERT INTO udp_input_revisions(payload) VALUES (?)").use { it.setBytes(1, bytes); it.executeUpdate() }
    }
    private fun insertLegacyMeasurement(c: Connection, id: String, timestamp: Long, cameraA: Boolean = true, cameraB: Boolean = true) {
        c.prepareStatement("""INSERT INTO measurements(id,session_id,timestamp,fused_diameter,shape_difference,confidence,status,fusion_reason,
            a_id,a_pixel_width,a_diameter,a_confidence,a_timestamp,a_sequence,a_valid,a_roi_left,a_roi_top,a_roi_right,a_roi_bottom,a_left_edge,a_right_edge,
            b_id,b_pixel_width,b_diameter,b_confidence,b_timestamp,b_sequence,b_valid,b_roi_left,b_roi_top,b_roi_right,b_roi_bottom)
            VALUES (?,'s',?,1.77,0.1,0.92,'NORMAL','BOTH_CAMERAS',?,83,1.7,0.91,101,23,1,1,2,30,40,3,29,?,84,1.8,0.93,102,24,1,4,5,31,41)""").use {
            it.setString(1, id); it.setLong(2, timestamp); it.setString(3, if (cameraA) "A" else null); it.setString(4, if (cameraB) "B" else null); it.executeUpdate()
        }
    }
    private fun scalar(c: Connection, sql: String): Long = c.createStatement().use { s -> s.executeQuery(sql).use { row -> row.next(); row.getLong(1) } }

    /** Capture the original columns only, so additive V002/V003 columns do not disguise rewrites. */
    private fun legacyRows(c: Connection, columns: Map<String, List<String>>? = null): Map<String, Pair<List<String>, List<List<String?>>>> {
        val tables = columns?.keys ?: listOf("configuration_revisions", "configuration_state", "sessions", "measurements", "alarms", "snapshots", "errors")
        return tables.associateWith { table ->
            c.createStatement().use { statement ->
                statement.executeQuery("SELECT ${columns?.get(table)?.joinToString(",") ?: "*"} FROM $table ORDER BY rowid").use { rows ->
                    val names = (1..rows.metaData.columnCount).map { rows.metaData.getColumnName(it) }
                    names to buildList { while (rows.next()) add(names.indices.map { index ->
                        val value = rows.getObject(index + 1)
                        if (value is ByteArray) value.contentToString() else value?.toString()
                    }) }
                }
            }
        }
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
