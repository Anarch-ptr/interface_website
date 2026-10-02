package com.filamentvision.backend.input.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class UdpReceiveBufferOwnershipTest {
    @Test
    fun ownedCopySurvivesReuseAndBorrowedAccessExpiresAfterCallback() {
        val reusable = byteArrayOf(1, 2, 3)
        val borrowed = BorrowedDatagramBytes(reusable, 3)
        val owned = borrowed.copyOwned()
        borrowed.invalidate()

        reusable.fill(9)

        assertArrayEquals(byteArrayOf(1, 2, 3), owned)
        assertThrows(IllegalStateException::class.java) { borrowed.copyOwned() }
        assertThrows(IllegalStateException::class.java) { borrowed.withBytes { _, _ -> Unit } }
    }
}
