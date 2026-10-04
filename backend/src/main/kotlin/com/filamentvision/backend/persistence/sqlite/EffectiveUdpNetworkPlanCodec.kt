package com.filamentvision.backend.persistence.sqlite

import com.filamentvision.backend.input.network.EffectiveUdpNetworkPlan
import com.filamentvision.backend.input.network.ResolutionStatus
import com.filamentvision.backend.input.profile.isCanonicalIpv4Literal
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

object EffectiveUdpNetworkPlanCodec {
    private const val VERSION = 1
    private const val MAX_HOST_LENGTH = 253
    private const val MAX_INTERFACE_ID_LENGTH = 65_536
    private const val MAX_SOURCE_ADDRESSES = 16
    private const val MAX_ENCODED_BYTES = 128 * 1024

    fun encode(plan: EffectiveUdpNetworkPlan): ByteArray {
        validate(plan)
        val canonical = plan.copy(resolvedSourceIpv4s = plan.resolvedSourceIpv4s.sortedWith(::compareIpv4).toCollection(linkedSetOf()))
        return ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { out ->
                out.writeInt(VERSION)
                out.writeBounded(canonical.actualBindIpv4)
                out.writeNullable(canonical.actualBindInterfaceId)
                out.writeNullable(canonical.configuredDestination)
                out.writeNullable(canonical.resolvedDestinationIpv4)
                out.writeNullable(canonical.configuredSource)
                out.writeInt(canonical.resolvedSourceIpv4s.size)
                canonical.resolvedSourceIpv4s.forEach { out.writeBounded(it) }
                out.writeUTF(canonical.destinationStatus.name)
                out.writeUTF(canonical.sourceStatus.name)
                out.writeNullableLong(canonical.destinationResolutionTime)
                out.writeNullableLong(canonical.sourceResolutionTime)
                out.writeLong(canonical.planCreatedAtEpochMillis)
                out.writeNullableLong(canonical.appliedAtEpochMillis)
                out.writeLong(canonical.resolutionGeneration)
            }
        }.toByteArray().also { require(it.size <= MAX_ENCODED_BYTES) { "Effective UDP network plan is too large" } }
    }

    fun decode(bytes: ByteArray): EffectiveUdpNetworkPlan {
        require(bytes.size <= MAX_ENCODED_BYTES) { "Effective UDP network plan is too large" }
        return try {
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                require(input.readInt() == VERSION) { "Unsupported effective UDP network plan version" }
                val actualBind = input.readBounded(45)
                val interfaceId = input.readNullable(MAX_INTERFACE_ID_LENGTH)
                val configuredDestination = input.readNullable(MAX_HOST_LENGTH)
                val resolvedDestination = input.readNullable(45)
                val configuredSource = input.readNullable(MAX_HOST_LENGTH)
                val count = input.readInt()
                require(count in 0..MAX_SOURCE_ADDRESSES) { "Invalid resolved source address count" }
                val sources = List(count) { input.readBounded(45) }.toCollection(linkedSetOf())
                val plan = EffectiveUdpNetworkPlan(
                    actualBind,
                    interfaceId,
                    configuredDestination,
                    resolvedDestination,
                    configuredSource,
                    sources,
                    enumValueOf<ResolutionStatus>(input.readUTF()),
                    enumValueOf<ResolutionStatus>(input.readUTF()),
                    input.readNullableLong(),
                    input.readNullableLong(),
                    input.readLong(),
                    input.readNullableLong(),
                    input.readLong(),
                )
                require(input.available() == 0) { "Trailing effective UDP network plan bytes" }
                validate(plan)
                plan.immutableCopy()
            }
        } catch (error: IllegalArgumentException) {
            throw error
        } catch (error: Exception) {
            throw IllegalArgumentException("Malformed effective UDP network plan", error)
        }
    }

    private fun validate(plan: EffectiveUdpNetworkPlan) {
        require(plan.resolutionGeneration >= 0) { "Resolution generation must be nonnegative" }
        require(plan.resolvedSourceIpv4s.size <= MAX_SOURCE_ADDRESSES) { "Too many resolved source addresses" }
        require(plan.actualBindIpv4 == "0.0.0.0" || isCanonicalIpv4Literal(plan.actualBindIpv4)) { "Invalid bind IPv4" }
        plan.resolvedDestinationIpv4?.let { require(isCanonicalIpv4Literal(it)) { "Invalid destination IPv4" } }
        plan.resolvedSourceIpv4s.forEach { require(isCanonicalIpv4Literal(it)) { "Invalid source IPv4" } }
        require(plan.actualBindInterfaceId == null || plan.actualBindInterfaceId.toByteArray().size <= MAX_INTERFACE_ID_LENGTH) { "Interface identifier is too long" }
        listOf(plan.configuredDestination, plan.configuredSource).filterNotNull().forEach {
            require(it.length <= MAX_HOST_LENGTH) { "Configured host is too long" }
        }
    }

    private fun DataOutputStream.writeBounded(value: String) {
        val bytes = value.encodeToByteArray()
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataOutputStream.writeNullable(value: String?) {
        writeBoolean(value != null)
        value?.let { writeBounded(it) }
    }

    private fun DataInputStream.readBounded(maximum: Int): String {
        val length = readInt()
        require(length in 0..maximum && length <= available()) { "Invalid bounded string length" }
        return ByteArray(length).also(::readFully).decodeToString(throwOnInvalidSequence = true)
    }

    private fun DataInputStream.readNullable(maximum: Int) = if (readBoolean()) readBounded(maximum) else null
    private fun DataOutputStream.writeNullableLong(value: Long?) { writeBoolean(value != null); value?.let(::writeLong) }
    private fun DataInputStream.readNullableLong() = if (readBoolean()) readLong() else null

    private fun compareIpv4(first: String, second: String): Int =
        numericIpv4(first).compareTo(numericIpv4(second))

    private fun numericIpv4(value: String): Long = value.split('.').fold(0L) { result, octet ->
        (result shl 8) or octet.toLong()
    }
}
