package com.filamentvision.backend

import com.filamentvision.backend.application.PageQuery
import com.filamentvision.backend.simulator.SimulationScenario
import com.filamentvision.core.event.DomainEvent
import com.filamentvision.core.event.EventReplay
import com.filamentvision.core.metrics.MetricCounter
import com.filamentvision.core.model.MeasurementStatus
import com.filamentvision.core.repository.HistoryCursor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs

/** Development acceptance driver. Uses existing composition; no new monitoring or hardware API. */
object SustainedVerifier {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        require(args.isEmpty()) { "The acceptance duration is fixed at 1800 real seconds" }
        val output = Path.of("build", "phase2-final-sustained-${System.currentTimeMillis()}")
        Files.createDirectories(output)
        Files.newBufferedWriter(output.resolve("samples.csv")).use { writer ->
            fun log(line: String) { println(line); writer.appendLine(line); writer.flush() }
            val c = BackendComposition.open(output.resolve("monitoring.db"), output.resolve("objects"),
                inputSourceFactory = com.filamentvision.backend.input.runtime.SimulatorBackendInputSourceFactory)
            try {
                val scenarios = listOf(SimulationScenario.NORMAL, SimulationScenario.CAMERA_MISMATCH,
                    SimulationScenario.TEMPORARY_DROPOUT, SimulationScenario.NORMAL)
                var cursor = 0L; var measurementEvents = 0L; var alarmEvents = 0L; var finalizations = 0
                var mismatch = false; var dropout = false; var recovery = false
                var window = 0
                // Slow preview creates genuine lossy delivery independently of the reliable replay reader.
                val slowPreview = launch { c.previews.frames(c.previewKeys).collect { delay(1000) } }
                fun readEvents() {
                    val replay = c.events.replayAfter(c.deviceId, cursor)
                    check(replay is EventReplay.Available) { "Reliable replay reader lost its cursor: $replay" }
                    for (item in replay.events) {
                        check(item.sequence == cursor + 1) { "Non-contiguous event sequence" }; cursor = item.sequence
                        when (val event = item.event) {
                            is DomainEvent.Measurement -> {
                                measurementEvents++
                                val m = event.record.measurement
                                val a = m.cameraA; val b = m.cameraB
                                if (window == 1 && m.cameraA != null && m.cameraB != null && m.shapeDifference > .19) mismatch = true
                                if (window == 2 && m.status == MeasurementStatus.CAMERA_B_FAILURE) dropout = true
                                if (window == 3 && a != null && b != null && m.status == MeasurementStatus.NORMAL &&
                                    abs(a.diameterMm - 1.75) < 1e-9 && abs(b.diameterMm - 1.75) < 1e-9) recovery = true
                            }
                            is DomainEvent.Alarm -> alarmEvents++
                            is DomainEvent.SessionFinalized -> finalizations++
                            else -> Unit
                        }
                    }
                }
                val session = c.application.start(c.deviceId, "sustained-start", null, null).session!!.id
                val start = System.nanoTime()
                fun elapsed() = (System.nanoTime() - start) / 1_000_000
                suspend fun persisted(): Long {
                    var after: HistoryCursor? = null; var total = 0L
                    do { val page = c.application.history(session, PageQuery(after = after, limit = 1000))
                        total += page.items.size; after = page.nextCursor
                    } while (after != null)
                    return total
                }
                log("kind,wallMs,window,scenario,heapUsed,heapCommitted,producer,maxProducer,accepted,persisted,realtime,measurementQueue,alarmQueue,previewRetained,previewPending,previewDropped,replayRetained,eventSequence,snapshotEntries,snapshotBytes,runtimeCommands,facadeCommands,sourceDrops,processedA,processedB,rateA,rateB,processingMaxMs")
                suspend fun sample(kind: String) {
                    val state = c.runtime.state.value; val queues = c.runtime.queuedCounts()
                    val preview = c.previews.metrics; val event = c.events.metrics(c.deviceId); val snapshot = c.snapshotCache.state()
                    val metrics = c.metrics.snapshot(); val cameras = metrics.cameras.entries.sortedBy { it.key.cameraId.value }.map { it.value }
                    check(c.source.activeProducerCount <= 1 && c.source.maximumActiveProducerCount <= 1)
                    check(state.activeProducerCount <= 1 && state.maximumActiveProducerCount <= 1)
                    check(c.runtime.realtimeMeasurements().size <= 300 && queues.first <= 8 && queues.second <= 8)
                    check(preview.retainedFrames <= 2 && preview.pendingFrames <= 64 && preview.subscribers <= 32)
                    check(event.retained <= 256 && snapshot.entries <= 2 && snapshot.totalBytes <= 1024 * 1024)
                    check(state.cachedCommandCount <= 128 && c.cachedFacadeCommands <= 128)
                    val heap = Runtime.getRuntime()
                    log(listOf(kind, elapsed(), window, scenarios[window], heap.totalMemory() - heap.freeMemory(), heap.totalMemory(),
                        c.source.activeProducerCount, c.source.maximumActiveProducerCount, state.statistics.sampleCount, persisted(),
                        c.runtime.realtimeMeasurements().size, queues.first, queues.second, preview.retainedFrames, preview.pendingFrames,
                        preview.droppedFrames, event.retained, event.published, snapshot.entries, snapshot.totalBytes,
                        state.cachedCommandCount, c.cachedFacadeCommands, c.source.diagnostics.value.droppedFrames,
                        cameras[0].processed.count, cameras[1].processed.count, cameras[0].processed.perSecond, cameras[1].processed.perSecond,
                        metrics.latencies.values.maxOf { it.maximumMillis }).joinToString(","))
                }
                sample("START")
                var nextSample = 30_000L
                while (elapsed() < 1_800_000L) {
                    readEvents()
                    val desired = (elapsed() / 450_000).toInt().coerceAtMost(3)
                    if (desired != window) {
                        if (window == 1) check(mismatch) { "Mismatch evidence absent" }
                        if (window == 2) check(dropout) { "Dropout official status absent" }
                        window = desired; c.source.setScenario(scenarios[window]); sample("TRANSITION")
                    }
                    if (elapsed() >= nextSample) { sample("SAMPLE"); nextSample += 30_000 }
                    delay(20)
                }
                check(mismatch && dropout && recovery) { "Scenario evidence missing: mismatch=$mismatch dropout=$dropout recovery=$recovery" }
                c.application.stop(c.deviceId, "sustained-stop")
                slowPreview.cancelAndJoin(); readEvents(); sample("STOP")
                val accepted = c.runtime.state.value.statistics.sampleCount
                val stored = persisted()
                check(accepted == stored && accepted == measurementEvents)
                check(accepted == c.metrics.snapshot().counters.getValue(MetricCounter.DATABASE_ROW).count)
                check(c.application.session(session).finalization!!.statistics.sampleCount == accepted)
                check(c.application.alarms(session).size.toLong() == alarmEvents && finalizations == 1)
                check(c.source.activeProducerCount == 0 && c.source.maximumActiveProducerCount == 1 && c.source.totalProducerStarts == 1L)
                check(c.runtime.queuedCounts() == (0 to 0) && c.previews.metrics.pendingFrames == 0 && c.previews.metrics.droppedFrames > 0)
                val wallSeconds = elapsed() / 1000.0
                val rates = c.metrics.snapshot().cameras.values.map { it.processed.count / wallSeconds }
                check(rates.all { it in 7.0..8.5 }) { "8Hz camera cadence outside scheduler tolerance: $rates" }
                log("PASS wallSeconds=$wallSeconds accepted=$accepted persisted=$stored measurementEvents=$measurementEvents alarmEvents=$alarmEvents finalizations=$finalizations rates=$rates mismatch=$mismatch dropout=$dropout recovery=$recovery")
            } finally { c.close() }
        }
    }
}
