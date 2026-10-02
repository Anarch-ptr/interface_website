package com.filamentvision.backend.input.inspector

import com.filamentvision.backend.input.profile.UdpCameraId
import java.net.InetSocketAddress
import org.junit.Assert.*
import org.junit.Test

class PacketInspectorTest {
    private var now = 1_000L
    private val inspector = PacketInspector(clockMillis = { now }, leaseDurationMillis = 500)

    @Test fun leaseLifecycleExpiryAndCameraIsolation() {
        val a = inspector.createLease(UdpCameraId.A, 128)
        inspector.captureRaw(UdpCameraId.A, byteArrayOf(1, 2), 2, endpoint(), 10, RawParseStatus.VALID)
        inspector.captureRaw(UdpCameraId.B, byteArrayOf(3), 1, endpoint(), 11, RawParseStatus.VALID)
        assertNotNull(inspector.latest(a.id, UdpCameraId.A))
        assertNull(inspector.latest(a.id, UdpCameraId.B))
        now += 501
        assertNull(inspector.latest(a.id, UdpCameraId.A))
        val next = inspector.createLease(UdpCameraId.A, 64)
        assertTrue(inspector.releaseLease(next.id, UdpCameraId.A))
        assertFalse(inspector.captureEnabled(UdpCameraId.A))
    }

    @Test fun latestOnlySequenceAndPreviewBounds() {
        listOf(64, 128, 256, 512).forEach { requested ->
            val lease = inspector.createLease(UdpCameraId.A, requested)
            val bytes = ByteArray(700) { it.toByte() }
            inspector.captureRaw(UdpCameraId.A, bytes, bytes.size, endpoint(), 10, RawParseStatus.VALID)
            val first = inspector.latest(lease.id, UdpCameraId.A)!!
            assertEquals(requested, first.raw.preview.size)
            inspector.captureRaw(UdpCameraId.A, byteArrayOf(9), 1, endpoint(), 11, RawParseStatus.SOURCE_IP_MISMATCH)
            val latest = inspector.latest(lease.id, UdpCameraId.A)!!
            assertTrue(latest.sequence > first.sequence)
            assertArrayEquals(byteArrayOf(9), latest.raw.preview)
            assertEquals(RawParseStatus.SOURCE_IP_MISMATCH, latest.raw.status)
            inspector.releaseLease(lease.id, UdpCameraId.A)
        }
        assertThrows(IllegalArgumentException::class.java) { inspector.createLease(UdpCameraId.A, 513) }
    }

    @Test fun reusingReceiverBackingArrayCannotMutateOwnedPreview() {
        val lease = inspector.createLease(UdpCameraId.A, 64)
        val reused = byteArrayOf(1, 2, 3, 4)
        inspector.captureRaw(UdpCameraId.A, reused, 4, endpoint(), 10, RawParseStatus.VALID)
        reused.fill(99)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), inspector.latest(lease.id, UdpCameraId.A)!!.raw.preview)
    }

    @Test fun parsedFrameReplacesOnlyBoundedParsedMetadataWithoutHistory() {
        val lease = inspector.createLease(UdpCameraId.B, 128)
        inspector.captureRaw(UdpCameraId.B, byteArrayOf(1), 1, endpoint(), 10, RawParseStatus.VALID,
            TransportInspection(4, 1, 3, 100, 90, 50))
        inspector.captureParsed(UdpCameraId.B, byteArrayOf(7, 8, 9), "GRAY8", "VALID", 2, 1, 11)
        val snapshot = inspector.latest(lease.id, UdpCameraId.B)!!
        assertEquals(4, snapshot.raw.transport!!.frameId)
        assertArrayEquals(byteArrayOf(7, 8, 9), snapshot.parsedFrame!!.preview)
        assertEquals(1, inspector.retainedSnapshotCount)
        assertTrue(inspector.retainedPreviewBytes <= 256)
    }

    @Test fun disabledCaptureDoesNoPreviewCopyOrSnapshotAllocation() {
        assertFalse(inspector.captureRaw(UdpCameraId.A, byteArrayOf(1, 2), 2, endpoint(), 10, RawParseStatus.VALID))
        assertEquals(0, inspector.previewCopies)
        assertEquals(0, inspector.retainedSnapshotCount)
    }

    private fun endpoint() = InetSocketAddress("127.0.0.1", 9000)
}
