package com.filamentvision.core.metrics

import com.filamentvision.core.frame.CameraId

data class MetricsCameraKey(val deviceId: String, val cameraId: CameraId)
enum class MetricCounter { FUSION, DROPPED_INPUT, DROPPED_PREVIEW, WEBSOCKET_PUBLICATION, DATABASE_BATCH, DATABASE_ROW }
enum class MetricLatency { PROCESSING, FUSION, PUBLICATION, DATABASE }
data class CounterRate(val count: Long, val perSecond: Double)
data class CameraMetrics(val received: CounterRate, val processed: CounterRate)
data class LatencyMetrics(val count: Long, val totalMillis: Double, val maximumMillis: Double)
data class MetricsSnapshot(val elapsedMillis: Long, val cameras: Map<MetricsCameraKey, CameraMetrics>,
    val counters: Map<MetricCounter, CounterRate>, val latencies: Map<MetricLatency, LatencyMetrics>)
/** Rates cover elapsed time since construction or the last reset, never a target cadence.
 * Inject non-negative monotonic milliseconds; backward readings freeze at the high-water mark.
 * Zero duration gives zero rates while retaining counts. Snapshot(reset=true) atomically returns
 * and clears the window. Each record call is atomic (separate calls are separate observations).
 * Fixed camera registry and enums bound state; latencies retain count/total/max, never samples.
 * Returned maps are detached, so caller mutation cannot affect subsequent snapshots.
 */
class MonitoringMetrics(cameras: Set<MetricsCameraKey>, private val clockMillis: () -> Long) {
    private val cameraCounts = cameras.associateWith { LongArray(2) }
    private val counters = LongArray(MetricCounter.entries.size)
    private val latencies = Array(MetricLatency.entries.size) { LatencyMetrics(0, 0.0, 0.0) }
    private var highWater = clockMillis().also { require(it >= 0) }
    private var started = highWater
    init { require(cameras.isNotEmpty() && cameras.all { it.deviceId.isNotBlank() }) }
    @Synchronized fun received(camera: MetricsCameraKey, count: Long = 1) = cameraCount(camera, 0, count)
    @Synchronized fun processed(camera: MetricsCameraKey, count: Long = 1) = cameraCount(camera, 1, count)
    private fun cameraCount(camera: MetricsCameraKey, index: Int, count: Long) {
        val values = requireNotNull(cameraCounts[camera]) { "Unregistered camera" }
        values[index] = add(values[index], count)
    }
    @Synchronized fun record(counter: MetricCounter, count: Long = 1) {
        counters[counter.ordinal] = add(counters[counter.ordinal], count)
    }
    @Synchronized fun latency(kind: MetricLatency, millis: Double) {
        require(millis.isFinite() && millis >= 0)
        val previous = latencies[kind.ordinal]
        val total = previous.totalMillis + millis
        require(total.isFinite()) { "Latency total overflow" }
        latencies[kind.ordinal] = LatencyMetrics(add(previous.count, 1), total, maxOf(previous.maximumMillis, millis))
    }
    @Synchronized fun snapshot(reset: Boolean = false): MetricsSnapshot {
        val reading = clockMillis()
        require(reading >= 0)
        highWater = maxOf(highWater, reading)
        val elapsed = highWater - started
        fun rate(count: Long) = CounterRate(count, if (elapsed == 0L) 0.0 else count.toDouble() / elapsed * 1000.0)
        val result = MetricsSnapshot(elapsed,
            cameraCounts.mapValues { CameraMetrics(rate(it.value[0]), rate(it.value[1])) },
            MetricCounter.entries.associateWith { rate(counters[it.ordinal]) },
            MetricLatency.entries.associateWith { latencies[it.ordinal] })
        if (reset) {
            cameraCounts.values.forEach { it.fill(0) }; counters.fill(0)
            latencies.fill(LatencyMetrics(0, 0.0, 0.0)); started = highWater
        }
        return result
    }
    private fun add(previous: Long, count: Long): Long {
        require(count >= 0 && previous <= Long.MAX_VALUE - count) { "Invalid count or overflow" }
        return previous + count
    }
}
