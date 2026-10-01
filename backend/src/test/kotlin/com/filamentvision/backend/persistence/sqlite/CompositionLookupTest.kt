package com.filamentvision.backend.persistence.sqlite

import com.filamentvision.backend.snapshot.FileSnapshotObjectStore
import com.filamentvision.core.frame.CameraId
import com.filamentvision.core.preview.*
import com.filamentvision.core.repository.*
import com.filamentvision.core.runtime.RuntimeSession
import com.filamentvision.core.snapshot.EncodedSnapshot
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CompositionLookupTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun sessionKeysetUsesTimestampThenIdAndLookupsKeepResourceIdentity() {
        SqliteDatabase(temporary.root.toPath().resolve("lookup.db")).use { db ->
            val r = SqliteRepositories(db)
            listOf(RuntimeSession("b", 1, 1), RuntimeSession("a", 2, 1), RuntimeSession("c", 3, 2)).forEach(r::createSession)
            assertEquals(listOf("a", "b"), r.sessions(limit = 2).map { it.session.id })
            assertEquals(listOf("b", "c"), r.sessions(HistoryCursor(1, "a")).map { it.session.id })
            assertEquals(listOf("c"), r.sessions(from = 2, through = 2).map { it.session.id })
            val e = ErrorRecord("e", "a", 3, "INPUT", "detail")
            r.saveError(e); r.saveError(e.copy(id = "unscoped", sessionId = null))
            assertEquals(e, r.error("e")); assertNull(r.error("missing")); assertEquals(2, r.allErrors().size)
            val s = SnapshotRecord("s", "a", 4, "A", "trusted")
            r.saveSnapshot(s); assertEquals(s, r.snapshot("s")); assertNull(r.snapshot("missing"))
        }
    }
    @Test fun objectReadReturnsExactBytesAndRejectsEscapeOrArbitraryRootFile() {
        val store = FileSnapshotObjectStore(temporary.newFolder("objects").toPath())
        val item = store.write("session", EncodedSnapshot(PreviewKey("simulator-1", CameraId("A"), PreviewStage.RAW), 1, 2, "image/png", byteArrayOf(1, 2, 3)))
        assertArrayEquals(byteArrayOf(1, 2, 3), store.read(item.record.objectKey))
        for (key in listOf("../escape", "C:/Windows/system.ini", "encoded.bin", "snapshot-abc/../encoded.bin")) {
            assertThrows(IllegalArgumentException::class.java) { store.read(key) }
        }
    }
}
