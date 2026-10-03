package com.filamentvision.backend.input.profile

data class ProfileRevision(val revision: Long, val profile: RaspberryPiUdpInputProfile)

data class RaspberryPiUdpProfileState(val saved: ProfileRevision, val applied: ProfileRevision)

interface RaspberryPiUdpInputProfileRepository {
    suspend fun save(expectedSavedRevision: Long, profile: RaspberryPiUdpInputProfile): ProfileRevision
    /** Only the current saved revision may be applied, while the applied pointer still matches. */
    suspend fun markApplied(expectedAppliedRevision: Long, revision: Long): Boolean
    suspend fun state(): RaspberryPiUdpProfileState
}
