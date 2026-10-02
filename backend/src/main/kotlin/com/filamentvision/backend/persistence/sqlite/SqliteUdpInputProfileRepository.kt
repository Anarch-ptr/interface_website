package com.filamentvision.backend.persistence.sqlite

import com.filamentvision.backend.input.profile.*
import com.filamentvision.core.repository.RepositoryException
import com.filamentvision.core.repository.RepositoryFailure

class SqliteUdpInputProfileRepository(
    private val database: SqliteDatabase,
    initialProfiles: UdpInputProfileSet? = null,
) : UdpInputProfileRepository {
    init {
        if (initialProfiles != null) {
            val validation = UdpInputProfileValidator().validate(initialProfiles)
            require(validation.isValid) { validation.errors.toString() }
            database.access(true) { connection ->
                val count = connection.createStatement().use { it.executeQuery("SELECT COUNT(*) FROM udp_input_revisions").use { row -> row.next(); row.getLong(1) } }
                if (count == 0L) {
                    connection.prepareStatement("INSERT INTO udp_input_revisions(payload) VALUES (?)").use {
                        it.setBytes(1, UdpInputProfileCodec.encode(initialProfiles)); it.executeUpdate()
                    }
                    val revision = connection.createStatement().use { it.executeQuery("SELECT last_insert_rowid()").use { row -> row.next(); row.getLong(1) } }
                    connection.prepareStatement("UPDATE udp_input_state SET applied_revision=? WHERE singleton=1").use {
                        it.setLong(1, revision); it.executeUpdate()
                    }
                }
            }
        }
    }

    override suspend fun save(profiles: UdpInputProfileSet): RevisionedUdpInputProfiles {
        val encoded = UdpInputProfileCodec.encode(profiles)
        return database.access(true) { connection ->
            connection.prepareStatement("INSERT INTO udp_input_revisions(payload) VALUES (?)").use {
                it.setBytes(1, encoded); it.executeUpdate()
            }
            val revision = connection.createStatement().use { it.executeQuery("SELECT last_insert_rowid()").use { row -> row.next(); row.getLong(1) } }
            RevisionedUdpInputProfiles(revision, profiles)
        }
    }

    override suspend fun get(revision: Long): RevisionedUdpInputProfiles? = database.access { connection ->
        connection.prepareStatement("SELECT payload FROM udp_input_revisions WHERE revision=?").use { statement ->
            statement.setLong(1, revision)
            statement.executeQuery().use { row -> if (row.next()) RevisionedUdpInputProfiles(revision, UdpInputProfileCodec.decode(row.getBytes(1))) else null }
        }
    }

    override suspend fun latest(): RevisionedUdpInputProfiles = database.access { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT revision,payload FROM udp_input_revisions ORDER BY revision DESC LIMIT 1").use { row ->
                if (!row.next()) throw RepositoryException(RepositoryFailure.NOT_FOUND, "No UDP input profile exists")
                RevisionedUdpInputProfiles(row.getLong(1), UdpInputProfileCodec.decode(row.getBytes(2)))
            }
        }
    }

    override suspend fun appliedRevision(): Long = database.access { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT applied_revision FROM udp_input_state WHERE singleton=1").use { row ->
                row.next(); val value = row.getLong(1)
                if (row.wasNull()) throw RepositoryException(RepositoryFailure.NOT_FOUND, "No applied UDP input profile")
                value
            }
        }
    }

    override suspend fun markApplied(revision: Long, expectedSavedRevision: Long): Boolean = database.access(true) { connection ->
        connection.prepareStatement("""UPDATE udp_input_state SET applied_revision=? WHERE singleton=1
            AND (SELECT MAX(revision) FROM udp_input_revisions)=?
            AND EXISTS(SELECT 1 FROM udp_input_revisions WHERE revision=?)""").use { statement ->
            statement.setLong(1, revision); statement.setLong(2, expectedSavedRevision); statement.setLong(3, revision)
            statement.executeUpdate() == 1
        }
    }
}
