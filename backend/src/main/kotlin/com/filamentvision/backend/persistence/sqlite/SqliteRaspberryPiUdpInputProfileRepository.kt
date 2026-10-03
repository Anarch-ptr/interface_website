package com.filamentvision.backend.persistence.sqlite

import com.filamentvision.backend.input.profile.*
import com.filamentvision.core.repository.RepositoryException
import com.filamentvision.core.repository.RepositoryFailure

class SqliteRaspberryPiUdpInputProfileRepository(private val database: SqliteDatabase) : RaspberryPiUdpInputProfileRepository {
    override suspend fun save(expectedSavedRevision: Long, profile: RaspberryPiUdpInputProfile): ProfileRevision {
        val payload = try { RaspberryPiUdpInputProfileCodec.encode(profile) }
        catch (_: IllegalArgumentException) { throw RepositoryException(RepositoryFailure.INVALID, "Invalid Raspberry Pi UDP profile") }
        return database.access(true) { c ->
            val saved = c.createStatement().use { s ->
                s.executeQuery("SELECT saved_revision FROM raspberry_pi_udp_input_state WHERE singleton=1").use { it.next(); it.getLong(1) }
            }
            if (saved != expectedSavedRevision) throw RepositoryException(RepositoryFailure.CONFLICT, "Saved UDP profile changed")
            c.prepareStatement("INSERT INTO raspberry_pi_udp_input_revisions(payload) VALUES (?)").use {
                it.setBytes(1, payload); it.executeUpdate()
            }
            val revision = c.createStatement().use { s -> s.executeQuery("SELECT last_insert_rowid()").use { it.next(); it.getLong(1) } }
            c.prepareStatement("UPDATE raspberry_pi_udp_input_state SET saved_revision=? WHERE singleton=1").use {
                it.setLong(1, revision); it.executeUpdate()
            }
            ProfileRevision(revision, RaspberryPiUdpInputProfileCodec.decode(payload))
        }
    }

    override suspend fun markApplied(expectedAppliedRevision: Long, revision: Long): Boolean = database.access(true) { c ->
        c.prepareStatement("""UPDATE raspberry_pi_udp_input_state SET applied_revision=?
            WHERE singleton=1 AND applied_revision=? AND saved_revision=?""").use {
            it.setLong(1, revision); it.setLong(2, expectedAppliedRevision); it.setLong(3, revision)
            it.executeUpdate() == 1
        }
    }

    override suspend fun state(): RaspberryPiUdpProfileState = database.access { c ->
        // Both pointers and both immutable payloads come from the same SQLite read snapshot.
        c.createStatement().use { statement ->
            statement.executeQuery("""SELECT saved.revision,saved.payload,applied.revision,applied.payload
                FROM raspberry_pi_udp_input_state state
                JOIN raspberry_pi_udp_input_revisions saved ON saved.revision=state.saved_revision
                JOIN raspberry_pi_udp_input_revisions applied ON applied.revision=state.applied_revision
                WHERE state.singleton=1""").use { row ->
                if (!row.next()) throw RepositoryException(RepositoryFailure.STORAGE, "Missing Raspberry Pi UDP state")
                RaspberryPiUdpProfileState(
                    ProfileRevision(row.getLong(1), RaspberryPiUdpInputProfileCodec.decode(row.getBytes(2))),
                    ProfileRevision(row.getLong(3), RaspberryPiUdpInputProfileCodec.decode(row.getBytes(4))))
            }
        }
    }
}
