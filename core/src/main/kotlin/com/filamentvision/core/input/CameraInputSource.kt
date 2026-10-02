package com.filamentvision.core.input

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** One lifecycle owner that emits protocol-neutral camera evidence. */
interface CameraInputSource {
    val inputs: Flow<CameraInput>
    val state: StateFlow<ImageInputState>
    val diagnostics: StateFlow<ImageInputDiagnostics>
    val activeProducerCount: Int

    suspend fun start()
    suspend fun stop()
}
