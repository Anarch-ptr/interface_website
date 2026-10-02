package com.filamentvision.backend.input.runtime

import com.filamentvision.backend.input.inspector.PacketInspector
import com.filamentvision.backend.input.profile.UdpInputProfileSet
import com.filamentvision.backend.simulator.SimulatorImageInputSource
import com.filamentvision.core.input.CameraInputSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope

fun interface BackendInputSourceFactory {
    fun create(scope: CoroutineScope, dispatcher: CoroutineDispatcher, epochMillis: () -> Long,
        monotonicNanos: () -> Long, profiles: UdpInputProfileSet, inspector: PacketInspector): CameraInputSource
}

object ProductionUdpInputSourceFactory : BackendInputSourceFactory {
    override fun create(scope: CoroutineScope, dispatcher: CoroutineDispatcher, epochMillis: () -> Long,
        monotonicNanos: () -> Long, profiles: UdpInputProfileSet, inspector: PacketInspector): CameraInputSource =
        UdpCameraInputSource(profiles, inspector)
}

object SimulatorBackendInputSourceFactory : BackendInputSourceFactory {
    override fun create(scope: CoroutineScope, dispatcher: CoroutineDispatcher, epochMillis: () -> Long,
        monotonicNanos: () -> Long, profiles: UdpInputProfileSet, inspector: PacketInspector): CameraInputSource =
        SimulatorImageInputSource(scope, dispatcher, epochMillis = epochMillis, monotonicNanos = monotonicNanos)
}
