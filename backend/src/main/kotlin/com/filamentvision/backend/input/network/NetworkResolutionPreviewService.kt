package com.filamentvision.backend.input.network

import com.filamentvision.backend.input.profile.AdvertisedDestinationProfile
import com.filamentvision.backend.input.profile.SourceFilterProfile
import com.filamentvision.backend.input.profile.UdpNetworkProfile
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class PreviewProfile(
    val enabled: Boolean,
    val network: UdpNetworkProfile,
    val savedRevision: Long,
    val appliedRevision: Long,
)

data class NetworkResolutionPreview(
    val configuredDestination: String?,
    val configuredSource: String?,
    val candidatePlan: EffectiveUdpNetworkPlan?,
    val destinationStatus: ResolutionStatus,
    val sourceStatus: ResolutionStatus,
    val consistencyStatus: NetworkConsistencyStatus,
    val reapplyRequired: Boolean,
    val differences: Set<String>,
    val warnings: Set<String>,
    val errorCode: String?,
    val previewedAtEpochMillis: Long,
    val appliedResolutionGeneration: Long?,
    val superseded: Boolean = false,
)

class NetworkResolutionPreviewService(
    private val resolver: Ipv4HostResolver,
    private val planner: EffectiveUdpNetworkPlanner,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val minimumStartIntervalMillis: Long = 250,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : AutoCloseable {
    private val lock = Any()
    private val slots = mutableMapOf<String, Slot>()
    private val latestResults = mutableMapOf<String, NetworkResolutionPreview>()
    private val activeCount = AtomicInteger()
    private val queuedCount = AtomicInteger()
    private val maxActive = AtomicInteger()
    private val maxQueued = AtomicInteger()
    private var lastStartedAt = 0L

    val maximumActivePreviewCount: Int get() = maxActive.get()
    val maximumQueuedPreviewCount: Int get() = maxQueued.get()

    suspend fun preview(
        deviceId: String,
        profile: PreviewProfile,
        appliedPlan: EffectiveUdpNetworkPlan?,
        runtimeMutationObserver: () -> Unit = {},
    ): NetworkResolutionPreview {
        // The observer is an assertion seam: preview intentionally never invokes it.
        @Suppress("UNUSED_VARIABLE") val ignored = runtimeMutationObserver
        val request = Request(profile, appliedPlan, CompletableDeferred())
        var startWorker = false
        synchronized(lock) {
            val slot = slots.getOrPut(deviceId) { Slot() }
            if (!slot.active) {
                slot.active = true
                startWorker = true
            } else {
                slot.queued?.result?.complete(superseded(slot.queued!!.profile, appliedPlan))
                slot.queued = request
                queuedCount.set(1)
                maxQueued.accumulateAndGet(queuedCount.get(), ::maxOf)
            }
        }
        if (startWorker) {
            scope.launch { drain(deviceId, request) }
        }
        return request.result.await()
    }

    fun latest(deviceId: String): NetworkResolutionPreview? = synchronized(lock) { latestResults[deviceId] }

    private suspend fun drain(deviceId: String, initial: Request) {
        var current: Request? = initial
        while (current != null) {
            val waitMillis = synchronized(lock) {
                (minimumStartIntervalMillis - (clockMillis() - lastStartedAt)).coerceAtLeast(0)
            }
            if (waitMillis > 0) delay(waitMillis)
            synchronized(lock) { lastStartedAt = clockMillis() }
            maxActive.accumulateAndGet(activeCount.incrementAndGet(), ::maxOf)
            val result = try {
                buildPreview(current.profile, current.appliedPlan)
            } finally {
                activeCount.decrementAndGet()
            }
            synchronized(lock) { latestResults[deviceId] = result }
            current.result.complete(result)
            current = synchronized(lock) {
                val slot = requireNotNull(slots[deviceId])
                val next = slot.queued
                slot.queued = null
                queuedCount.set(0)
                if (next == null) slot.active = false
                next
            }
        }
    }

    private suspend fun buildPreview(
        profile: PreviewProfile,
        appliedPlan: EffectiveUdpNetworkPlan?,
    ): NetworkResolutionPreview {
        val timestamp = clockMillis()
        val destinationValue = (profile.network.destination as? AdvertisedDestinationProfile.Manual)?.hostOrIpv4
        val sourceValue = (profile.network.sourceFilter as? SourceFilterProfile.IpOrHost)?.hostOrIpv4
        if (!profile.enabled) {
            return NetworkResolutionPreview(
                destinationValue, sourceValue, null,
                ResolutionStatus.NOT_REQUIRED, ResolutionStatus.NOT_REQUIRED,
                NetworkConsistencyStatus.HEALTHY, false, emptySet(), emptySet(), null,
                timestamp, appliedPlan?.resolutionGeneration,
            )
        }
        val destination = destinationValue?.let { resolver.resolveIpv4(it) }
        val source = sourceValue?.let { resolver.resolveIpv4(it) }
        val result = planner.plan(profile.network, destination, source)
        if (result is NetworkPlanResult.Failure) {
            val destinationStatus = when {
                result.code == "DESTINATION_AMBIGUOUS" -> ResolutionStatus.AMBIGUOUS
                destinationValue == null -> ResolutionStatus.NOT_REQUIRED
                destination?.status == HostResolutionStatus.RESOLVED -> ResolutionStatus.RESOLVED
                else -> ResolutionStatus.FAILED
            }
            val sourceStatus = when {
                sourceValue == null -> ResolutionStatus.NOT_REQUIRED
                source?.status == HostResolutionStatus.RESOLVED -> ResolutionStatus.RESOLVED
                else -> ResolutionStatus.FAILED
            }
            return NetworkResolutionPreview(
                destinationValue, sourceValue, null, destinationStatus, sourceStatus,
                NetworkConsistencyStatus.REAPPLY_REQUIRED, true, setOf("RESOLUTION_FAILED"), emptySet(),
                result.code, timestamp, appliedPlan?.resolutionGeneration,
            )
        }
        val candidateResult = (result as NetworkPlanResult.Success).candidate
        val candidate = EffectiveUdpNetworkPlan.candidate(
            candidateResult,
            createdAtEpochMillis = clockMillis(),
            generation = appliedPlan?.resolutionGeneration ?: 0,
        )
        val changed = appliedPlan != null && !candidate.hasSameResolution(appliedPlan)
        return NetworkResolutionPreview(
            destinationValue,
            sourceValue,
            candidate,
            ResolutionStatus.RESOLVED,
            if (sourceValue == null) ResolutionStatus.NOT_REQUIRED else ResolutionStatus.RESOLVED,
            if (changed) NetworkConsistencyStatus.REAPPLY_REQUIRED else NetworkConsistencyStatus.HEALTHY,
            changed,
            if (changed) setOf("RESOLUTION_CHANGED") else emptySet(),
            candidateResult.warnings,
            null,
            timestamp,
            appliedPlan?.resolutionGeneration,
        )
    }

    private fun superseded(profile: PreviewProfile, applied: EffectiveUdpNetworkPlan?) = NetworkResolutionPreview(
        (profile.network.destination as? AdvertisedDestinationProfile.Manual)?.hostOrIpv4,
        (profile.network.sourceFilter as? SourceFilterProfile.IpOrHost)?.hostOrIpv4,
        null,
        ResolutionStatus.NOT_REQUIRED,
        ResolutionStatus.NOT_REQUIRED,
        NetworkConsistencyStatus.HEALTHY,
        false,
        emptySet(),
        emptySet(),
        null,
        clockMillis(),
        applied?.resolutionGeneration,
        superseded = true,
    )

    override fun close() {
        scope.cancel()
    }

    private data class Request(
        val profile: PreviewProfile,
        val appliedPlan: EffectiveUdpNetworkPlan?,
        val result: CompletableDeferred<NetworkResolutionPreview>,
    )

    private data class Slot(var active: Boolean = false, var queued: Request? = null)
}
