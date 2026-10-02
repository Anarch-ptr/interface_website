package com.filamentvision.backend.input.reassembly

import com.filamentvision.backend.input.profile.UdpCameraId
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.InetSocketAddress

class SingleDatagramFrameReassemblerTest {
    @Test
    fun acceptedDatagramCompletesOneFrameWithReceiveTimestampProvenance() {
        val reassembler = SingleDatagramFrameReassembler(maxFrameBytes = 4)
        val result = reassembler.accept(
            SingleDatagramInput(
                cameraId = UdpCameraId.A,
                source = InetSocketAddress("127.0.0.1", 9_000),
                payload = byteArrayOf(1, 2, 3),
                receivedAtMonotonicNanos = 123,
                receivedAtEpochMillis = 456,
            ),
        ) as FrameReassemblyResult.Completed

        assertArrayEquals(byteArrayOf(1, 2, 3), result.frame.payload)
        assertEquals(123, result.frame.receivedAtMonotonicNanos)
        assertEquals(456, result.frame.receivedAtEpochMillis)
        assertEquals(123, result.frame.completedAtMonotonicNanos)
        assertNull(result.frame.sourceTimestampMicros)
        assertEquals(0, reassembler.inFlightFrameCount)
        assertEquals(0, reassembler.bufferedBytes)
    }

    @Test
    fun payloadOverConfiguredMaximumIsDroppedWithoutRetainedState() {
        val reassembler = SingleDatagramFrameReassembler(maxFrameBytes = 2)
        val result = reassembler.accept(
            SingleDatagramInput(
                cameraId = UdpCameraId.B,
                source = InetSocketAddress("127.0.0.1", 9_001),
                payload = byteArrayOf(1, 2, 3),
                receivedAtMonotonicNanos = 1,
                receivedAtEpochMillis = 2,
            ),
        )

        assertEquals(
            FrameReassemblyResult.Dropped(FrameDropReason.OVERSIZED_FRAME),
            result,
        )
        assertEquals(0, reassembler.inFlightFrameCount)
        assertEquals(0, reassembler.bufferedBytes)
    }
}
