package com.filamentvision.core.runtime

import com.filamentvision.core.frame.CameraId
import com.filamentvision.core.frame.FrameLease
import com.filamentvision.core.fusion.LatestCompatiblePairingStrategy
import com.filamentvision.core.input.CameraInput
import com.filamentvision.core.input.CameraInputSource
import com.filamentvision.core.input.DirectDiameterEncoding
import com.filamentvision.core.input.DirectDiameterEvidence
import com.filamentvision.core.input.DirectDiameterUnit
import com.filamentvision.core.input.ImageInputDiagnostics
import com.filamentvision.core.input.ImageInputState
import com.filamentvision.core.model.FusionConfiguration
import com.filamentvision.core.vision.CameraMeasurementSource
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MonitoringRuntimeCameraInputTest {
    @Test
    fun mixedInputsRunVisionOnlyForImagesAndPairByBackendMonotonicTime() = runTest {
        var releases = 0
        val source = MixedSource()
        val sink = RecordingSink()
        val runtime = MonitoringRuntime(
            scope = backgroundScope,
            source = source,
            configurations = mapOf(CameraId("A") to Harness.config),
            pairingStrategy = LatestCompatiblePairingStrategy(CameraId("A"), CameraId("B")),
            fusionConfiguration = FusionConfiguration(.25, .75, .7, 100),
            targetDiameter = 1.75,
            persistence = sink,
            eventSink = sink,
            directDiameterFusionConfidence = mapOf(CameraId("B") to .8),
        )
        runtime.start(StartMonitoringCommand("start"))
        source.channel.send(CameraInput.Image(Harness.frame(1, "A", lease = FrameLease { releases++ })))
        source.channel.send(
            CameraInput.DirectDiameter(
                DirectDiameterEvidence(
                    CameraId("B"), 1.8, DirectDiameterEncoding.ASCII, DirectDiameterUnit.MM,
                    1.8, null, 999_999, 50_000_000, 9_999_999_999, 2,
                ),
            ),
        )
        runCurrent()

        val latest = runtime.state.value.latestMeasurement!!
        assertEquals(CameraMeasurementSource.VISION, latest.cameraA!!.source)
        assertEquals(CameraMeasurementSource.DIRECT_DIAMETER, latest.cameraB!!.source)
        assertEquals(1.45, latest.fusedDiameter, 0.000001)
        assertEquals(1, releases)
        assertEquals(1, runtime.state.value.activeProducerCount)
        runtime.close()
        assertEquals(0, source.activeProducerCount)
    }

    private class MixedSource : CameraInputSource {
        val channel = Channel<CameraInput>(Channel.UNLIMITED)
        override val inputs = channel.receiveAsFlow()
        override val state = MutableStateFlow(ImageInputState.IDLE)
        override val diagnostics = MutableStateFlow(ImageInputDiagnostics())
        override var activeProducerCount = 0
            private set

        override suspend fun start() {
            activeProducerCount = 1
            state.value = ImageInputState.RUNNING
        }

        override suspend fun stop() {
            activeProducerCount = 0
            state.value = ImageInputState.IDLE
        }
    }
}
