package com.filamentvision.backend.input.network

import com.filamentvision.backend.input.profile.isCanonicalIpv4Literal
import com.filamentvision.backend.input.profile.normalizeHostOrIpv4
import java.net.InetAddress
import java.util.Collections
import java.util.LinkedHashSet
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

fun interface HostLookup {
    fun lookup(hostname: String): List<String>
}

enum class HostResolutionStatus {
    RESOLVED,
    FAILED,
    RESULT_LIMIT_EXCEEDED,
    SATURATED,
}

data class HostResolution(
    val configuredHost: String,
    val ipv4s: Set<String>,
    val status: HostResolutionStatus,
    val resolvedAtEpochMillis: Long,
    val errorCode: String? = null,
)

fun interface Ipv4HostResolver {
    suspend fun resolveIpv4(hostOrIpv4: String): HostResolution
}

class HostResolver(
    private val lookup: HostLookup = HostLookup { hostname ->
        InetAddress.getAllByName(hostname).map(InetAddress::getHostAddress)
    },
    private val timeoutMillis: Long = 3_000,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val executor: ThreadPoolExecutor = newBoundedExecutor(),
) : Ipv4HostResolver, AutoCloseable {
    private val maximumActive = AtomicInteger()
    private val maximumQueued = AtomicInteger()

    val activeLookupCount: Int get() = executor.activeCount
    val queuedLookupCount: Int get() = executor.queue.size
    val maximumActiveLookupCount: Int get() = maximumActive.get()
    val maximumQueuedLookupCount: Int get() = maximumQueued.get()
    val retainedResultCount: Int get() = 0

    override suspend fun resolveIpv4(hostOrIpv4: String): HostResolution {
        val timestamp = clockMillis()
        val normalized = try {
            normalizeHostOrIpv4(hostOrIpv4)
        } catch (_: IllegalArgumentException) {
            return failure(hostOrIpv4, timestamp, HostResolutionStatus.FAILED, "INVALID_HOST_OR_IPV4")
        }
        if (isCanonicalIpv4Literal(normalized)) {
            return success(normalized, listOf(normalized), timestamp)
        }
        val lookupOutcome = withTimeoutOrNull(timeoutMillis) { submitLookup(normalized) }
            ?: return failure(normalized, timestamp, HostResolutionStatus.FAILED, "HOSTNAME_RESOLUTION_FAILED")
        val addresses = when (lookupOutcome) {
            is LookupOutcome.Answer -> lookupOutcome.addresses
            LookupOutcome.Failed -> return failure(normalized, timestamp, HostResolutionStatus.FAILED, "HOSTNAME_RESOLUTION_FAILED")
            LookupOutcome.Saturated -> return failure(normalized, timestamp, HostResolutionStatus.SATURATED, "HOSTNAME_RESOLUTION_FAILED")
        }
        val sorted = addresses.asSequence()
            .filter(::isCanonicalIpv4Literal)
            .distinct()
            .sortedBy(::numericIpv4)
            .toList()
        if (sorted.size > MAX_RESULTS) {
            return failure(normalized, timestamp, HostResolutionStatus.RESULT_LIMIT_EXCEEDED, "HOSTNAME_RESULT_LIMIT_EXCEEDED")
        }
        if (sorted.isEmpty()) {
            return failure(normalized, timestamp, HostResolutionStatus.FAILED, "HOSTNAME_RESOLUTION_FAILED")
        }
        return success(normalized, sorted, timestamp)
    }

    private suspend fun submitLookup(hostname: String): LookupOutcome = suspendCancellableCoroutine { continuation ->
        try {
            executor.execute {
                maximumActive.accumulateAndGet(executor.activeCount, ::maxOf)
                val answer = try {
                    LookupOutcome.Answer(lookup.lookup(hostname))
                } catch (_: Throwable) {
                    LookupOutcome.Failed
                }
                if (continuation.isActive) continuation.resume(answer)
            }
            maximumQueued.accumulateAndGet(executor.queue.size, ::maxOf)
        } catch (_: RejectedExecutionException) {
            continuation.resume(LookupOutcome.Saturated)
        }
    }

    private fun success(host: String, addresses: List<String>, timestamp: Long) = HostResolution(
        host,
        immutableOrderedSet(addresses),
        HostResolutionStatus.RESOLVED,
        timestamp,
    )

    private fun failure(host: String, timestamp: Long, status: HostResolutionStatus, code: String) = HostResolution(
        host,
        emptySet(),
        status,
        timestamp,
        code,
    )

    override fun close() {
        executor.shutdownNow()
    }

    private sealed interface LookupOutcome {
        data class Answer(val addresses: List<String>) : LookupOutcome
        data object Failed : LookupOutcome
        data object Saturated : LookupOutcome
    }

    private companion object {
        const val MAX_RESULTS = 16

        fun newBoundedExecutor() = ThreadPoolExecutor(
            2,
            2,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(2),
            ThreadFactory { runnable -> Thread(runnable, "udp-host-resolver").apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy(),
        )

        fun numericIpv4(value: String): Long = value.split('.').fold(0L) { result, octet ->
            (result shl 8) or octet.toLong()
        }

        fun immutableOrderedSet(values: List<String>): Set<String> =
            Collections.unmodifiableSet(LinkedHashSet(values))
    }
}
