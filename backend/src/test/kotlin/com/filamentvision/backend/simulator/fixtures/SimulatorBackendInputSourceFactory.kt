package com.filamentvision.backend.simulator.fixtures

import com.filamentvision.backend.BackendComposition
import com.filamentvision.backend.input.profile.RaspberryPiUdpInputProfile
import com.filamentvision.backend.input.runtime.BackendInputSourceFactory
import com.filamentvision.core.frame.CameraId
import com.filamentvision.core.input.CameraInput
import com.filamentvision.core.input.CameraInputSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.transform

/** Explicit test adapter: keep simulator scenarios unchanged, admit only its first camera. */
object SimulatorBackendInputSourceFactory : BackendInputSourceFactory {
    override fun create(scope: CoroutineScope, dispatcher: CoroutineDispatcher, epochMillis: () -> Long,
        monotonicNanos: () -> Long, profile: RaspberryPiUdpInputProfile): CameraInputSource =
        SingleCameraSimulatorInput(SimulatorImageInputSource(scope, dispatcher, cameraAId = CameraId("camera"),
            epochMillis = epochMillis, monotonicNanos = monotonicNanos))
}

private class SingleCameraSimulatorInput(val simulator: SimulatorImageInputSource) : CameraInputSource by simulator {
    override val inputs = simulator.inputs.transform { input ->
        if (input.cameraId == CameraId("camera")) emit(input)
        else (input as CameraInput.Image).frame.close()
    }
}

val BackendComposition.source: SimulatorImageInputSource get() = (inputSource as SingleCameraSimulatorInput).simulator
