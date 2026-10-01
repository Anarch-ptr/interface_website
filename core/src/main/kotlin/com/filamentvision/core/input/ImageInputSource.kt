package com.filamentvision.core.input

import com.filamentvision.core.frame.CameraFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

enum class ImageInputState { IDLE, STARTING, RUNNING, STOPPING, ERROR }

data class ImageInputDiagnostics(
    val droppedFrames: Long = 0,
    val lastError: String? = null,
)

/** A decoded-image producer; an observer of [frames] does not own its lifecycle. */
interface ImageInputSource {
    val frames: Flow<CameraFrame>
    val state: StateFlow<ImageInputState>
    val diagnostics: StateFlow<ImageInputDiagnostics>
    val activeProducerCount: Int

    suspend fun start()
    suspend fun stop()
}
