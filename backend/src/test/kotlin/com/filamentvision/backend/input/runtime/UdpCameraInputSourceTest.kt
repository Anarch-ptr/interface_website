package com.filamentvision.backend.input.runtime

import com.filamentvision.backend.input.profile.*
import com.filamentvision.core.frame.*
import com.filamentvision.core.input.CameraInput
import com.filamentvision.core.input.DirectDiameterEncoding
import com.filamentvision.core.input.DirectDiameterEvidence
import com.filamentvision.core.input.DirectDiameterUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class UdpCameraInputSourceTest {
    @Test
    fun startIsIdempotentAndOverallProducerReservationIsZeroOrOne() = runTest {
        val source = UdpCameraInputSource(disabledProfiles())
        source.start(); source.start()
        assertEquals(1, source.activeProducerCount)
        assertEquals(1, source.totalProducerStarts)
        source.stop(); source.stop()
        assertEquals(0, source.activeProducerCount)
    }

    @Test
    fun keyedMailboxReplacesOnlyOlderSameCameraBeforeAdmission() = runTest {
        val source = UdpCameraInputSource(disabledProfiles())
        val admitted = CompletableDeferred<CameraInput>()
        val hold = CompletableDeferred<Unit>()
        backgroundScope.launch { source.inputs.collect { admitted.complete(it); hold.await() } }
        source.offerInput(direct(UdpCameraId.A, 1))
        runCurrent(); admitted.await()

        var releases = 0
        source.offerInput(image(UdpCameraId.A, 2, FrameLease { releases++ }))
        source.offerInput(direct(UdpCameraId.B, 3))
        source.offerInput(image(UdpCameraId.A, 4, FrameLease { releases++ }))
        assertEquals(1, releases)
        assertEquals(1, source.preAdmissionDroppedInputs(UdpCameraId.A))
        assertEquals(0, source.preAdmissionDroppedInputs(UdpCameraId.B))
        assertEquals(2, source.pendingInputCount)
        hold.complete(Unit)
        source.stop()
    }

    private fun disabledProfiles() = UdpInputProfileSet(profile(UdpCameraId.A), profile(UdpCameraId.B))
    private fun profile(id: UdpCameraId) = UdpInputProfile(
        id, false, null, if (id == UdpCameraId.A) 45_101 else 45_102,
        TransportMode.SINGLE_DATAGRAM,
        PayloadProfile.Diameter(DiameterValueEncoding.ASCII, DiameterByteOrder.BIG_ENDIAN),
        ApplicationFramingProfile(), TransportLimits(),
    )

    private fun direct(id: UdpCameraId, sequence: Long) = CameraInput.DirectDiameter(
        DirectDiameterEvidence(
            CameraId(id.name), 1.0, DirectDiameterEncoding.ASCII, DirectDiameterUnit.MM,
            1.0, null, sequence, sequence, null, sequence,
        ),
    )

    private fun image(id: UdpCameraId, sequence: Long, lease: FrameLease) = CameraInput.Image(
        CameraFrame(CameraId(id.name), sequence, sequence, 1, 1, PixelFormat.ARGB32, PixelBuffer.ints(intArrayOf(-1)), 0, false, sequence, lease = lease),
    )
}
