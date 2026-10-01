package com.filamentvision.core.repository

interface SnapshotRepository {
    fun snapshot(id: String): SnapshotRecord?
    fun saveSnapshot(snapshot: SnapshotRecord)
    fun snapshots(sessionId: String): List<SnapshotRecord>
}
