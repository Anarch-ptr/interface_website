package com.filamentvision.backend.input.network

import com.filamentvision.backend.input.profile.NetworkInterfaceSelection
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.StandardProtocolFamily
import java.nio.channels.DatagramChannel

fun interface Ipv4RouteProbe {
    /** Return the local IPv4 route without sending a packet, or null when unavailable. */
    fun localIpv4(raspberryPiIpv4: String): String?
}

class JavaIpv4RouteProbe internal constructor(private val probePort: Int) : Ipv4RouteProbe {
    constructor() : this(9)

    override fun localIpv4(raspberryPiIpv4: String): String? {
        val destination = requireNotNull(parseIpv4Literal(raspberryPiIpv4)) { "Accepts only canonical IPv4 literals" }
        // Starts unbound; connect asks the OS for a route and assigns an ephemeral port.
        // No send/write operation is performed, and the temporary channel always closes.
        return DatagramChannel.open(StandardProtocolFamily.INET).use { channel ->
            channel.connect(InetSocketAddress(destination, probePort))
            val address = (channel.localAddress as? InetSocketAddress)?.address as? Inet4Address
            address?.takeUnless { it.isAnyLocalAddress }?.hostAddress
        }
    }
}

data class BackendIpv4Resolution(
    val selectedInterface: Ipv4NetworkInterfaceEntry?,
    val hasMultipleCandidates: Boolean,
    val statusDetail: String? = null,
) {
    val advertisedBackendIpv4: String? get() = selectedInterface?.ipv4
}

/** Resolves an advertised destination only; receiver binding and lifecycle are separate. */
class BackendIpv4Resolver(
    private val provider: Ipv4NetworkInterfaceProvider = JavaIpv4NetworkInterfaceProvider(),
    private val routeProbe: Ipv4RouteProbe = JavaIpv4RouteProbe(),
) {
    fun resolve(selection: NetworkInterfaceSelection, raspberryPiIpv4: String?): BackendIpv4Resolution {
        raspberryPiIpv4?.let {
            require(parseIpv4Literal(it) != null) { "Accepts only canonical IPv4 literals" }
        }
        // Refresh ownership each time, including SPECIFIC, rather than caching an old selection.
        val eligible = provider.listEligible().filter { it.isEligible() }
        if (selection is NetworkInterfaceSelection.Specific) {
            val selected = eligible.firstOrNull { it.id == selection.interfaceId && it.ipv4 == selection.ipv4 }
            return BackendIpv4Resolution(selected, false,
                if (selected == null) "SELECTED_IPV4_INTERFACE_UNAVAILABLE" else null)
        }
        if (eligible.isEmpty()) return BackendIpv4Resolution(null, false, "NO_USABLE_IPV4_INTERFACE")
        val routeIpv4 = raspberryPiIpv4?.let {
            try { routeProbe.localIpv4(it) } catch (_: IOException) { null }
        }
        val routed = eligible.firstOrNull { it.ipv4 == routeIpv4 }
        val selected = routed ?: eligible.minWith(
            compareBy<Ipv4NetworkInterfaceEntry> { it.isVirtual }
                .thenBy { it.interfaceIndex }
                .thenBy { numericIpv4(it.ipv4) }
                .thenBy { it.id },
        )
        return BackendIpv4Resolution(selected, eligible.size > 1)
    }

    private fun numericIpv4(ipv4: String): Long =
        checkNotNull(parseIpv4Literal(ipv4)).address.fold(0L) { result, octet -> (result shl 8) or (octet.toLong() and 0xff) }
}
