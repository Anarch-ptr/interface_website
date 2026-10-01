package com.filamentvision.backend.snapshot

import com.filamentvision.core.preview.PreviewKey
import com.filamentvision.core.repository.SnapshotRecord
import com.filamentvision.core.snapshot.EncodedSnapshot
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.io.IOException

data class StoredSnapshot(val record: SnapshotRecord, val key: PreviewKey, val mediaType: String, val sizeBytes: Int)

/** Already-encoded objects only. The existing root must be an application-owned directory.
 * Each write reserves a unique generated directory; user IDs never form any path component.
 * Objects are immutable and never replaced. A sibling temporary file is atomically renamed
 * to encoded.bin. Providers without atomic move fail closed. This is atomic visibility,
 * not a promise of crash durability/fsync or database/object-store transactions.
 * The root must not be concurrently modified by another filesystem principal.
 */
class FileSnapshotObjectStore internal constructor(root: Path,
    private val writeBytes: (Path, ByteArray) -> Unit) {
    private val configuredRoot = root.toAbsolutePath().normalize()
    private val realRoot = configuredRoot.toRealPath()
    init { require(Files.isDirectory(realRoot)) { "Snapshot root must be a directory" } }
    constructor(root: Path) : this(root, { path, bytes -> Files.write(path, bytes); Unit })
    fun read(objectKey: String): ByteArray {
        require(objectKey.matches(Regex("snapshot-[A-Za-z0-9_-]+/encoded\\.bin"))) { "Invalid object key" }
        if (configuredRoot.toRealPath() != realRoot) throw IOException("Snapshot root changed")
        val resolved = realRoot.resolve(objectKey).toRealPath()
        require(resolved.startsWith(realRoot) && Files.isRegularFile(resolved)) { "Object outside store" }
        require(Files.size(resolved) <= 4 * 1024 * 1024) { "Object exceeds read budget" }
        return Files.readAllBytes(resolved)
    }
    fun write(sessionId: String, snapshot: EncodedSnapshot): StoredSnapshot {
        require(sessionId.isNotBlank())
        if (configuredRoot.toRealPath() != realRoot) throw IOException("Snapshot root changed")
        val directory = Files.createTempDirectory(realRoot, "snapshot-")
        val temporary = directory.resolve("encoded.tmp")
        val final = directory.resolve("encoded.bin")
        try {
            writeBytes(temporary, snapshot.copyBytes())
            val objectKey = directory.fileName.toString() + "/encoded.bin"
            val record = SnapshotRecord(directory.fileName.toString(), sessionId, snapshot.timestamp,
                snapshot.key.cameraId.value, objectKey, snapshot.sourceSequence)
            val result = StoredSnapshot(record, snapshot.key, snapshot.mediaType, snapshot.sizeBytes)
            Files.move(temporary, final, ATOMIC_MOVE)
            return result
        } catch (failure: Throwable) {
            try { Files.deleteIfExists(temporary); Files.deleteIfExists(directory) }
            catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }
}
