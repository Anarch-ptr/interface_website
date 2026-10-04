package com.filamentvision.backend.persistence.sqlite

import com.filamentvision.backend.input.network.*
import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EffectiveUdpNetworkPlanCodecTest {
    @Test
    fun fullProvenanceRoundTripsWithNumericSourceOrderingAndImmutableSet() {
        val plan = EffectiveUdpNetworkPlan(
            "10.0.0.2", "wifi-id", "backend.local", "10.0.0.2", "pi.local",
            linkedSetOf("192.168.1.20", "10.0.0.3", "10.0.0.2"),
            ResolutionStatus.RESOLVED, ResolutionStatus.RESOLVED,
            101, 102, 103, 104, 7,
        )
        val decoded = EffectiveUdpNetworkPlanCodec.decode(EffectiveUdpNetworkPlanCodec.encode(plan))
        assertEquals(plan.copy(resolvedSourceIpv4s = linkedSetOf("10.0.0.2", "10.0.0.3", "192.168.1.20")), decoded)
        assertEquals(listOf("10.0.0.2", "10.0.0.3", "192.168.1.20"), decoded.resolvedSourceIpv4s.toList())
        assertThrows(UnsupportedOperationException::class.java) {
            (decoded.resolvedSourceIpv4s as MutableSet).add("10.0.0.4")
        }
    }

    @Test
    fun inactivePlanRoundTripsNullableFieldsAndGenerationZero() {
        val inactive = EffectiveUdpNetworkPlan.inactive(55)
        assertEquals(inactive, EffectiveUdpNetworkPlanCodec.decode(EffectiveUdpNetworkPlanCodec.encode(inactive)))
    }

    @Test
    fun rejectsExcessiveSourceSetAndBoundedStringsBeforeEncoding() {
        val base = EffectiveUdpNetworkPlan.inactive(1)
        assertThrows(IllegalArgumentException::class.java) {
            EffectiveUdpNetworkPlanCodec.encode(base.copy(resolvedSourceIpv4s = (1..17).map { "10.0.0.$it" }.toSet()))
        }
        assertThrows(IllegalArgumentException::class.java) {
            EffectiveUdpNetworkPlanCodec.encode(base.copy(configuredDestination = "a".repeat(254)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            EffectiveUdpNetworkPlanCodec.encode(base.copy(resolutionGeneration = -1))
        }
    }

    @Test
    fun rejectsUnknownVersionTruncationTrailingBytesAndCorruptAddressWithoutMutation() {
        val valid = EffectiveUdpNetworkPlanCodec.encode(EffectiveUdpNetworkPlan.inactive(1))
        val before = valid.copyOf()
        val corruptVersion = valid.copyOf().also { ByteBuffer.wrap(it).putInt(99) }
        listOf(byteArrayOf(), valid.copyOf(valid.size - 1), valid + 0, corruptVersion).forEach {
            assertThrows(IllegalArgumentException::class.java) { EffectiveUdpNetworkPlanCodec.decode(it) }
        }
        assertArrayEquals(before, valid)
        assertTrue(valid.size < 2_048)
    }
}
