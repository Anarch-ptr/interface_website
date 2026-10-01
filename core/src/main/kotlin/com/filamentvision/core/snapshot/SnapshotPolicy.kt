package com.filamentvision.core.snapshot

import com.filamentvision.core.preview.PreviewKey

/** Per-device/camera/stage admission cooldown, bounded by a copied fixed registry.
 * Supply non-negative monotonic milliseconds from a stable origin. Epoch milliseconds also
 * work conservatively: backward readings freeze at the high-water mark, never grant early.
 * Acquisition consumes the slot even if subsequent encoding/storage fails; no rollback API.
 */
class SnapshotPolicy(keys: Set<PreviewKey>, private val clockMillis: () -> Long) {
    private val lastCapture = keys.associateWith { null as Long? }.toMutableMap()
    private var highWater = 0L
    init { require(keys.isNotEmpty() && keys.all { it.deviceId.isNotBlank() }) }
    @Synchronized fun tryAcquire(key: PreviewKey): Boolean {
        require(lastCapture.containsKey(key)) { "Unregistered snapshot key" }
        val reading = clockMillis()
        require(reading >= 0)
        highWater = maxOf(highWater, reading)
        val previous = lastCapture[key]
        if (previous != null && highWater - previous < 300_000L) return false
        lastCapture[key] = highWater
        return true
    }
}
