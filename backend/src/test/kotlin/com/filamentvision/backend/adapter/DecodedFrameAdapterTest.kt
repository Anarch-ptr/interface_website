package com.filamentvision.backend.adapter

import com.filamentvision.core.frame.CameraId
import com.filamentvision.core.frame.FrameDiagnostics
import com.filamentvision.core.frame.FrameLease
import com.filamentvision.core.frame.PixelFormat
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class DecodedFrameAdapterTest {
    // Mutation caught: dropping or misrouting image and capture metadata at the adapter boundary.
    @Test fun mapsDecodedPixelsAndMetadataExactly() {
        val diagnostics = FrameDiagnostics(sourceDroppedFrames = 7, note = "late")
        val releases = AtomicInteger()
        val lease = FrameLease { releases.incrementAndGet() }
        val frame = DecodedFrameAdapter.toFrame(
            image = DecodedImage(2, 2, intArrayOf(0x01020304, 0x11223344, -1, 0)),
            cameraId = CameraId("B"), capturedAtEpochMillis = 1234,
            receivedAtMonotonicNanos = 5678, orientationDegrees = 90,
            mirrored = true, sequence = 42, diagnostics = diagnostics, lease = lease,
        )
        assertEquals(CameraId("B"), frame.cameraId)
        assertEquals(1234L, frame.capturedAtEpochMillis)
        assertEquals(5678L, frame.receivedAtMonotonicNanos)
        assertEquals(2, frame.width)
        assertEquals(2, frame.height)
        assertEquals(PixelFormat.ARGB32, frame.pixelFormat)
        assertEquals(listOf(0x01020304, 0x11223344, -1, 0), (0 until frame.pixels.count).map(frame.pixels::get))
        assertEquals(90, frame.orientationDegrees)
        assertEquals(true, frame.mirrored)
        assertEquals(42L, frame.sequence)
        assertSame(diagnostics, frame.diagnostics)
        assertSame(lease, frame.lease)
        frame.close()
        assertEquals(1, releases.get())
    }

    // Mutation caught: sharing the externally owned decoded array with Core consumers.
    @Test fun copiesExternalPixelsAtIngress() {
        val external = intArrayOf(11, 22, 33, 44)
        val frame = DecodedFrameAdapter.toFrame(
            image = DecodedImage(2, 2, external), cameraId = CameraId("A"),
            capturedAtEpochMillis = 1, receivedAtMonotonicNanos = 2, sequence = 3,
        )
        external[0] = 999
        external[3] = 999
        assertEquals(11, frame.pixels[0])
        assertEquals(44, frame.pixels[3])
    }
}
