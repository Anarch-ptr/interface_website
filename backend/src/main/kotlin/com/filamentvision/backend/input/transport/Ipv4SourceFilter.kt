package com.filamentvision.backend.input.transport

import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress

class Ipv4SourceFilter(sourceIp: String?) {
    private val expected: ByteArray? = sourceIp?.let {
        val address = InetAddress.getByName(it)
        require(address is Inet4Address) { "sourceIp must be an IPv4 literal" }
        address.address
    }

    fun accepts(source: InetSocketAddress): Boolean {
        val address = source.address
        return address is Inet4Address && (expected == null || expected.contentEquals(address.address))
    }
}
