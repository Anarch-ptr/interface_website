package com.filamentvision.core.snapshot

import com.filamentvision.core.preview.PreviewKey

/** Opaque adapter-encoded payload. Core never decodes it; ingress and egress copy bytes. */
class EncodedSnapshot(val key: PreviewKey, val timestamp: Long, val sourceSequence: Long,
    val mediaType: String, payload: ByteArray) {
    init {
        require(key.deviceId.isNotBlank())
        require(timestamp >= 0 && sourceSequence >= 0)
        require(mediaType.length in 1..127 && mediaType.matches(Regex("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+")))
        require(payload.isNotEmpty())
    }
    private val bytes = payload.copyOf()
    val sizeBytes: Int get() = bytes.size
    fun copyBytes(): ByteArray = bytes.copyOf()
}
data class SnapshotCacheState(val entries: Int, val totalBytes: Long)
/** Bounded write-order cache: replacement becomes newest; reads do not change eviction order.
 * Latest means accepted arrival, not timestamp comparison. All operations are linearizable.
 * Caller-owned snapshots/copies are outside the retained-byte budget.
 */
class LatestFrameSnapshotCache(private val maxEntries: Int, private val maxBytes: Long) {
    init { require(maxEntries > 0 && maxBytes > 0) }
    private val entries = linkedMapOf<PreviewKey, EncodedSnapshot>()
    private var bytes = 0L
    @Synchronized fun put(snapshot: EncodedSnapshot) {
        require(snapshot.sizeBytes <= maxBytes) { "Snapshot exceeds byte budget" }
        entries.remove(snapshot.key)?.let { bytes -= it.sizeBytes }
        // Subtraction avoids overflow even when the configured budget is Long.MAX_VALUE.
        while (entries.size >= maxEntries || bytes > maxBytes - snapshot.sizeBytes) {
            val oldest = entries.keys.first()
            bytes -= entries.remove(oldest)!!.sizeBytes
        }
        entries[snapshot.key] = snapshot
        bytes += snapshot.sizeBytes
    }
    @Synchronized fun get(key: PreviewKey): EncodedSnapshot? = entries[key]
    @Synchronized fun state(): SnapshotCacheState = SnapshotCacheState(entries.size, bytes)
}
