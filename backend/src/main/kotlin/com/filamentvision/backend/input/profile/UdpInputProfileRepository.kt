package com.filamentvision.backend.input.profile

data class RevisionedUdpInputProfiles(val revision: Long, val profiles: UdpInputProfileSet)

interface UdpInputProfileRepository {
    suspend fun save(profiles: UdpInputProfileSet): RevisionedUdpInputProfiles
    suspend fun get(revision: Long): RevisionedUdpInputProfiles?
    suspend fun latest(): RevisionedUdpInputProfiles
    suspend fun appliedRevision(): Long
    suspend fun markApplied(revision: Long, expectedSavedRevision: Long): Boolean
}

class InMemoryUdpInputProfileRepository(initial: UdpInputProfileSet) : UdpInputProfileRepository {
    private val revisions = linkedMapOf(1L to initial)
    private var applied = 1L
    var forceMarkAppliedResult: Boolean? = null
    var markAppliedFailure: Throwable? = null

    override suspend fun save(profiles: UdpInputProfileSet): RevisionedUdpInputProfiles {
        val revision = revisions.keys.last() + 1
        revisions[revision] = profiles
        return RevisionedUdpInputProfiles(revision, profiles)
    }

    override suspend fun get(revision: Long) = revisions[revision]?.let { RevisionedUdpInputProfiles(revision, it) }
    override suspend fun latest() = RevisionedUdpInputProfiles(revisions.keys.last(), revisions.values.last())
    override suspend fun appliedRevision() = applied

    override suspend fun markApplied(revision: Long, expectedSavedRevision: Long): Boolean {
        markAppliedFailure?.let { throw it }
        forceMarkAppliedResult?.let { return it }
        if (revisions.keys.last() != expectedSavedRevision || revision !in revisions) return false
        applied = revision
        return true
    }
}
