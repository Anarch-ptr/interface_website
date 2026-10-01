package com.filamentvision.core.frame

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class CameraFrameTest {
    // Mutation caught: accepting a buffer with fewer or more pixels than the declared frame.
    @Test fun rejectsWrongPixelCount() {
        for (count in listOf(3, 5)) {
            assertThrows(IllegalArgumentException::class.java) {
                frame(width = 2, height = 2, pixels = PixelBuffer.ints(IntArray(count)))
            }
        }
    }

    // Mutation caught: allowing zero or negative dimensions, including when the count is zero.
    @Test fun rejectsNonPositiveDimensions() {
        for ((width, height) in listOf(0 to 2, -1 to 2, 2 to 0, 2 to -1)) {
            assertThrows(IllegalArgumentException::class.java) {
                frame(width = width, height = height)
            }
        }
    }

    // Mutation caught: accepting a non-quarter-turn orientation.
    @Test fun acceptsOnlyQuarterTurnOrientations() {
        for (orientation in listOf(0, 90, 180, 270)) {
            assertEquals(orientation, frame(orientationDegrees = orientation).orientationDegrees)
        }
        for (orientation in listOf(-90, 1, 360)) {
            assertThrows(IllegalArgumentException::class.java) {
                frame(orientationDegrees = orientation)
            }
        }
    }

    // Mutation caught: accepting blank camera identity.
    @Test fun rejectsBlankCameraId() {
        for (id in listOf("", " \t ")) {
            assertThrows(IllegalArgumentException::class.java) { CameraId(id) }
        }
    }

    // Mutation caught: discarding or replacing source metadata while constructing a frame.
    @Test fun retainsSourceMetadataAndPixelBufferIdentity() {
        val pixels = PixelBuffer.ints(intArrayOf(0x11223344, 0x55667788.toInt(), 0, -1))
        val diagnostics = FrameDiagnostics(sourceDroppedFrames = 2, note = "simulated dropout")
        val frame = CameraFrame(
            cameraId = CameraId("A"), capturedAtEpochMillis = 99,
            receivedAtMonotonicNanos = 100, width = 2, height = 2,
            pixelFormat = PixelFormat.ARGB32, pixels = pixels,
            orientationDegrees = 270, mirrored = true, sequence = 3,
            diagnostics = diagnostics,
        )
        assertEquals(CameraId("A"), frame.cameraId)
        assertEquals(99L, frame.capturedAtEpochMillis)
        assertEquals(100L, frame.receivedAtMonotonicNanos)
        assertEquals(2, frame.width)
        assertEquals(2, frame.height)
        assertEquals(PixelFormat.ARGB32, frame.pixelFormat)
        assertSame(pixels, frame.pixels)
        assertEquals(270, frame.orientationDegrees)
        assertEquals(true, frame.mirrored)
        assertEquals(3L, frame.sequence)
        assertEquals(diagnostics, frame.diagnostics)
    }

    // Mutation caught: accepting negative timestamps, sequence or dropped-frame count.
    @Test fun rejectsNegativeClockAndSequenceValues() {
        assertThrows(IllegalArgumentException::class.java) { frame(capturedAtEpochMillis = -1) }
        assertThrows(IllegalArgumentException::class.java) { frame(receivedAtMonotonicNanos = -1) }
        assertThrows(IllegalArgumentException::class.java) { frame(sequence = -1) }
        assertThrows(IllegalArgumentException::class.java) { FrameDiagnostics(sourceDroppedFrames = -1) }
        assertEquals(0L, frame(capturedAtEpochMillis = 0, receivedAtMonotonicNanos = 0, sequence = 0).sequence)
    }

    // Mutation caught: indexed pixels returning the wrong value or silently accepting an invalid index.
    @Test fun pixelsExposeReadOnlyIndexedValuesWithBounds() {
        val pixels = PixelBuffer.ints(intArrayOf(11, 22, 33, 44))
        assertEquals(4, pixels.count)
        assertEquals(11, pixels[0])
        assertEquals(44, pixels[3])
        assertThrows(IndexOutOfBoundsException::class.java) { pixels[-1] }
        assertThrows(IndexOutOfBoundsException::class.java) { pixels[4] }
    }

    // Mutation caught: repeated frame closure invoking the release callback more than once.
    @Test fun frameCloseDelegatesToLeaseOnlyOnce() {
        val releases = AtomicInteger()
        val frame = frame(lease = FrameLease { releases.incrementAndGet() })
        frame.close()
        frame.close()
        assertEquals(1, releases.get())
    }

    // Mutation caught: racing close calls both invoking the release callback.
    @Test fun concurrentLeaseCloseReleasesExactlyOnce() {
        val releases = AtomicInteger()
        val lease = FrameLease { releases.incrementAndGet() }
        val pool = Executors.newFixedThreadPool(8)
        try {
            val start = CountDownLatch(1)
            val jobs = (1..32).map {
                pool.submit { start.await(); lease.close() }
            }
            start.countDown()
            jobs.forEach { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, releases.get())
        } finally {
            pool.shutdownNow()
        }
    }

    private fun frame(
        width: Int = 2,
        height: Int = 2,
        pixels: PixelBuffer = PixelBuffer.ints(intArrayOf(1, 2, 3, 4)),
        orientationDegrees: Int = 0,
        capturedAtEpochMillis: Long = 99,
        receivedAtMonotonicNanos: Long = 100,
        sequence: Long = 3,
        lease: FrameLease = FrameLease(),
    ) = CameraFrame(
        cameraId = CameraId("A"), capturedAtEpochMillis = capturedAtEpochMillis,
        receivedAtMonotonicNanos = receivedAtMonotonicNanos, width = width, height = height,
        pixelFormat = PixelFormat.ARGB32, pixels = pixels,
        orientationDegrees = orientationDegrees, mirrored = false, sequence = sequence,
        diagnostics = FrameDiagnostics(), lease = lease,
    )
}
