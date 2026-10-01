package com.filamentvision.backend.snapshot

import com.filamentvision.backend.persistence.sqlite.*
import com.filamentvision.core.frame.CameraId
import com.filamentvision.core.preview.*
import com.filamentvision.core.repository.*
import com.filamentvision.core.runtime.RuntimeSession
import com.filamentvision.core.snapshot.EncodedSnapshot
import java.io.IOException
import java.nio.file.Files
import java.sql.DriverManager
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileSnapshotObjectStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val key = PreviewKey("../../device", CameraId("../camera\\B"), PreviewStage.EDGE)
    private val payload = byteArrayOf(0, -1, -128, 65, 10)
    private fun snapshot() = EncodedSnapshot(key, 987654321, 456, "image/png", payload)

    @Test fun binaryRoundtripAndMetadataUseUniqueBoundedGeneratedKeysInsideRoot() {
        val root = temporary.newFolder("objects").toPath()
        val store = FileSnapshotObjectStore(root)
        val first = store.write("../../session", snapshot())
        val second = store.write("../../session", snapshot())
        assertNotEquals(first.record.objectKey, second.record.objectKey)
        for (stored in listOf(first, second)) {
            assertTrue(stored.record.objectKey.length <= 100)
            val file = root.resolve(stored.record.objectKey).normalize()
            assertTrue(file.startsWith(root)); assertArrayEquals(payload, Files.readAllBytes(file))
            assertEquals("../../session", stored.record.sessionId); assertEquals(987654321L, stored.record.timestamp)
            assertEquals("../camera\\B", stored.record.cameraId); assertEquals(456L, stored.record.sourceSequence)
            assertEquals(key, stored.key); assertEquals("image/png", stored.mediaType); assertEquals(5, stored.sizeBytes)
        }
        Files.walk(root).use { paths -> assertEquals(2L, paths.filter { Files.isRegularFile(it) }.count()) }
    }
    @Test fun partialWriteFailureCleansTemporaryObjectAndNeverPublishes() {
        val root = temporary.newFolder("failure").toPath()
        val store = FileSnapshotObjectStore(root) { path, bytes ->
            Files.write(path, bytes.copyOf(2))
            assertFalse(Files.exists(path.resolveSibling("encoded.bin")))
            throw IOException("disk write failure")
        }
        assertThrows(IOException::class.java) { store.write("session", snapshot()) }
        Files.list(root).use { assertEquals(0L, it.count()) }
    }
    @Test fun failedAtomicMoveCleansTheReservedDirectory() {
        val root = temporary.newFolder("move-failure").toPath()
        val store = FileSnapshotObjectStore(root) { path, bytes ->
            Files.write(path, bytes); Files.delete(path)
        }
        assertThrows(IOException::class.java) { store.write("session", snapshot()) }
        Files.list(root).use { assertEquals(0L, it.count()) }
    }
    @Test fun concurrentStoresNeverOverwriteObjectsWithIdenticalMetadata() {
        val root = temporary.newFolder("concurrent").toPath()
        val stores = listOf(FileSnapshotObjectStore(root), FileSnapshotObjectStore(root))
        val executor = Executors.newFixedThreadPool(4)
        try {
            val writes = (0..39).map { i -> executor.submit<StoredSnapshot> { stores[i % 2].write("session", snapshot()) } }
            val results = writes.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(40, results.map { it.record.objectKey }.toSet().size)
            results.forEach { assertArrayEquals(payload, Files.readAllBytes(root.resolve(it.record.objectKey))) }
            Files.walk(root).use { paths -> assertEquals(40L, paths.filter { Files.isRegularFile(it) }.count()) }
        } finally { executor.shutdownNow() }
    }
    @Test fun invalidSessionOrRemovedRootNeverWritesAnObject() {
        val root = temporary.newFolder("removed").toPath()
        val store = FileSnapshotObjectStore(root)
        assertThrows(IllegalArgumentException::class.java) { store.write(" ", snapshot()) }
        Files.list(root).use { assertEquals(0L, it.count()) }
        Files.delete(root)
        assertThrows(IOException::class.java) { store.write("session", snapshot()) }
        assertFalse(Files.exists(root))
    }
    @Test fun repositoryRetainsExactSnapshotMetadataAcrossReopen() {
        val root = temporary.newFolder("persisted").toPath()
        val stored = FileSnapshotObjectStore(root).write("session", snapshot())
        val db = temporary.root.toPath().resolve("snapshot.db")
        SqliteDatabase(db).use {
            val repository = SqliteRepositories(it)
            repository.createSession(RuntimeSession("session", 1, 2))
            repository.saveSnapshot(stored.record)
        }
        SqliteDatabase(db).use {
            val saved = SqliteRepositories(it).snapshots("session").single()
            assertEquals(stored.record, saved)
            assertEquals(456L, saved.sourceSequence)
            assertArrayEquals(payload, Files.readAllBytes(root.resolve(saved.objectKey)))
        }
    }
    @Test fun versionOneDatabaseMigratesWithoutInventingLegacySequences() {
        val db = temporary.root.toPath().resolve("legacy.db")
        DriverManager.getConnection("jdbc:sqlite:$db").use { connection ->
            val sql = checkNotNull(javaClass.getResourceAsStream("/db/migration/V001__phase2_foundation.sql"))
                .bufferedReader().use { it.readText() }
            connection.createStatement().use { statement ->
                sql.split(';').filter { it.isNotBlank() }.forEach { statement.execute(it) }
                statement.execute("INSERT INTO sessions(id,generation,started_at) VALUES ('s',1,2)")
                statement.execute("INSERT INTO snapshots(id,session_id,timestamp,camera_id,object_key) VALUES ('old','s',3,'A','legacy.bin')")
                statement.execute("PRAGMA user_version=1")
            }
        }
        SqliteDatabase(db).use {
            val repository = SqliteRepositories(it)
            assertNull(repository.snapshots("s").single().sourceSequence)
            repository.saveSnapshot(SnapshotRecord("new", "s", 4, "B", "new.bin", 789))
            assertEquals(listOf(null, 789L), repository.snapshots("s").map { row -> row.sourceSequence })
        }
        SqliteDatabase(db).use { assertEquals(789L, SqliteRepositories(it).snapshots("s").last().sourceSequence) }
    }
}
