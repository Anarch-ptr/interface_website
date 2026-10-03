package com.filamentvision.backend.input.network

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/** One owned IPv4 address; an adapter with aliases has an entry for each address. */
data class Ipv4NetworkInterfaceEntry(
    val id: String,
    val displayName: String,
    val ipv4: String,
    val isUp: Boolean,
    val isLoopback: Boolean,
    val isVirtual: Boolean,
    val supportsMulticast: Boolean,
    val interfaceIndex: Int,
)

fun interface Ipv4NetworkInterfaceProvider {
    fun listEligible(): List<Ipv4NetworkInterfaceEntry>
}

/** Platform enumeration seam keeps selection tests independent of host adapters. */
fun interface NetworkInterfacePlatform {
    fun listInterfaces(): List<PlatformNetworkInterface>
}

data class PlatformNetworkInterface(
    val id: String,
    val displayName: String,
    val index: Int,
    val isUp: Boolean,
    val isLoopback: Boolean,
    val isVirtual: Boolean,
    val supportsMulticast: Boolean,
    val addresses: List<InetAddress>,
)

class JavaNetworkInterfacePlatform : NetworkInterfacePlatform {
    override fun listInterfaces(): List<PlatformNetworkInterface> =
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().map { adapter ->
            PlatformNetworkInterface(
                adapter.name, adapter.displayName ?: adapter.name, adapter.index,
                adapter.isUp, adapter.isLoopback, adapter.isVirtual, adapter.supportsMulticast(),
                adapter.inetAddresses.toList(),
            )
        }
}

class JavaIpv4NetworkInterfaceProvider(
    private val platform: NetworkInterfacePlatform = JavaNetworkInterfacePlatform(),
) : Ipv4NetworkInterfaceProvider {
    override fun listEligible(): List<Ipv4NetworkInterfaceEntry> = java.util.List.copyOf(
        platform.listInterfaces().filter { it.isUp && !it.isLoopback }.flatMap { adapter ->
            adapter.addresses.filterIsInstance<Inet4Address>()
                .filter { !it.isAnyLocalAddress && !it.isLoopbackAddress }
                .map { address ->
                    Ipv4NetworkInterfaceEntry(
                        adapter.id, adapter.displayName, address.hostAddress, adapter.isUp,
                        adapter.isLoopback, adapter.isVirtual, adapter.supportsMulticast, adapter.index,
                    )
                }
        },
    )
}

/** Parse canonical IPv4 bytes without DNS or IPv4-mapped IPv6 normalization. */
internal fun parseIpv4Literal(value: String): Inet4Address? {
    val parts = value.split('.')
    if (parts.size != 4) return null
    val bytes = ByteArray(4)
    for ((index, part) in parts.withIndex()) {
        if (part.isEmpty() || part.any { it !in '0'..'9' } || (part.length > 1 && part.first() == '0')) return null
        val octet = part.toIntOrNull() ?: return null
        if (octet !in 0..255) return null
        bytes[index] = octet.toByte()
    }
    return InetAddress.getByAddress(bytes) as Inet4Address
}

internal fun Ipv4NetworkInterfaceEntry.isEligible(): Boolean {
    val address = parseIpv4Literal(ipv4) ?: return false
    return isUp && !isLoopback && !address.isAnyLocalAddress && !address.isLoopbackAddress
}
