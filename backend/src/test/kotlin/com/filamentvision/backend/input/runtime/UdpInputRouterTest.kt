package com.filamentvision.backend.input.runtime

import com.filamentvision.backend.input.profile.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class UdpInputRouterTest {
    @Test
    fun parserOnlySwapAndRestartChangesAreCameraIsolatedAndRollbackable() = runBlocking {
        val initial = profiles()
        val swaps = mutableListOf<Triple<UdpCameraId, Boolean, Int>>()
        val router = UdpInputRouter(1, initial, UdpCameraRuntimeSwapper { camera, _, next, restart ->
            swaps += Triple(camera, restart, next.listenPort)
        })
        val parserOnly = initial.copy(cameraA = initial.cameraA.copy(payload = PayloadProfile.Gray8(2, 2)))
        val token = router.prepareAndSwap(2, parserOnly)
        assertEquals(listOf(Triple(UdpCameraId.A, false, 46_001)), swaps)
        token.rollback()
        assertEquals(1, router.appliedRevision)
        assertEquals(initial, router.effectiveProfiles)

        swaps.clear()
        val portChange = initial.copy(cameraA = initial.cameraA.copy(listenPort = 46_010))
        router.prepareAndSwap(2, portChange).commit()
        assertEquals(listOf(Triple(UdpCameraId.A, true, 46_010)), swaps)
        assertEquals(2, router.appliedRevision)
        assertEquals(portChange, router.receiverEffectiveProfiles)
    }

    @Test
    fun repeatedAppliedRevisionIsIdempotent() = runBlocking {
        var swaps = 0
        val initial = profiles()
        val router = UdpInputRouter(1, initial, UdpCameraRuntimeSwapper { _, _, _, _ -> swaps++ })
        router.prepareAndSwap(1, initial).commit()
        assertEquals(0, swaps)
        assertEquals(1, router.appliedRevision)
    }
}

internal fun profiles(): UdpInputProfileSet = UdpInputProfileSet(
    profile(UdpCameraId.A, 46_001), profile(UdpCameraId.B, 46_002),
)

private fun profile(camera: UdpCameraId, port: Int) = UdpInputProfile(
    camera, true, null, port, TransportMode.STRUCTURED_CHUNKED,
    PayloadProfile.Gray8(1, 1), ApplicationFramingProfile(),
    TransportLimits(maxFrameBytes = 1024, maxChunkPayloadBytes = 128),
)
