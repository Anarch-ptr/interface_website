package com.filamentvision.backend.input.network

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HostResolverTest {
    @Test
    fun literalBypassesLookupAndReturnsTimestampedImmutableSingleton() = runBlocking {
        var lookupCalls = 0
        HostResolver(HostLookup { lookupCalls++; emptyList() }, clockMillis = { 42L }).use { resolver ->
            val result = resolver.resolveIpv4("192.168.1.7")
            assertEquals(HostResolutionStatus.RESOLVED, result.status)
            assertEquals(linkedSetOf("192.168.1.7"), result.ipv4s)
            assertEquals(42L, result.resolvedAtEpochMillis)
            assertEquals(0, lookupCalls)
            assertUnsupported { (result.ipv4s as MutableSet).add("192.168.1.8") }
        }
    }

    @Test
    fun hostnameFiltersIpv6NormalizesDeduplicatesAndSortsIpv4Numerically() = runBlocking {
        HostResolver(HostLookup { listOf("192.168.1.20", "2001:db8::1", "10.0.0.2", "192.168.1.3", "10.0.0.2") }).use { resolver ->
            val result = resolver.resolveIpv4("PI.LOCAL.")
            assertEquals(HostResolutionStatus.RESOLVED, result.status)
            assertEquals(listOf("10.0.0.2", "192.168.1.3", "192.168.1.20"), result.ipv4s.toList())
            assertEquals("pi.local", result.configuredHost)
        }
    }

    @Test
    fun zeroResultsLookupFailureTimeoutAndTooManyResultsAreTypedFailures() = runBlocking {
        val cases = listOf(
            HostResolver(HostLookup { emptyList() }) to HostResolutionStatus.FAILED,
            HostResolver(HostLookup { throw IllegalStateException("dns down") }) to HostResolutionStatus.FAILED,
            HostResolver(HostLookup { Thread.sleep(250); listOf("10.0.0.1") }, timeoutMillis = 20) to HostResolutionStatus.FAILED,
            HostResolver(HostLookup { (1..17).map { "10.0.0.$it" } }) to HostResolutionStatus.RESULT_LIMIT_EXCEEDED,
        )
        cases.forEach { (resolver, expected) ->
            resolver.use {
                val result = it.resolveIpv4("pi.local")
                assertEquals(expected, result.status)
                assertTrue(result.ipv4s.isEmpty())
            }
        }
    }

    @Test
    fun repeatedLookupDoesNotRetainHistoryAndCanReturnChangedAddresses() = runBlocking {
        var answer = "10.0.0.1"
        HostResolver(HostLookup { listOf(answer) }).use { resolver ->
            val first = resolver.resolveIpv4("pi.local")
            answer = "10.0.0.2"
            val second = resolver.resolveIpv4("pi.local")
            assertNotEquals(first.ipv4s, second.ipv4s)
            assertEquals(setOf("10.0.0.2"), second.ipv4s)
            assertEquals(0, resolver.retainedResultCount)
        }
    }

    @Test
    fun executorAllowsTwoActiveTwoQueuedThenRejectsWithoutUnboundedGrowth() = runBlocking {
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        HostResolver(HostLookup {
            entered.countDown()
            release.await(2, TimeUnit.SECONDS)
            listOf("10.0.0.1")
        }, timeoutMillis = 2_000).use { resolver ->
            val firstFour = (1..4).map { index -> async(Dispatchers.Default) { resolver.resolveIpv4("pi-$index.local") } }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            while (resolver.queuedLookupCount < 2) Thread.yield()
            val rejected = resolver.resolveIpv4("pi-5.local")
            assertEquals(HostResolutionStatus.SATURATED, rejected.status)
            assertTrue(resolver.activeLookupCount <= 2)
            assertTrue(resolver.queuedLookupCount <= 2)
            assertTrue(resolver.maximumActiveLookupCount <= 2)
            assertTrue(resolver.maximumQueuedLookupCount <= 2)
            release.countDown()
            assertTrue(firstFour.awaitAll().all { it.status == HostResolutionStatus.RESOLVED })
        }
    }

    private fun assertUnsupported(action: () -> Unit) {
        var failed = false
        try { action() } catch (_: UnsupportedOperationException) { failed = true }
        assertTrue(failed)
    }
}
