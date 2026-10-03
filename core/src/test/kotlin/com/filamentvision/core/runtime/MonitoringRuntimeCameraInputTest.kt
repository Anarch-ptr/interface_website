package com.filamentvision.core.runtime

import com.filamentvision.core.config.MonitoringConfiguration
import com.filamentvision.core.config.VisionConfiguration
import com.filamentvision.core.frame.CameraFrame
import com.filamentvision.core.frame.CameraId
import com.filamentvision.core.frame.FrameLease
import com.filamentvision.core.input.CameraInput
import com.filamentvision.core.input.CameraInputSource
import com.filamentvision.core.input.DirectDiameterEncoding
import com.filamentvision.core.input.DirectDiameterEvidence
import com.filamentvision.core.input.DirectDiameterUnit
import com.filamentvision.core.input.ImageInputDiagnostics
import com.filamentvision.core.input.ImageInputState
import com.filamentvision.core.model.MeasurementStatus
import com.filamentvision.core.vision.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MonitoringRuntimeCameraInputTest {
    @Test
    fun oneImageImmediatelyProducesOneNeutralVisionMeasurementWithoutPairing() = runTest {
        var releases = 0
        val source = MixedSource()
        val sink = RecordingSink()
        val runtime = MonitoringRuntime(
            scope = backgroundScope,
            source = source,
            vision = Harness.config,
            targetDiameter = .4,
            persistence = sink,
            eventSink = sink,
        )
        try {
            runtime.start(StartMonitoringCommand("start"))
            source.channel.send(CameraInput.Image(Harness.frame(3, "camera", lease = FrameLease { releases++ })))
            runCurrent() // No clock advance, second camera, or pairing timeout.
            val latest = requireNotNull(runtime.state.value.latestMeasurement)
            assertEquals(MeasurementStatus.NORMAL, latest.status)
            assertNull(latest.legacyFusion)
            assertEquals(.4, latest.diameterMm, 0.0)
            assertEquals(1.0, latest.confidence, 0.0)
            assertEquals(102L, latest.timestamp)
            val evidence = latest.evidence.single()
            assertEquals(CameraId("camera"), evidence.cameraId)
            assertEquals(CameraMeasurementSource.VISION, evidence.source)
            assertEquals(3L, evidence.sequence)
            assertEquals(2, evidence.leftEdge)
            assertEquals(6, evidence.rightEdge)
            assertEquals(1, releases)
            assertEquals(1L, runtime.state.value.visionDiagnostics.processedFrames)
            assertEquals(0L, runtime.state.value.visionDiagnostics.rejectedFrames)
            assertEquals(1L, runtime.state.value.statistics.sampleCount)
            runtime.stop(StopMonitoringCommand("stop"))
            assertEquals(listOf(latest), sink.measurements)
            assertEquals(listOf("measurements", "finalize", "event"), sink.order)
        } finally { runtime.close() }
    }

    @Test fun silenceAndNoEdgesNeverProduceSyntheticSamplesOrAlarms() = runTest {
        val source = MixedSource()
        val sink = RecordingSink()
        val runtime = runtime(backgroundScope, source, sink)
        var releases = 0
        try {
            runtime.start(StartMonitoringCommand("start"))
            advanceTimeBy(10_000); runCurrent()
            assertNull(runtime.state.value.latestMeasurement)
            source.channel.send(CameraInput.Image(Harness.frame(0, "camera", width = 0, lease = FrameLease { releases++ })))
            runCurrent()
            advanceTimeBy(10_000); runCurrent()
            assertNull(runtime.state.value.latestMeasurement)
            assertEquals(0L, runtime.state.value.statistics.sampleCount)
            assertEquals(1, releases)
            assertEquals(1L, runtime.state.value.visionDiagnostics.processedFrames)
            assertEquals(1L, runtime.state.value.visionDiagnostics.rejectedFrames)
            assertEquals(DetectionError.NO_EDGES, runtime.state.value.visionDiagnostics.lastError)
            runtime.stop(StopMonitoringCommand("stop"))
            assertTrue(sink.measurements.isEmpty())
            assertTrue(sink.alarms.isEmpty())
            assertEquals(0L, sink.finalized.single().statistics.sampleCount)
            assertEquals(listOf("finalize", "event"), sink.order)
        } finally { runtime.close() }
    }

    @Test fun directDiameterInputCannotBecomeAnOfficialProductionSample() = runTest {
        val source = MixedSource()
        val sink = RecordingSink()
        val runtime = runtime(backgroundScope, source, sink)
        try {
            runtime.start(StartMonitoringCommand("start"))
            source.channel.send(CameraInput.DirectDiameter(DirectDiameterEvidence(
                CameraId("camera"), 1.8, DirectDiameterEncoding.ASCII, DirectDiameterUnit.MM,
                1.8, null, 999_999, 50_000_000, 9_999_999_999, 2,
            )))
            runCurrent()
            assertNull(runtime.state.value.latestMeasurement)
            assertEquals(0L, runtime.state.value.statistics.sampleCount)
            assertEquals(MonitoringLifecycle.RUNNING, runtime.state.value.lifecycle)
            runtime.stop(StopMonitoringCommand("stop"))
            assertTrue(sink.measurements.isEmpty())
        } finally { runtime.close() }
    }

    @Test fun injectedRoiAnalyzerUsesOneConfigurationAndKeepsGlobalEvidenceCoordinates() = runTest {
        val source = MixedSource()
        val sink = RecordingSink()
        val allocations = mutableListOf<Int>()
        val roi = RoiVisionProcessor(allocationObserver = { allocations += it })
        val configuration = Harness.config.copy(calibration = Harness.config.calibration.copy(
            roiLeft = 1, roiTop = 1, roiRight = 7, roiBottom = 3,
        ))
        var observed: VisionResult? = null
        val analyzer = object : VisionAnalyzer {
            override fun process(frame: CameraFrame, configuration: VisionConfiguration, decodeNanos: Long): VisionResult =
                roi.process(frame, configuration, decodeNanos).also { observed = it }
        }
        val runtime = MonitoringRuntime(backgroundScope, source, configuration, .4, sink, sink, processor = analyzer)
        try {
            runtime.start(StartMonitoringCommand("start"))
            source.channel.send(CameraInput.Image(Harness.frame(0)))
            runCurrent()
            assertEquals(listOf(12, 12, 12, 12), allocations)
            assertEquals(6, observed!!.rawStage.width)
            assertEquals(2, observed!!.rawStage.height)
            val evidence = runtime.state.value.latestMeasurement!!.evidence.single()
            assertSame(observed!!.measurement, evidence)
            assertEquals(listOf(1, 1, 7, 3), listOf(evidence.roiLeft, evidence.roiTop, evidence.roiRight, evidence.roiBottom))
            assertEquals(2, evidence.leftEdge)
            assertEquals(6, evidence.rightEdge)
            assertEquals(.4, runtime.state.value.latestMeasurement!!.diameterMm, 0.0)
        } finally { runtime.close() }
    }

    @Test fun invalidCalibrationIsCountedWithoutReplacingLastValidSampleAndResetsOnRestart() = runTest {
        val source = MixedSource()
        val sink = RecordingSink()
        val runtime = runtime(backgroundScope, source, sink)
        try {
            runtime.start(StartMonitoringCommand("first"))
            source.channel.send(CameraInput.Image(Harness.frame(0)))
            source.channel.send(CameraInput.Image(Harness.frame(1, width = 0)))
            runCurrent()
            assertEquals(0L, runtime.state.value.latestMeasurement!!.evidence.single().sequence)
            assertEquals(2L, runtime.state.value.visionDiagnostics.processedFrames)
            assertEquals(1L, runtime.state.value.visionDiagnostics.rejectedFrames)
            runtime.stop(StopMonitoringCommand("stop-first"))
            assertTrue(runtime.configureWhenStopped(MonitoringConfiguration(
                vision = Harness.config.copy(calibration = Harness.config.calibration.copy(mmPerPixel = 0.0)),
            )))
            runtime.start(StartMonitoringCommand("second"))
            assertNull(runtime.state.value.latestMeasurement)
            assertEquals(0L, runtime.state.value.visionDiagnostics.rejectedFrames)
            source.channel.send(CameraInput.Image(Harness.frame(2)))
            runCurrent()
            assertNull(runtime.state.value.latestMeasurement)
            assertEquals(DetectionError.INVALID_CALIBRATION, runtime.state.value.visionDiagnostics.lastError)
            assertEquals(1L, runtime.state.value.visionDiagnostics.rejectedFrames)
            runtime.stop(StopMonitoringCommand("stop-second"))
            assertEquals(1, sink.measurements.size)
            assertEquals(listOf(1L, 0L), sink.finalized.map { it.statistics.sampleCount })
        } finally { runtime.close() }
    }

    @Test fun unexpectedCameraIdentityCannotIntroduceASecondVisionStream() = runTest {
        val source = MixedSource()
        val sink = RecordingSink()
        val runtime = runtime(backgroundScope, source, sink)
        var releases = 0
        try {
            runtime.start(StartMonitoringCommand("start"))
            source.channel.send(CameraInput.Image(Harness.frame(0)))
            source.channel.send(CameraInput.Image(Harness.frame(1, "B", lease = FrameLease { releases++ })))
            runCurrent()
            assertEquals(MonitoringLifecycle.ERROR, runtime.state.value.lifecycle)
            assertEquals(1, releases)
            assertEquals(1, sink.measurements.size)
            assertEquals("camera", sink.measurements.single().evidence.single().cameraId.value)
            assertEquals(FinalizationReason.INTERRUPTED, sink.finalized.single().reason)
            assertEquals(0, source.activeProducerCount)
        } finally { runtime.close() }
    }

    private fun runtime(scope: kotlinx.coroutines.CoroutineScope, source: CameraInputSource, sink: RecordingSink) = MonitoringRuntime(
        scope = scope, source = source,
        vision = Harness.config,
        targetDiameter = .4, persistence = sink, eventSink = sink,
    )

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
