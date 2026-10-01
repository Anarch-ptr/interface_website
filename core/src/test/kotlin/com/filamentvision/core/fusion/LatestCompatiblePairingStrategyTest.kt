package com.filamentvision.core.fusion

import com.filamentvision.core.frame.CameraId
import com.filamentvision.core.vision.CameraMeasurement
import org.junit.Assert.*
import org.junit.Test

class LatestCompatiblePairingStrategyTest {
    private val aId = CameraId("left-optic")
    private val bId = CameraId("right-optic")

    @Test fun eachOfferUsesTheLatestCounterpartWithoutConsumingIt() {
        // Catches hard-coded IDs, waiting for two frames, consume-once or stale counterpart selection.
        val strategy = LatestCompatiblePairingStrategy(aId, bId)
        val a1 = m(aId, 1)
        val a2 = m(aId, 2)
        val b1 = m(bId, 3)
        val b2 = m(bId, 4)
        assertEquals(listOf(MeasurementPair(a1, null)), strategy.offer(a1))
        assertEquals(listOf(MeasurementPair(a2, null)), strategy.offer(a2))
        assertEquals(listOf(MeasurementPair(a2, b1)), strategy.offer(b1))
        assertEquals(listOf(MeasurementPair(a2, b2)), strategy.offer(b2))
        val a3 = m(aId, 5)
        assertEquals(listOf(MeasurementPair(a3, b2)), strategy.offer(a3))
    }

    @Test fun pairingDoesNotApplyTimestampOrConfidencePolicy() {
        // Catches Fusion policy leaking into pair selection; latest means latest offered, not timestamp max.
        val strategy = LatestCompatiblePairingStrategy(aId, bId)
        val b = m(bId, 1).copy(timestamp = 9000)
        assertEquals(listOf(MeasurementPair(null, b)), strategy.offer(b))
        val a = m(aId, 2).copy(timestamp = 1000, confidence = .2)
        assertEquals(listOf(MeasurementPair(a, b)), strategy.offer(a))
        val olderA = a.copy(timestamp = 500, sequence = 3)
        assertEquals(listOf(MeasurementPair(olderA, b)), strategy.offer(olderA))
    }

    @Test fun resetClearsBothRetainedSides() {
        // Catches a previous session's counterpart surviving reset.
        val strategy = LatestCompatiblePairingStrategy(aId, bId)
        val a = m(aId, 1)
        val b = m(bId, 2)
        strategy.offer(a)
        strategy.offer(b)
        strategy.reset()
        assertEquals(listOf(MeasurementPair(null, b)), strategy.offer(b))
        strategy.reset()
        assertEquals(listOf(MeasurementPair(a, null)), strategy.offer(a))
    }

    @Test fun unknownIdentityFailsWithoutReplacingStoredEvidence() {
        // Catches unknown IDs falling into a default B branch or mutating retained state.
        val strategy = LatestCompatiblePairingStrategy(aId, bId)
        val a = m(aId, 1)
        val b = m(bId, 2)
        strategy.offer(a)
        assertThrows(IllegalArgumentException::class.java) { strategy.offer(m(CameraId("unknown"), 3)) }
        assertEquals(listOf(MeasurementPair(a, b)), strategy.offer(b))
    }

    @Test fun ambiguousCameraIdentityIsRejected() {
        // Catches an impossible A/B configuration that would silently lose one side.
        assertThrows(IllegalArgumentException::class.java) { LatestCompatiblePairingStrategy(aId, aId) }
    }

    private fun m(id: CameraId, sequence: Long) = CameraMeasurement(id, 170.0, 1.7, .9, 1000, sequence, true)
}
