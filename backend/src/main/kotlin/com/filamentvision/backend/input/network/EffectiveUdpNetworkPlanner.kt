package com.filamentvision.backend.input.network

import com.filamentvision.backend.input.profile.AdvertisedDestinationProfile
import com.filamentvision.backend.input.profile.BindProfile
import com.filamentvision.backend.input.profile.SourceFilterProfile
import com.filamentvision.backend.input.profile.UdpNetworkProfile
import java.io.IOException

data class NetworkPlanCandidate(
    val actualBindIpv4: String,
    val actualBindInterfaceId: String?,
    val configuredDestination: String?,
    val resolvedDestinationIpv4: String,
    val configuredSource: String?,
    val resolvedSourceIpv4s: Set<String>,
    val destinationResolution: HostResolution?,
    val sourceResolution: HostResolution?,
    val warnings: Set<String> = emptySet(),
)

sealed interface NetworkPlanResult {
    data class Success(val candidate: NetworkPlanCandidate) : NetworkPlanResult
    data class Failure(val code: String, val detail: String? = null) : NetworkPlanResult
}

class EffectiveUdpNetworkPlanner(
    private val interfaceProvider: Ipv4NetworkInterfaceProvider = JavaIpv4NetworkInterfaceProvider(),
    private val routeProbe: Ipv4RouteProbe = JavaIpv4RouteProbe(),
) {
    fun plan(
        profile: UdpNetworkProfile,
        destination: HostResolution? = null,
        source: HostResolution? = null,
    ): NetworkPlanResult {
        val inventory = interfaceProvider.listEligible()
        val eligible = inventory.filter(Ipv4NetworkInterfaceEntry::isEligible)
        val bind = resolveBind(profile.bind, inventory, eligible)
        if (bind is BindResult.Failure) return NetworkPlanResult.Failure(bind.code)
        bind as BindResult.Success

        val sourceAddresses = when (val configured = profile.sourceFilter) {
            SourceFilterProfile.Any -> emptySet()
            is SourceFilterProfile.IpOrHost -> {
                if (source?.status != HostResolutionStatus.RESOLVED || source.ipv4s.isEmpty()) {
                    return NetworkPlanResult.Failure("SOURCE_RESOLUTION_FAILED")
                }
                source.ipv4s
            }
        }
        val destinationAddress = when (val configured = profile.destination) {
            AdvertisedDestinationProfile.Auto -> resolveAutoDestination(sourceAddresses, eligible)
            is AdvertisedDestinationProfile.Manual -> resolveManualDestination(destination)
        }
        if (destinationAddress is DestinationResult.Failure) {
            return NetworkPlanResult.Failure(destinationAddress.code)
        }
        destinationAddress as DestinationResult.Success

        val warnings = if (sourceAddresses.any { !sameClassCSubnet(it, destinationAddress.ipv4) }) {
            setOf("DIFFERENT_SUBNET")
        } else {
            emptySet()
        }
        return NetworkPlanResult.Success(
            NetworkPlanCandidate(
                actualBindIpv4 = bind.ipv4,
                actualBindInterfaceId = bind.interfaceId,
                configuredDestination = (profile.destination as? AdvertisedDestinationProfile.Manual)?.hostOrIpv4,
                resolvedDestinationIpv4 = destinationAddress.ipv4,
                configuredSource = (profile.sourceFilter as? SourceFilterProfile.IpOrHost)?.hostOrIpv4,
                resolvedSourceIpv4s = sourceAddresses.toSet(),
                destinationResolution = destination,
                sourceResolution = source,
                warnings = warnings,
            ),
        )
    }

    private fun resolveBind(
        bind: BindProfile,
        inventory: List<Ipv4NetworkInterfaceEntry>,
        eligible: List<Ipv4NetworkInterfaceEntry>,
    ): BindResult = when (bind) {
        BindProfile.Auto, BindProfile.AllInterfaces -> BindResult.Success("0.0.0.0", null)
        is BindProfile.SpecificInterface -> {
            val byId = inventory.filter { it.id == bind.interfaceId }
            when {
                byId.isEmpty() -> BindResult.Failure("INTERFACE_NOT_FOUND")
                byId.none { it.isUp } -> BindResult.Failure("INTERFACE_NOT_UP")
                byId.none { it.ipv4 == bind.ipv4 } -> BindResult.Failure("INTERFACE_ADDRESS_CHANGED")
                else -> BindResult.Success(bind.ipv4, bind.interfaceId)
            }
        }
        is BindProfile.ManualIp -> eligible.firstOrNull { it.ipv4 == bind.ipv4 }
            ?.let { BindResult.Success(bind.ipv4, it.id) }
            ?: BindResult.Failure("MANUAL_BIND_IP_NOT_LOCAL")
    }

    private fun resolveManualDestination(destination: HostResolution?): DestinationResult =
        if (destination?.status == HostResolutionStatus.RESOLVED && destination.ipv4s.size == 1) {
            DestinationResult.Success(destination.ipv4s.single())
        } else if (destination?.ipv4s?.size.orZero() > 1) {
            DestinationResult.Failure("DESTINATION_AMBIGUOUS")
        } else {
            DestinationResult.Failure("HOSTNAME_RESOLUTION_FAILED")
        }

    private fun resolveAutoDestination(
        sourceAddresses: Set<String>,
        eligible: List<Ipv4NetworkInterfaceEntry>,
    ): DestinationResult {
        if (sourceAddresses.isNotEmpty()) {
            val routes = sourceAddresses.map { source ->
                try { routeProbe.localIpv4(source) } catch (_: IOException) { null }
            }
            if (routes.any { it == null }) return DestinationResult.Failure("DESTINATION_AMBIGUOUS")
            val unique = routes.filterNotNull().toSet()
            if (unique.size != 1 || eligible.none { it.ipv4 == unique.single() }) {
                return DestinationResult.Failure("DESTINATION_AMBIGUOUS")
            }
            return DestinationResult.Success(unique.single())
        }
        return when (eligible.size) {
            0 -> DestinationResult.Failure("NO_DESTINATION_CANDIDATE")
            1 -> DestinationResult.Success(eligible.single().ipv4)
            else -> DestinationResult.Failure("DESTINATION_AMBIGUOUS")
        }
    }

    private fun sameClassCSubnet(first: String, second: String): Boolean =
        first.split('.').take(3) == second.split('.').take(3)

    private sealed interface BindResult {
        data class Success(val ipv4: String, val interfaceId: String?) : BindResult
        data class Failure(val code: String) : BindResult
    }

    private sealed interface DestinationResult {
        data class Success(val ipv4: String) : DestinationResult
        data class Failure(val code: String) : DestinationResult
    }

    private fun Int?.orZero() = this ?: 0
}
