package com.filamentvision.backend.migration.fixtures

import com.filamentvision.backend.input.profile.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

fun interface UdpCameraRuntimeSwapper {
    suspend fun swap(
        cameraId: UdpCameraId,
        previous: UdpInputProfile,
        next: UdpInputProfile,
        receiverRestart: Boolean,
    )
}

class UdpInputRouter(
    initialRevision: Long,
    initialProfiles: UdpInputProfileSet,
    private val swapper: UdpCameraRuntimeSwapper,
) {
    private val mutex = Mutex()
    @Volatile var appliedRevision: Long = initialRevision
        private set
    @Volatile var effectiveProfiles: UdpInputProfileSet = initialProfiles
        private set
    @Volatile var receiverEffectiveProfiles: UdpInputProfileSet = initialProfiles
        private set

    suspend fun prepareAndSwap(revision: Long, candidate: UdpInputProfileSet): SwapToken = mutex.withLock {
        val validation = UdpInputProfileValidator().validate(candidate)
        require(validation.isValid) { validation.errors.joinToString { "${it.path}: ${it.message}" } }
        // Historical revision/CAS fixture only: no retired UDP processing chain is instantiated.
        if (revision == appliedRevision && candidate == effectiveProfiles) return@withLock SwapToken.noop(this)

        val old = effectiveProfiles
        val changes = changes(old, candidate)
        val completed = mutableListOf<ProfileChange>()
        try {
            for (change in changes) {
                swapper.swap(change.cameraId, change.previous, change.next, change.restart)
                completed += change
                receiverEffectiveProfiles = replace(receiverEffectiveProfiles, change.cameraId, change.next)
            }
        } catch (error: Throwable) {
            for (change in completed.asReversed()) {
                swapper.swap(change.cameraId, change.next, change.previous, change.restart)
                receiverEffectiveProfiles = replace(receiverEffectiveProfiles, change.cameraId, change.previous)
            }
            throw error
        }
        SwapToken(this, revision, old, candidate, completed)
    }

    class SwapToken internal constructor(
        private val router: UdpInputRouter,
        private val revision: Long,
        private val oldProfiles: UdpInputProfileSet,
        private val candidate: UdpInputProfileSet,
        private val changes: List<ProfileChange>,
        private var finished: Boolean = false,
    ) {
        suspend fun commit() = router.mutex.withLock {
            if (finished) return@withLock
            router.effectiveProfiles = candidate
            router.appliedRevision = revision
            finished = true
        }

        suspend fun rollback() = router.mutex.withLock {
            if (finished) return@withLock
            for (change in changes.asReversed()) {
                router.swapper.swap(change.cameraId, change.next, change.previous, change.restart)
                router.receiverEffectiveProfiles = replace(router.receiverEffectiveProfiles, change.cameraId, change.previous)
            }
            router.effectiveProfiles = oldProfiles
            finished = true
        }

        internal companion object {
            fun noop(router: UdpInputRouter) = SwapToken(
                router, router.appliedRevision, router.effectiveProfiles, router.effectiveProfiles, emptyList(),
            )
        }
    }

    internal data class ProfileChange(
        val cameraId: UdpCameraId,
        val previous: UdpInputProfile,
        val next: UdpInputProfile,
        val restart: Boolean,
    )

    private fun changes(old: UdpInputProfileSet, next: UdpInputProfileSet): List<ProfileChange> = buildList {
        if (old.cameraA != next.cameraA) add(change(old.cameraA, next.cameraA))
        if (old.cameraB != next.cameraB) add(change(old.cameraB, next.cameraB))
    }

    private fun change(old: UdpInputProfile, next: UdpInputProfile) = ProfileChange(
        old.cameraId, old, next,
        old.enabled != next.enabled || old.sourceIp != next.sourceIp ||
            old.listenPort != next.listenPort || old.transportMode != next.transportMode,
    )

    private companion object {
        fun replace(set: UdpInputProfileSet, id: UdpCameraId, profile: UdpInputProfile) = when (id) {
            UdpCameraId.A -> set.copy(cameraA = profile)
            UdpCameraId.B -> set.copy(cameraB = profile)
        }
    }
}
