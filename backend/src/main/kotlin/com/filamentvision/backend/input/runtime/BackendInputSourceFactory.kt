package com.filamentvision.backend.input.runtime

import com.filamentvision.backend.input.profile.RaspberryPiUdpInputProfile
import com.filamentvision.backend.input.transport.EpochClock
import com.filamentvision.backend.input.transport.MonotonicClock
import com.filamentvision.core.input.CameraInputSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope

fun interface BackendInputSourceFactory {
    fun create(scope: CoroutineScope, dispatcher: CoroutineDispatcher, epochMillis: () -> Long,
        monotonicNanos: () -> Long, profile: RaspberryPiUdpInputProfile): CameraInputSource
}

object ProductionUdpInputSourceFactory : BackendInputSourceFactory {
    override fun create(scope: CoroutineScope, dispatcher: CoroutineDispatcher, epochMillis: () -> Long,
        monotonicNanos: () -> Long, profile: RaspberryPiUdpInputProfile): CameraInputSource =
        RaspberryPiUdpInputSource(profile, monotonicClock = MonotonicClock(monotonicNanos), epochClock = EpochClock(epochMillis))
}
