package com.filamentvision.backend.simulator

import com.filamentvision.core.frame.CameraFrame
import com.filamentvision.core.frame.CameraId
import com.filamentvision.core.frame.PixelBuffer
import com.filamentvision.core.frame.PixelFormat

/** Allocates one owned ARGB buffer per frame; no caller-owned array is retained. */
class SimulatorFrameFactory {
    fun create(cameraId: CameraId, epochMillis: Long, monotonicNanos: Long, sequence: Long,
               scenario: SimulationScenario, scenarioTick: Long, isCameraA: Boolean): CameraFrame {
        val width = when (scenario) {
            SimulationScenario.NORMAL, SimulationScenario.CAMERA_A_ONLY, SimulationScenario.CAMERA_B_ONLY,
            SimulationScenario.TIMESTAMP_MISMATCH, SimulationScenario.LOW_CONFIDENCE, SimulationScenario.TEMPORARY_DROPOUT -> 35
            SimulationScenario.HIGH_DIAMETER -> 36 + (scenarioTick % 2).toInt()
            SimulationScenario.LOW_DIAMETER -> 34 - (scenarioTick % 2).toInt()
            SimulationScenario.CAMERA_MISMATCH -> if (isCameraA) 33 else 37
            SimulationScenario.CONNECTION_FAILURE -> error("A disconnected source has no frame")
        }
        val left = (48 - width) / 2
        val detectableRows = if (scenario == SimulationScenario.LOW_CONFIDENCE && isCameraA) 4 else 8
        val pixels = IntArray(48 * 8) { index ->
            if (index / 48 < detectableRows && index % 48 in left until left + width) 0xff141414.toInt() else 0xffc8c8c8.toInt()
        }
        return CameraFrame(cameraId, epochMillis, monotonicNanos, 48, 8, PixelFormat.ARGB32,
            PixelBuffer.ints(pixels), 0, false, sequence)
    }
}
