package com.filamentvision.backend.input.runtime

import com.filamentvision.backend.BackendComposition
import com.filamentvision.backend.parseBackendOptions
import com.filamentvision.backend.application.*
import com.filamentvision.backend.input.profile.RaspberryPiUdpInputProfile
import com.filamentvision.backend.input.profile.copy
import com.filamentvision.backend.simulator.fixtures.*
import com.filamentvision.core.frame.CameraId
import com.filamentvision.core.runtime.StartOutcome
import java.net.DatagramSocket
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProductionUdpCompositionTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun productionHasOneNeutralIdentityAndOnePreviewSlot() = runBlocking {
        val c = BackendComposition.open(path("one-source.db"), path("one-source-objects"))
        try {
            assertEquals("filamentvision-1", c.deviceId)
            assertEquals(setOf(CameraId("camera")), c.previewKeys.map { it.cameraId }.toSet())
            assertEquals(1, c.previewKeys.size)
            assertTrue(c.inputSource is RaspberryPiUdpInputSource)
            assertNull(c.applied.legacy)
            assertNull(c.application.configuration(c.deviceId).saved!!.configuration.legacy)
            assertEquals(setOf("camera"), c.metrics.snapshot().cameras.keys.map { it.cameraId.value }.toSet())
        } finally { c.close() }
    }

    @Test fun supplyingDispatchersDoesNotSelectASyntheticProducer() = runBlocking {
        val c = BackendComposition.open(path("dispatcher.db"), path("dispatcher-objects"),
            dispatcher = Dispatchers.Default, ioDispatcher = Dispatchers.IO)
        try {
            assertTrue("Every production overload must use the real single UDP source", c.inputSource is RaspberryPiUdpInputSource)
        } finally { c.close() }
    }

    @Test fun productionCliRejectsSimulationScenario() {
        assertThrows(IllegalArgumentException::class.java) {
            parseBackendOptions(arrayOf("--scenario", "NORMAL"))
        }
    }

    @Test fun idleAndDisabledMonitoringNeverCreateSamplesOrOpenAReceiver() = runBlocking {
        val c = BackendComposition.open(path("production.db"), path("objects"))
        val source = c.inputSource as RaspberryPiUdpInputSource
        try {
            assertEquals(RaspberryPiUdpInputProfile.defaults(), source.effectiveProfile)
            assertEquals(0, source.activeProducerCount)
            assertNull(c.application.latest(c.deviceId))
            assertEquals(0L, c.previews.metrics.publishedFrames)
            assertEquals(StartOutcome.STARTED, c.application.start(c.deviceId, "disabled-start", null, null).outcome)
            delay(50)
            assertEquals(1, source.activeProducerCount)
            assertEquals(0, source.activeReceiverCount)
            assertEquals(UdpOperationalStatus.DISABLED, source.operationalStatus().status)
            assertEquals(0L, c.runtime.state.value.statistics.sampleCount)
            assertNull(c.application.latest(c.deviceId))
            assertEquals(0L, c.previews.metrics.publishedFrames)
            c.application.stop(c.deviceId, "disabled-stop")
        } finally { c.close() }
        assertEquals(0, source.activeProducerCount)
    }

    @Test fun simulatorRemainsAnExplicitSingleStreamTestFixture() = runBlocking {
        val c = BackendComposition.open(path("sim.db"), path("sim-objects"),
            inputSourceFactory = SimulatorBackendInputSourceFactory)
        try {
            assertEquals(0, c.source.activeProducerCount)
            c.application.start(c.deviceId, "sim-start", null, null)
            withTimeout(3000) { while (c.application.latest(c.deviceId) == null) delay(5) }
            val measurement = c.application.latest(c.deviceId)!!.measurement
            assertEquals("camera", measurement.evidence.single().cameraId.value)
            assertEquals(1.75, measurement.diameterMm, 1e-9)
        } finally { c.close() }
    }

    @Test fun silentAppliedPiWaitsWithOneProducerAndCloseReleasesItsPortBeforeReturn() = runBlocking {
        val now = AtomicLong(0)
        val port = DatagramSocket(0).use { it.localPort }
        val c = BackendComposition.open(path("restore.db"), path("restore-objects"),
            dispatcher = Dispatchers.Default, monotonicMillis = now::get)
        val source = c.inputSource as RaspberryPiUdpInputSource
        val profile = RaspberryPiUdpInputProfile.defaults().copy(enabled = true, listenPort = port)
        val service = checkNotNull(c.udpInputService)
        val saved = service.save(1, profile)
        assertEquals(RaspberryPiUdpApplyOutcome.APPLIED, service.apply(saved.revision).outcome)
        try {
            val starts = coroutineScope { (1..12).map { async { c.application.start(c.deviceId, "start", null, null) } }.awaitAll() }
            assertEquals(1, starts.map { it.session!!.id }.toSet().size)
            assertEquals(1, source.activeProducerCount)
            assertEquals(1, source.activeReceiverCount)
            assertEquals(1L, source.totalProducerStarts)
            now.set(2000)
            assertEquals(UdpOperationalStatus.WAITING_FOR_RASPBERRY_PI, source.operationalStatus().status)
            assertNull(c.application.latest(c.deviceId))
            assertEquals(0L, c.runtime.state.value.statistics.sampleCount)
            assertEquals(0L, c.previews.metrics.publishedFrames)
        } finally { c.close() }
        assertEquals(0, source.activeReceiverCount)
        assertEquals(0, source.activeProducerCount)
        assertEquals(0, source.pendingInputCount)
        DatagramSocket(port).close()
        val reopened = BackendComposition.open(path("restore.db"), path("restore-objects-2"))
        try {
            assertEquals(saved.revision, checkNotNull(reopened.udpInputRouter).appliedRevision)
            assertEquals(profile, (reopened.inputSource as RaspberryPiUdpInputSource).effectiveProfile)
            assertEquals(saved.revision, reopened.udpProfileRepository.state().applied.revision)
        } finally { reopened.close() }
    }

    @Test fun inspectorLeaseDoesNotStartAProducerOrChangeConfiguration() = runBlocking {
        val c = BackendComposition.open(path("legacy-api.db"), path("legacy-api-objects"))
        try {
            val before = c.udpProfileRepository.state()
            val lease = c.application.createPacketInspectorLease(c.deviceId, 64)
            val failure = assertThrows(ApplicationFailure::class.java) { runBlocking {
                c.application.packetInspectorSnapshot(c.deviceId, lease.id, 64)
            } }
            assertEquals(ApplicationFailureCode.NOT_FOUND, failure.code)
            c.application.releasePacketInspectorLease(c.deviceId, lease.id)
            val source = c.inputSource as RaspberryPiUdpInputSource
            assertEquals(0, source.activeProducerCount)
            assertEquals(0, source.activeReceiverCount)
            assertEquals(0L, source.totalProducerStarts)
            assertEquals(before, c.udpProfileRepository.state())
        } finally { c.close() }
    }

    @Test fun compositionCloseReleasesInspectorLeaseAndCannotReenableIt() = runBlocking {
        val c = BackendComposition.open(path("inspector-close.db"), path("inspector-close-objects"))
        val inspector = (c.inputSource as RaspberryPiUdpInputSource).packetInspector
        inspector.createLease(64)
        c.close(); c.close()
        assertFalse(inspector.captureEnabled())
        assertThrows(IllegalStateException::class.java) { inspector.createLease(64) }
        Unit
    }

    private fun path(name: String) = temporary.root.toPath().resolve(name)
}
