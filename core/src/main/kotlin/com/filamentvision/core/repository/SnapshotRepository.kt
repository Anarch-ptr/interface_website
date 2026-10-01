package com.filamentvision.core.repository

interface SnapshotRepository {
    fun saveSnapshot(snapshot: SnapshotRecord)
    fun snapshots(sessionId: String): List<SnapshotRecord>
}
