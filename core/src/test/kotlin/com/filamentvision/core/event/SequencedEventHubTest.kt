package com.filamentvision.core.event

import com.filamentvision.core.alarm.*
import com.filamentvision.core.frame.CameraId
import com.filamentvision.core.fusion.FusionReason
import com.filamentvision.core.model.*
import com.filamentvision.core.repository.*
import com.filamentvision.core.runtime.*
import com.filamentvision.core.session.SessionStatistics
import com.filamentvision.core.vision.CameraMeasurement
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SequencedEventHubTest {
    @Test fun exactSuffixAndBoundaryAreOrderedAndDoNotExposeMutableStorage() {
        val hub = SequencedEventHub(setOf("device"), 3)
        repeat(5) { hub.publish(errorEvent()) }
        assertEquals(listOf(3L, 4L, 5L), available(hub, 2).events.map { it.sequence })
        assertEquals(listOf(5L), available(hub, 4).events.map { it.sequence })
        assertTrue(available(hub, 5).events.isEmpty())
        assertEquals(5L, available(hub, 5).latestSequence)
        val snapshot = available(hub, 2)
        hub.publish(errorEvent())
        assertEquals(listOf(3L, 4L, 5L), snapshot.events.map { it.sequence })
        (snapshot.events as? MutableList)?.clear()
        assertEquals(listOf(4L, 5L, 6L), available(hub, 3).events.map { it.sequence })
    }

    @Test fun expiredAndFutureCursorsAreExplicitIncludingEmptyDevice() {
        val hub = SequencedEventHub(setOf("device"), 2)
        assertEquals(EventReplay.Available(emptyList(), 0), hub.replayAfter("device", 0))
        assertEquals(EventReplay.FutureCursor(0), hub.replayAfter("device", 1))
        repeat(4) { hub.publish(errorEvent()) }
        assertEquals(EventReplay.SnapshotRequired(3, 4), hub.replayAfter("device", 1))
        assertEquals(listOf(3L, 4L), available(hub, 2).events.map { it.sequence })
        assertEquals(EventReplay.FutureCursor(4), hub.replayAfter("device", 5))
        assertThrows(IllegalArgumentException::class.java) { hub.replayAfter("device", -1) }
    }

    @Test fun replayRemainsBoundedAtOneAndNWithLatestAlwaysAvailable() {
        for (capacity in listOf(1, 7)) {
            val hub = SequencedEventHub(setOf("device"), capacity)
            repeat(10_000) {
                val published = hub.publish(errorEvent())
                assertEquals(it + 1L, published.sequence)
                assertTrue(hub.metrics("device").retained <= capacity)
            }
            assertEquals(EventHubMetrics(10_000, capacity, capacity), hub.metrics("device"))
            assertEquals(10_000L, available(hub, 9_999).events.single().sequence)
        }
    }

    @Test fun invalidCapacityOrUnknownDevicesCannotGrowStorage() {
        for (capacity in listOf(0, -1)) {
            assertThrows(IllegalArgumentException::class.java) { SequencedEventHub(setOf("device"), capacity) }
        }
        assertThrows(IllegalArgumentException::class.java) { SequencedEventHub(emptySet(), 1) }
        assertThrows(IllegalArgumentException::class.java) { SequencedEventHub(setOf(""), 1) }
        val devices = mutableSetOf("device")
        val hub = SequencedEventHub(devices, 1)
        devices.add("intruder")
        assertThrows(IllegalArgumentException::class.java) { hub.publish(errorEvent("intruder")) }
        assertThrows(IllegalArgumentException::class.java) { hub.replayAfter("intruder", 0) }
        assertThrows(IllegalArgumentException::class.java) { hub.metrics("intruder") }
        assertEquals(0L, hub.metrics("device").published)
    }

    @Test fun concurrentPublicationAllocatesUniqueOrderedSequencesPerDevice() = runBlocking {
        val hub = SequencedEventHub(setOf("device", "other"), 1_000)
        val published = coroutineScope {
            (0 until 8).map { worker -> async(Dispatchers.Default) {
                List(100) { hub.publish(errorEvent(if (worker % 2 == 0) "device" else "other")) }
            } }.awaitAll().flatten()
        }
        for (device in listOf("device", "other")) {
            assertEquals((1L..400L).toList(), published.filter { it.event.deviceId == device }.map { it.sequence }.sorted())
            assertEquals((1L..400L).toList(), (hub.replayAfter(device, 0) as EventReplay.Available).events.map { it.sequence })
        }
    }

    @Test fun independentDeviceWindowsNeverEvictEachOther() {
        val hub = SequencedEventHub(setOf("device", "other"), 2)
        hub.publish(errorEvent("other"))
        repeat(10) { hub.publish(errorEvent()) }
        assertEquals(1L, (hub.replayAfter("other", 0) as EventReplay.Available).events.single().sequence)
        assertEquals(EventReplay.SnapshotRequired(9, 10), hub.replayAfter("device", 0))
    }

    @Test fun allDomainPayloadsPreserveCaptureAndLifecycleEvidence() {
        val hub = SequencedEventHub(setOf("device"), 5)
        val expected = domainEvents()
        expected.forEach(hub::publish)
        val actual = available(hub, 0).events.map { it.event }
        assertEquals(expected, actual)
        val camera = (actual.first() as DomainEvent.Measurement).record.measurement.cameraA!!
        assertEquals(CameraId("camera-A"), camera.cameraId)
        assertEquals(1234L, camera.timestamp)
        assertEquals(987L, camera.sequence)
        assertEquals(5L, hub.metrics("device").published)
    }

    private fun available(hub: SequencedEventHub, after: Long) = hub.replayAfter("device", after) as EventReplay.Available
}

internal fun errorEvent(deviceId: String = "device") = DomainEvent.Error(deviceId, ErrorRecord("error", "session", 678, "SOURCE", "lost"))

internal fun domainEvents(): List<DomainEvent> {
    val camera = CameraMeasurement(CameraId("camera-A"), 20.0, 1.75, .95, 1234, 987, true)
    val measurement = OfficialMeasurement(camera, null, 1.75, 0.0, .95, MeasurementStatus.CAMERA_B_FAILURE, 1234, FusionReason.CAMERA_B_MISSING)
    val session = RuntimeSession("session", 2, 1000)
    return listOf(
        DomainEvent.Measurement("device", MeasurementRecord("m", "session", measurement, 7)),
        DomainEvent.Alarm("device", AlarmEventRecord("a", "session", AlarmTransition(AlarmState(), AlarmState(AlarmLevel.WARNING, AlarmDirection.HIGH, 2.1), 1234, 1.79))),
        DomainEvent.SessionStarted("device", session),
        DomainEvent.SessionFinalized("device", SessionFinalization(session, 2000, FinalizationReason.COMPLETED, SessionStatistics(sampleCount = 1))),
        errorEvent(),
    )
}
