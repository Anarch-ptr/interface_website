package com.filamentvision.backend.input.runtime

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DecodedFramePermitTest {
    @Test fun oneConcurrentAcquisitionWinsUntilItsOwnerCloses() {
        val permit = DecodedFramePermit()
        val executor = Executors.newFixedThreadPool(16)
        val start = CountDownLatch(1)
        try {
            val attempts = (1..16).map { executor.submit<DecodedFrameLease?> {
                check(start.await(3, TimeUnit.SECONDS))
                permit.tryAcquire()
            } }
            start.countDown()
            val winners = attempts.mapNotNull { it.get(3, TimeUnit.SECONDS) }
            assertEquals(1, winners.size)
            assertNull(permit.tryAcquire())
            winners.single().close()
            checkNotNull(permit.tryAcquire()).close()
        } finally { start.countDown(); executor.shutdownNow() }
    }

    @Test fun closingOldLeaseTwiceCannotReleaseItsSuccessor() {
        val permit = DecodedFramePermit()
        val old = checkNotNull(permit.tryAcquire())
        old.close()
        val next = checkNotNull(permit.tryAcquire())
        old.close()
        assertNull(permit.tryAcquire())
        next.close(); next.close()
        checkNotNull(permit.tryAcquire()).close()
    }
}
