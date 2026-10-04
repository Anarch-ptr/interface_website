package com.filamentvision.backend.persistence.sqlite

import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import com.filamentvision.core.repository.RepositoryException
import com.filamentvision.core.repository.RepositoryFailure
import com.filamentvision.backend.input.profile.*
import com.filamentvision.backend.input.network.EffectiveUdpNetworkPlan

/** Connections are scoped to an operation. Close prevents further operations and waits for
 * any in-flight operation; migration and multi-row writes use explicit transactions.
 */
class SqliteDatabase(private val path: Path) : AutoCloseable {
    private var closed = false
    init {
        access(write = true) { connection ->
            val version = connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA user_version").use { it.next(); it.getInt(1) }
            }
            if (version == 0) {
                val migration = checkNotNull(javaClass.getResourceAsStream("/db/migration/V001__phase2_foundation.sql"))
                    .bufferedReader().use { it.readText() }
                connection.createStatement().use { statement ->
                    migration.split(';').filter { it.isNotBlank() }.forEach { statement.execute(it) }
                    statement.execute("PRAGMA user_version = 1")
                }
            } else if (version !in 1..5) throw RepositoryException(RepositoryFailure.STORAGE, "Unsupported schema version")
            if (version < 2) {
                val migration = checkNotNull(javaClass.getResourceAsStream("/db/migration/V002__snapshot_source_sequence.sql"))
                    .bufferedReader().use { it.readText() }
                connection.createStatement().use { statement ->
                    migration.split(';').filter { it.isNotBlank() }.forEach { statement.execute(it) }
                    statement.execute("PRAGMA user_version = 2")
                }
            }
            if (version < 3) {
                val migration = checkNotNull(javaClass.getResourceAsStream("/db/migration/V003__udp_input_profiles_and_measurement_source.sql"))
                    .bufferedReader().use { it.readText() }
                connection.createStatement().use { statement ->
                    migration.split(';').filter { it.isNotBlank() }.forEach { statement.execute(it) }
                    statement.execute("PRAGMA user_version = 3")
                }
            }
            if (version < 4) {
                val migration = checkNotNull(javaClass.getResourceAsStream("/db/migration/V004__single_camera_udp_and_measurements.sql"))
                    .bufferedReader().use { it.readText() }
                connection.createStatement().use { statement ->
                    migration.split(';').filter { it.isNotBlank() }.forEach { statement.execute(it) }
                }
                seedSingleCameraProfile(connection)
                connection.createStatement().use { it.execute("PRAGMA user_version = 4") }
            }
            if (version < 5) {
                val migration = checkNotNull(javaClass.getResourceAsStream("/db/migration/V005__effective_udp_network_plan.sql"))
                    .bufferedReader().use { it.readText() }
                connection.createStatement().use { statement ->
                    migration.split(';').filter { it.isNotBlank() }.forEach { statement.execute(it) }
                }
                seedEffectiveNetworkPlan(connection)
                connection.createStatement().use { it.execute("PRAGMA user_version = 5") }
            }
        }
    }

    /** V005 never resolves DNS or enumerates interfaces. Enabled legacy profiles require explicit Re-Apply. */
    private fun seedEffectiveNetworkPlan(connection: Connection) {
        val profile = connection.createStatement().use { statement ->
            statement.executeQuery(
                """SELECT revision.payload FROM raspberry_pi_udp_input_state state
                    JOIN raspberry_pi_udp_input_revisions revision ON revision.revision = state.applied_revision
                    WHERE state.singleton = 1""".trimIndent(),
            ).use { row ->
                require(row.next()) { "Missing applied Raspberry Pi UDP input profile" }
                RaspberryPiUdpInputProfileCodec.decode(row.getBytes(1))
            }
        }
        if (!profile.enabled) {
            val payload = EffectiveUdpNetworkPlanCodec.encode(EffectiveUdpNetworkPlan.inactive(createdAtEpochMillis = 0))
            connection.prepareStatement(
                "UPDATE raspberry_pi_udp_input_state SET applied_network_plan=?, resolution_generation=0 WHERE singleton=1",
            ).use { statement ->
                statement.setBytes(1, payload)
                check(statement.executeUpdate() == 1)
            }
        }
    }

    /** Runs inside the migration transaction, including decoding/validation and both pointers. */
    private fun seedSingleCameraProfile(connection: Connection) {
        val old = connection.createStatement().use { statement ->
            statement.executeQuery("SELECT payload FROM udp_input_revisions ORDER BY revision DESC LIMIT 1").use { row ->
                if (row.next()) UdpInputProfileCodec.decode(row.getBytes(1)).cameraA else null
            }
        }
        val defaults = RaspberryPiUdpInputProfile.defaults()
        val validator = RaspberryPiUdpInputProfileValidator()
        var profile = defaults
        if (old != null) {
            profile = defaults.copy(listenPort = old.listenPort)
            val sourceCandidate = profile.copy(raspberryPiIpv4 = old.sourceIp)
            if (validator.validate(sourceCandidate).isValid) profile = sourceCandidate
            val payload = when (val value = old.payload) {
                is PayloadProfile.Jpeg -> ImagePayloadProfile.Jpeg(value.maxFrameBytes, value.expectedWidth, value.expectedHeight)
                is PayloadProfile.Gray8 -> ImagePayloadProfile.Gray8(value.width, value.height)
                is PayloadProfile.Binary -> ImagePayloadProfile.Binary(value.width, value.height, value.binaryPacking)
                is PayloadProfile.Rgb888 -> ImagePayloadProfile.Rgb888(value.width, value.height, value.channelOrder)
                is PayloadProfile.Diameter -> defaults.payload
            }
            val candidate = profile.copy(payload = payload, transportLimits = old.transportLimits)
            if (validator.validate(candidate).isValid) profile = candidate
            val framing = old.applicationFraming
            if (framing.headerEnabled && framing.footerEnabled &&
                framing.headerFormat == FramingFormat.HEX && framing.footerFormat == FramingFormat.HEX &&
                framing.headerScope == FramingScope.DATAGRAM && framing.footerScope == FramingScope.DATAGRAM &&
                framing.stripHeader && framing.stripFooter) {
                val envelope = try { DatagramEnvelopeProfile(normalizeTwoByteHex(framing.headerBytes), normalizeTwoByteHex(framing.footerBytes)) }
                catch (_: IllegalArgumentException) { defaults.envelope }
                profile = profile.copy(envelope = envelope)
            }
        }
        connection.prepareStatement("INSERT INTO raspberry_pi_udp_input_revisions(payload) VALUES (?)").use {
            it.setBytes(1, RaspberryPiUdpInputProfileCodec.encode(profile)); it.executeUpdate()
        }
        connection.createStatement().use {
            it.execute("INSERT INTO raspberry_pi_udp_input_state(singleton,saved_revision,applied_revision) VALUES (1,last_insert_rowid(),last_insert_rowid())")
        }
    }
    @Synchronized internal fun <T> access(write: Boolean = false, action: (Connection) -> T): T {
        if (closed) throw RepositoryException(RepositoryFailure.STORAGE, "Database is closed")
        try {
            return DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath()).use { connection ->
                connection.createStatement().use {
                    it.execute("PRAGMA foreign_keys = ON")
                    it.execute("PRAGMA busy_timeout = 5000")
                    if (write) it.execute("BEGIN IMMEDIATE")
                }
                try {
                    val result = action(connection)
                    if (write) connection.createStatement().use { it.execute("COMMIT") }
                    result
                } catch (failure: Throwable) {
                    if (write) try { connection.createStatement().use { it.execute("ROLLBACK") } } catch (_: SQLException) {}
                    throw failure
                }
            }
        } catch (error: SQLException) {
            val type = when {
                error.message.orEmpty().contains("UNIQUE constraint") || error.message.orEmpty().contains("PRIMARY KEY") -> RepositoryFailure.CONFLICT
                error.errorCode == 19 -> RepositoryFailure.INVALID
                else -> RepositoryFailure.STORAGE
            }
            throw RepositoryException(type, "Persistence operation failed")
        } catch (_: java.io.IOException) {
            throw RepositoryException(RepositoryFailure.STORAGE, "Invalid stored configuration")
        } catch (_: IllegalArgumentException) {
            throw RepositoryException(RepositoryFailure.STORAGE, "Invalid stored domain value")
        }
    }
    @Synchronized override fun close() { closed = true }
}
