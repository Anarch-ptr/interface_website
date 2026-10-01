package com.filamentvision.core.metrics

import com.filamentvision.core.frame.CameraId
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class MonitoringMetricsTest {
    private val a = MetricsCameraKey("device", CameraId("A"))
    private val b = MetricsCameraKey("device", CameraId("B"))
    @Test fun ratesAndLatencyTotalsAreIndependentOverElapsedWindow() {
        var now = 100L
        val metrics = MonitoringMetrics(setOf(a, b)) { now }
        metrics.received(a, 30); metrics.processed(a, 22); metrics.received(b, 14); metrics.processed(b, 10)
        MetricCounter.entries.forEachIndexed { i, kind -> metrics.record(kind, (i + 1).toLong()) }
        MetricLatency.entries.forEachIndexed { i, kind -> metrics.latency(kind, i + 0.5); metrics.latency(kind, i + 1.5) }
        now = 2100
        val s = metrics.snapshot()
        assertEquals(2000L, s.elapsedMillis)
        assertEquals(CameraMetrics(CounterRate(30, 15.0), CounterRate(22, 11.0)), s.cameras[a])
        assertEquals(CameraMetrics(CounterRate(14, 7.0), CounterRate(10, 5.0)), s.cameras[b])
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 6L), MetricCounter.entries.map { s.counters[it]!!.count })
        assertEquals(listOf(.5, 1.0, 1.5, 2.0, 2.5, 3.0), MetricCounter.entries.map { s.counters[it]!!.perSecond })
        assertEquals(listOf(2.0, 4.0, 6.0, 8.0), MetricLatency.entries.map { s.latencies[it]!!.totalMillis })
        assertEquals(listOf(1.5, 2.5, 3.5, 4.5), MetricLatency.entries.map { s.latencies[it]!!.maximumMillis })
        assertTrue(s.latencies.values.all { it.count == 2L })
    }
    @Test fun zeroDurationRollbackAndAtomicResetHaveExplicitWindows() {
        var now = 100L
        val metrics = MonitoringMetrics(setOf(a)) { now }
        metrics.received(a, 3); metrics.record(MetricCounter.FUSION, 2); metrics.latency(MetricLatency.FUSION, 1.0)
        assertEquals(CounterRate(3, 0.0), metrics.snapshot().cameras[a]!!.received)
        now = 1100; val before = metrics.snapshot(); now = 0
        assertEquals(before, metrics.snapshot()); val reset = metrics.snapshot(reset = true)
        assertEquals(before, reset); assertEquals(CounterRate(0, 0.0), metrics.snapshot().cameras[a]!!.received)
        assertTrue(metrics.snapshot().latencies.values.all { it.count == 0L && it.totalMillis == 0.0 })
        now = 2100; metrics.received(a)
        assertEquals(CounterRate(1, 1.0), metrics.snapshot().cameras[a]!!.received)
        assertEquals(CounterRate(3, 3.0), before.cameras[a]!!.received)
    }
    @Test fun concurrentRecordingAndResetLoseNoCountsAndRetainFixedKeys() {
        val metrics = MonitoringMetrics(setOf(a, b)) { 0 }
        val executor = Executors.newFixedThreadPool(5)
        try {
            val writers = (1..4).map { executor.submit { repeat(5000) { metrics.received(a); metrics.processed(b); metrics.record(MetricCounter.FUSION); metrics.latency(MetricLatency.PROCESSING, .5) } } }
            val reader = executor.submit<List<MetricsSnapshot>> { (1..100).map { metrics.snapshot(reset = true) } }
            writers.forEach { it.get(10, TimeUnit.SECONDS) }
            val windows = reader.get(10, TimeUnit.SECONDS) + metrics.snapshot()
            assertEquals(20000L, windows.sumOf { it.cameras[a]!!.received.count })
            assertEquals(20000L, windows.sumOf { it.cameras[b]!!.processed.count })
            assertEquals(20000L, windows.sumOf { it.counters[MetricCounter.FUSION]!!.count })
            assertEquals(10000.0, windows.sumOf { it.latencies[MetricLatency.PROCESSING]!!.totalMillis }, 0.0)
            assertTrue(windows.all { it.cameras.size == 2 && it.counters.size == 6 && it.latencies.size == 4 })
        } finally { executor.shutdownNow() }
    }
    @Test fun invalidInputsAndOverflowDoNotCorruptCounters() {
        val registry = mutableSetOf(a)
        val metrics = MonitoringMetrics(registry) { 0 }; registry.add(b)
        assertThrows(IllegalArgumentException::class.java) { metrics.received(b) }
        assertThrows(IllegalArgumentException::class.java) { metrics.received(a, -1) }
        assertThrows(IllegalArgumentException::class.java) { metrics.processed(a, -1) }
        assertThrows(IllegalArgumentException::class.java) { metrics.record(MetricCounter.FUSION, -1) }
        listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY).forEach { bad ->
            assertThrows(IllegalArgumentException::class.java) { metrics.latency(MetricLatency.DATABASE, bad) }
        }
        metrics.record(MetricCounter.FUSION, Long.MAX_VALUE)
        assertThrows(IllegalArgumentException::class.java) { metrics.record(MetricCounter.FUSION) }
        assertEquals(Long.MAX_VALUE, metrics.snapshot().counters[MetricCounter.FUSION]!!.count)
        assertEquals(CounterRate(0, 0.0), metrics.snapshot().cameras[a]!!.received)
    }
    @Test fun latencyOverflowAndNegativeClockLeaveTheLastGoodWindowIntact() {
        var now = 0L
        val metrics = MonitoringMetrics(setOf(a)) { now }
        metrics.latency(MetricLatency.PROCESSING, Double.MAX_VALUE)
        assertThrows(IllegalArgumentException::class.java) { metrics.latency(MetricLatency.PROCESSING, Double.MAX_VALUE) }
        now = -1
        assertThrows(IllegalArgumentException::class.java) { metrics.snapshot(reset = true) }
        now = 1000
        assertEquals(LatencyMetrics(1, Double.MAX_VALUE, Double.MAX_VALUE), metrics.snapshot().latencies[MetricLatency.PROCESSING])
    }
}
