package com.filamentvision.core.snapshot

import com.filamentvision.core.frame.CameraId
import com.filamentvision.core.preview.*
import org.junit.Assert.*
import org.junit.Test

class LatestFrameSnapshotCacheTest {
    private val a = PreviewKey("device", CameraId("A"), PreviewStage.RAW)
    private val b = a.copy(cameraId = CameraId("B"))
    private val c = a.copy(stage = PreviewStage.EDGE)
    private fun frame(key: PreviewKey, size: Int) = EncodedSnapshot(key, 123, 45, "image/png", ByteArray(size) { it.toByte() })

    @Test fun entryEvictionUsesWriteOrderAndReadsDoNotRefreshIt() {
        val cache = LatestFrameSnapshotCache(2, 100)
        cache.put(frame(a, 1)); cache.put(frame(b, 1)); cache.get(a); cache.put(frame(c, 1))
        assertNull(cache.get(a)); assertNotNull(cache.get(b)); assertNotNull(cache.get(c))
        assertEquals(SnapshotCacheState(2, 2), cache.state())
    }
    @Test fun byteEvictionAndReplacementAccountExactlyAndKeepNewest() {
        val cache = LatestFrameSnapshotCache(3, 8)
        cache.put(frame(a, 3)); cache.put(frame(b, 3)); cache.put(frame(a, 5))
        assertEquals(SnapshotCacheState(2, 8), cache.state())
        cache.put(frame(a, 1)); assertEquals(SnapshotCacheState(2, 4), cache.state())
        cache.put(frame(c, 5)); assertNull(cache.get(b))
        assertEquals(SnapshotCacheState(2, 6), cache.state())
        cache.put(frame(a, 8)); assertNull(cache.get(c))
        assertEquals(SnapshotCacheState(1, 8), cache.state())
    }
    @Test fun oversizeRejectionPreservesPreviousValueAndAccounting() {
        val cache = LatestFrameSnapshotCache(2, 4)
        cache.put(frame(a, 3))
        assertThrows(IllegalArgumentException::class.java) { cache.put(frame(a, 5)) }
        assertEquals(3, cache.get(a)!!.sizeBytes); assertEquals(SnapshotCacheState(1, 3), cache.state())
    }
    @Test fun bytesAndMetadataAreOwnedAndDeviceCameraStageStayDistinct() {
        val cache = LatestFrameSnapshotCache(4, 20)
        val bytes = byteArrayOf(1, 2, 3)
        val value = EncodedSnapshot(a, 123, 45, "image/png", bytes)
        cache.put(value); bytes[0] = 9; cache.get(a)!!.copyBytes()[1] = 9
        listOf(b, c, a.copy(deviceId = "other")).forEach { cache.put(frame(it, 1)) }
        val read = cache.get(a)!!
        assertArrayEquals(byteArrayOf(1, 2, 3), read.copyBytes())
        assertEquals(a, read.key); assertEquals(123L, read.timestamp); assertEquals(45L, read.sourceSequence)
        assertEquals("image/png", read.mediaType); assertEquals(3, read.sizeBytes)
        assertEquals(SnapshotCacheState(4, 6), cache.state())
    }
    @Test fun policyHasFiveMinuteIndependentKeysAndRollbackDoesNotBypassCooldown() {
        var now = 1000L
        val policy = SnapshotPolicy(setOf(a, b)) { now }
        assertTrue(policy.tryAcquire(a)); now = 300999; assertFalse(policy.tryAcquire(a))
        assertTrue(policy.tryAcquire(b)); now = 301000; assertTrue(policy.tryAcquire(a))
        now = 0; assertFalse(policy.tryAcquire(a)); now = 600999; assertFalse(policy.tryAcquire(a))
        now = 601000; assertTrue(policy.tryAcquire(a)); assertTrue(policy.tryAcquire(b))
        assertThrows(IllegalArgumentException::class.java) { policy.tryAcquire(c) }
    }
    @Test fun invalidBoundsMetadataAndClockAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { LatestFrameSnapshotCache(0, 1) }
        assertThrows(IllegalArgumentException::class.java) { LatestFrameSnapshotCache(1, 0) }
        assertThrows(IllegalArgumentException::class.java) { EncodedSnapshot(a, -1, 0, "image/png", byteArrayOf(1)) }
        assertThrows(IllegalArgumentException::class.java) { EncodedSnapshot(a, 1, -1, "image/png", byteArrayOf(1)) }
        assertThrows(IllegalArgumentException::class.java) { EncodedSnapshot(a, 1, 1, "", byteArrayOf(1)) }
        assertThrows(IllegalArgumentException::class.java) { EncodedSnapshot(a, 1, 1, "image/png", byteArrayOf()) }
        assertThrows(IllegalArgumentException::class.java) { SnapshotPolicy(setOf(a)) { -1 }.tryAcquire(a) }
    }
}
