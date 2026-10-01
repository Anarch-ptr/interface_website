package com.filamentvision.backend.persistence.sqlite

import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import com.filamentvision.core.repository.RepositoryException
import com.filamentvision.core.repository.RepositoryFailure

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
            } else if (version !in 1..2) throw RepositoryException(RepositoryFailure.STORAGE, "Unsupported schema version")
            if (version < 2) {
                val migration = checkNotNull(javaClass.getResourceAsStream("/db/migration/V002__snapshot_source_sequence.sql"))
                    .bufferedReader().use { it.readText() }
                connection.createStatement().use { statement ->
                    migration.split(';').filter { it.isNotBlank() }.forEach { statement.execute(it) }
                    statement.execute("PRAGMA user_version = 2")
                }
            }
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
