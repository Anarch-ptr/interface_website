package com.filamentvision.backend.simulator

import com.filamentvision.backend.simulator.fixtures.*

import com.filamentvision.core.config.CalibrationProfile
import com.filamentvision.core.config.VisionConfiguration
import com.filamentvision.core.frame.CameraFrame
import com.filamentvision.core.frame.CameraId
import com.filamentvision.core.frame.PixelFormat
import com.filamentvision.core.fusion.FusionEngine
import com.filamentvision.core.fusion.FusionReason
import com.filamentvision.core.fusion.LatestCompatiblePairingStrategy
import com.filamentvision.core.model.FusionConfiguration
import com.filamentvision.core.model.MeasurementStatus
import com.filamentvision.core.vision.VisionProcessor
import com.filamentvision.core.vision.DetectionError
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SimulatorScenarioVisionTest {
    private val a = CameraId("left-optic")
    private val b = CameraId("right-optic")
    private val vision = VisionProcessor()
    private val config = VisionConfiguration(CalibrationProfile(
        roiRight = 48, roiBottom = 8, manualThreshold = 40, edgeSensitivity = 30,
        minimumPixelWidth = 1, maximumPixelWidth = 46, mmPerPixel = 0.05,
    ))

    private suspend fun TestScope.capture(scenario: SimulationScenario, ticks: Int = 1): List<CameraFrame> {
        val source = SimulatorImageInputSource(backgroundScope, StandardTestDispatcher(testScheduler), a, b,
            epochMillis = { 1_000 + testScheduler.currentTime }, monotonicNanos = { testScheduler.currentTime * 1_000_000 })
        source.setScenario(scenario)
        assertEquals(scenario, source.scenario.value)
        val frames = mutableListOf<CameraFrame>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { source.frames.collect { frames += it } }
        source.start(); runCurrent()
        repeat(ticks - 1) { advanceTimeBy(125); runCurrent() }
        source.stop(); collector.cancelAndJoin()
        return frames
    }

    private fun assertStrip(frame: CameraFrame, width: Int, rows: Int = 8) {
        assertEquals(48, frame.width); assertEquals(8, frame.height)
        assertEquals(PixelFormat.ARGB32, frame.pixelFormat)
        assertEquals(0, frame.orientationDegrees); assertFalse(frame.mirrored)
        assertTrue(frame.receivedAtMonotonicNanos >= 0)
        val left = (48 - width) / 2
        for (y in 0 until 8) for (x in 0 until 48) {
            assertEquals(if (y < rows && x in left until left + width) 0xff141414.toInt() else 0xffc8c8c8.toInt(), frame.pixels[y * 48 + x])
        }
    }

    private fun fuse(frames: List<CameraFrame>) = LatestCompatiblePairingStrategy(a, b).let { pairing ->
        frames.flatMap { frame ->
            val result = vision.process(frame, config)
            assertEquals(frame.cameraId, result.cameraId)
            assertEquals(frame.sequence, result.sequence)
            assertEquals(frame.capturedAtEpochMillis, result.timestampMillis)
            val measurement = result.measurement!!
            assertEquals(frame.sequence, measurement.sequence)
            pairing.offer(measurement).mapNotNull { FusionEngine().fuseDetailed(it.cameraA, it.cameraB, FusionConfiguration(0.5, 0.5, 0.7, 100)) }
        }.last()
    }

    // A wrong width, pixel packing, metadata mapping or hard-coded camera identity changes the real result.
    @Test fun normalPixelsProduceCalibratedDualCameraMeasurement() = runTest {
        val frames = capture(SimulationScenario.NORMAL)
        assertEquals(listOf(a, b), frames.map { it.cameraId })
        assertEquals(listOf(0L, 0L), frames.map { it.sequence })
        assertEquals(listOf(1_000L, 1_000L), frames.map { it.capturedAtEpochMillis })
        frames.forEach { assertStrip(it, 35) }
        val result = fuse(frames)
        assertEquals(1.75, result.measurement.fusedDiameter, 1e-9)
        assertEquals(1.0, result.measurement.confidence, 0.0)
        assertEquals(FusionReason.BOTH_CAMERAS, result.reason)
        assertEquals(MeasurementStatus.NORMAL, result.measurement.status)
        frames.forEach { it.close(); it.close() }
    }

    // Emitting B in A-only mode would hide the missing camera in Fusion.
    @Test fun cameraAOnlyOmitsBAndFusesAsSingleCamera() = runTest {
        val frames = capture(SimulationScenario.CAMERA_A_ONLY, ticks = 3)
        assertEquals(listOf(a, a, a), frames.map { it.cameraId })
        assertEquals(listOf(0L, 1L, 2L), frames.map { it.sequence })
        frames.forEach { assertStrip(it, 35) }
        val result = fuse(frames)
        assertEquals(FusionReason.CAMERA_B_MISSING, result.reason)
        assertEquals(MeasurementStatus.CAMERA_B_FAILURE, result.measurement.status)
        assertNull(result.measurement.cameraB)
        assertEquals(1.75, result.measurement.fusedDiameter, 1e-9)
        frames.forEach { it.close() }
    }

    // Emitting A in B-only mode would hide the missing camera in Fusion.
    @Test fun cameraBOnlyOmitsAAndFusesAsSingleCamera() = runTest {
        val frames = capture(SimulationScenario.CAMERA_B_ONLY, ticks = 3)
        assertEquals(listOf(b, b, b), frames.map { it.cameraId })
        assertEquals(listOf(0L, 1L, 2L), frames.map { it.sequence })
        frames.forEach { assertStrip(it, 35) }
        val result = fuse(frames)
        assertEquals(FusionReason.CAMERA_A_MISSING, result.reason)
        assertEquals(MeasurementStatus.CAMERA_A_FAILURE, result.measurement.status)
        assertNull(result.measurement.cameraA)
        assertEquals(1.75, result.measurement.fusedDiameter, 1e-9)
        frames.forEach { it.close() }
    }

    // Equalizing the two strips would erase observable camera disagreement.
    @Test fun mismatchedPixelsPreserveDifferentCameraDiameters() = runTest {
        val frames = capture(SimulationScenario.CAMERA_MISMATCH)
        assertStrip(frames[0], 33); assertStrip(frames[1], 37)
        val result = fuse(frames)
        assertEquals(1.65, result.measurement.cameraA!!.diameterMm, 1e-9)
        assertEquals(1.85, result.measurement.cameraB!!.diameterMm, 1e-9)
        assertEquals(0.20, result.measurement.shapeDifference, 1e-9)
        assertEquals(0.20, result.cameraDifferenceMm!!, 1e-9)
        assertEquals(1.75, result.measurement.fusedDiameter, 1e-9)
        assertEquals(FusionReason.BOTH_CAMERAS, result.reason)
        frames.forEach { it.close() }
    }

    // Losing the injected skew would incorrectly fuse an incompatible pair as synchronized.
    @Test fun timestampMismatchSelectsTheNewerCamera() = runTest {
        val frames = capture(SimulationScenario.TIMESTAMP_MISMATCH)
        frames.forEach { assertStrip(it, 35) }
        assertEquals(listOf(1_000L, 1_500L), frames.map { it.capturedAtEpochMillis })
        val result = fuse(frames)
        assertEquals(FusionReason.TIMESTAMP_MISMATCH, result.reason)
        assertFalse(result.timestampsCompatible)
        assertEquals(MeasurementStatus.CAMERA_A_FAILURE, result.measurement.status)
        assertEquals(setOf(b), result.activeCameraIds)
        assertEquals(1_500L, result.measurement.timestamp)
        assertEquals(0L, result.measurement.cameraB!!.sequence)
        frames.forEach { it.close() }
    }

    // Restoring all A rows would incorrectly turn low-confidence pixels into a valid dual-camera pair.
    @Test fun partiallyDetectableRowsCauseRealLowConfidenceAndFusionFallback() = runTest {
        val frames = capture(SimulationScenario.LOW_CONFIDENCE)
        assertStrip(frames[0], 35, rows = 4); assertStrip(frames[1], 35)
        val rejected = vision.process(frames[0], config)
        assertNull(rejected.measurement)
        assertEquals(DetectionError.LOW_CONFIDENCE, rejected.error)
        val acceptedForDiagnostics = config.copy(calibration = config.calibration.copy(minimumConfidence = 0.5))
        val measurements = frames.map { vision.process(it, acceptedForDiagnostics).measurement!! }
        assertEquals(0.5, measurements[0].confidence, 0.0)
        assertEquals(1.75, measurements[0].diameterMm, 1e-9)
        assertEquals(1.0, measurements[1].confidence, 0.0)
        val pairing = LatestCompatiblePairingStrategy(a, b)
        val result = measurements.flatMap { pairing.offer(it) }.mapNotNull {
            FusionEngine().fuseDetailed(it.cameraA, it.cameraB, FusionConfiguration(0.5, 0.5, 0.7, 100))
        }.last()
        assertEquals(FusionReason.CAMERA_A_LOW_CONFIDENCE, result.reason)
        assertEquals(setOf(b), result.activeCameraIds)
        assertEquals(MeasurementStatus.CAMERA_A_FAILURE, result.measurement.status)
        frames.forEach { it.close() }
    }

    // A dropout must omit images temporarily, retain B sequence continuity and recover normal fusion.
    @Test fun temporaryDropoutOmitsThreeBTicksThenResumesItsSequence() = runTest {
        val frames = capture(SimulationScenario.TEMPORARY_DROPOUT, ticks = 6)
        val cameraB = frames.filter { it.cameraId == b }
        assertEquals(listOf(1_000L, 1_500L, 1_625L), cameraB.map { it.capturedAtEpochMillis })
        assertEquals(listOf(0L, 1L, 2L), cameraB.map { it.sequence })
        assertEquals(6, frames.count { it.cameraId == a })
        frames.forEach { assertStrip(it, 35) }
        val duringDropout = fuse(frames.filter { it.capturedAtEpochMillis <= 1_375 })
        assertEquals(FusionReason.TIMESTAMP_MISMATCH, duringDropout.reason)
        assertEquals(setOf(a), duringDropout.activeCameraIds)
        val recovered = fuse(frames)
        assertEquals(FusionReason.BOTH_CAMERAS, recovered.reason)
        assertEquals(1.75, recovered.measurement.fusedDiameter, 1e-9)
        assertEquals(2L, recovered.measurement.cameraB!!.sequence)
        frames.forEach { it.close() }
    }

    // Low geometry must cross both existing warning and critical deviations without fabricated measurements.
    @Test fun lowPixelsProduceWarningAndCriticalDiameters() = runTest {
        val frames = capture(SimulationScenario.LOW_DIAMETER, ticks = 2)
        frames.take(2).forEach { assertStrip(it, 34) }
        frames.drop(2).forEach { assertStrip(it, 33) }
        assertEquals(1.70, fuse(frames.take(2)).measurement.fusedDiameter, 1e-9)
        assertEquals(1.65, fuse(frames.drop(2)).measurement.fusedDiameter, 1e-9)
        frames.forEach { it.close() }
    }

    // High geometry must cross both existing warning and critical deviations without fabricated measurements.
    @Test fun highPixelsProduceWarningAndCriticalDiameters() = runTest {
        val frames = capture(SimulationScenario.HIGH_DIAMETER, ticks = 2)
        frames.take(2).forEach { assertStrip(it, 36) }
        frames.drop(2).forEach { assertStrip(it, 37) }
        assertEquals(1.80, fuse(frames.take(2)).measurement.fusedDiameter, 1e-9)
        assertEquals(1.85, fuse(frames.drop(2)).measurement.fusedDiameter, 1e-9)
        frames.forEach { it.close() }
    }
}
