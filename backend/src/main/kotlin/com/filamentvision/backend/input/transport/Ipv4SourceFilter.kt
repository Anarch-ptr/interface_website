package com.filamentvision.backend.input.transport

import java.net.Inet4Address
import java.net.InetSocketAddress

class Ipv4SourceFilter(sourceIpv4s: Set<Inet4Address>?) {
    private val expected: Set<Inet4Address>? = sourceIpv4s?.let { java.util.Set.copyOf(it) }

    fun accepts(source: InetSocketAddress): Boolean {
        val address = source.address
        return address is Inet4Address && (expected == null || address in expected)
    }
}
