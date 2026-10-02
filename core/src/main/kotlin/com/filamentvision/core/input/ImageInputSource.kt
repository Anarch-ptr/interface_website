package com.filamentvision.core.input

import com.filamentvision.core.frame.CameraFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map

enum class ImageInputState { IDLE, STARTING, RUNNING, STOPPING, ERROR }

data class ImageInputDiagnostics(
    val droppedFrames: Long = 0,
    val lastError: String? = null,
)

/** A decoded-image producer; an observer of [frames] does not own its lifecycle. */
interface ImageInputSource : CameraInputSource {
    val frames: Flow<CameraFrame>
    override val inputs: Flow<CameraInput> get() = frames.map(CameraInput::Image)
}
