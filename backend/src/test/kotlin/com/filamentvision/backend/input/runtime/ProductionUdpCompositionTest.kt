package com.filamentvision.backend.input.runtime

import com.filamentvision.backend.BackendComposition
import com.filamentvision.backend.simulator.SimulatorImageInputSource
import java.net.DatagramSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProductionUdpCompositionTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun defaultCompositionUsesUdpAndNeverCreatesFakeSamples() = runBlocking {
        val c = BackendComposition.open(path("production.db"), path("objects"))
        try {
            assertTrue(c.inputSource is UdpCameraInputSource)
            assertFalse(c.inputSource is SimulatorImageInputSource)
            assertEquals(0, c.inputSource.activeProducerCount)
            assertNull(c.application.latest(c.deviceId))
        } finally { c.close() }
    }

    @Test fun simulatorRemainsAnExplicitTestFixture() = runBlocking {
        val c = BackendComposition.open(path("sim.db"), path("sim-objects"),
            inputSourceFactory = SimulatorBackendInputSourceFactory,
            dispatcher = Dispatchers.Default, ioDispatcher = Dispatchers.IO)
        try { assertTrue(c.inputSource is SimulatorImageInputSource) } finally { c.close() }
    }

    @Test fun appliedUdpRevisionIsRestoredAndSocketsAreClosedBeforeCompositionReturns() = runBlocking {
        val c = BackendComposition.open(path("restore.db"), path("restore-objects"))
        val revision = c.application.saveUdpInput(c.deviceId, "save", BackendComposition.defaultUdpInputProfiles().let {
            it.copy(cameraA = it.cameraA.copy(listenPort = freePort()), cameraB = it.cameraB.copy(listenPort = freePort()))
        }).savedRevision
        c.application.applyUdpInput(c.deviceId, "apply", revision, revision)
        c.close()
        val reopened = BackendComposition.open(path("restore.db"), path("restore-objects-2"))
        try {
            assertEquals(revision, reopened.udpInputRouter.appliedRevision)
            assertEquals(revision, reopened.application.udpInputConfiguration(reopened.deviceId).appliedRevision)
        } finally { reopened.close() }
    }

    private fun path(name: String) = temporary.root.toPath().resolve(name)
    private fun freePort() = DatagramSocket(0).use { it.localPort }
}
