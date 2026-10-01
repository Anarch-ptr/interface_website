package com.filamentvision.core.runtime

import com.filamentvision.core.alarm.AlarmTransition
import com.filamentvision.core.config.*
import com.filamentvision.core.frame.*
import com.filamentvision.core.fusion.*
import com.filamentvision.core.input.*
import com.filamentvision.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.TestScope
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*

internal class TestSource : ImageInputSource {
    override val state = MutableStateFlow(ImageInputState.IDLE)
    override val diagnostics = MutableStateFlow(ImageInputDiagnostics())
    private val active = AtomicInteger()
    val maximum = AtomicInteger()
    val starts = AtomicInteger()
    override val activeProducerCount get() = active.get()
    var startGate: CompletableDeferred<Unit>? = null
    var startFailure: Throwable? = null
    var stopFailure: Throwable? = null
    val enteredStart = CompletableDeferred<Unit>()
    val input = Channel<CameraFrame>(Channel.UNLIMITED)
    var lateOnStop: CameraFrame? = null
    private var downstream: FlowCollector<CameraFrame>? = null
    val collectors = mutableListOf<FlowCollector<CameraFrame>>()
    override val frames = object : Flow<CameraFrame> {
        override suspend fun collect(collector: FlowCollector<CameraFrame>) {
            downstream = collector
            collectors += collector
            for (frame in input) collector.emit(frame)
        }
    }
    override suspend fun start() {
        starts.incrementAndGet()
        maximum.updateAndGet { maxOf(it, active.incrementAndGet()) }
        state.value = ImageInputState.STARTING
        enteredStart.complete(Unit)
        startGate?.await()
        startFailure?.let { throw it }
        state.value = ImageInputState.RUNNING
    }
    override suspend fun stop() {
        stopFailure?.let { throw it }
        lateOnStop?.let { downstream?.emit(it) }
        active.set(0)
        state.value = ImageInputState.IDLE
    }
}

internal class RecordingSink : RuntimePersistence, RuntimeEventSink {
    var writeFailure: Throwable? = null
    val created = mutableListOf<RuntimeSession>()
    val measurements = mutableListOf<OfficialMeasurement>()
    val alarms = mutableListOf<AlarmTransition>()
    val finalized = mutableListOf<SessionFinalization>()
    val events = mutableListOf<SessionFinalization>()
    val order = mutableListOf<String>()
    val batchSizes = mutableListOf<Int>()
    override suspend fun createSession(session: RuntimeSession) { created += session }
    override suspend fun writeMeasurements(session: RuntimeSession, batch: List<OfficialMeasurement>) {
        writeFailure?.let { throw it }
        order += "measurements"
        batchSizes += batch.size
        measurements += batch
    }
    override suspend fun writeAlarms(session: RuntimeSession, batch: List<AlarmTransition>) {
        order += "alarms"
        alarms += batch
    }
    override suspend fun finalizeSession(finalization: SessionFinalization) {
        order += "finalize"
        finalized += finalization
    }
    override suspend fun sessionFinalized(finalization: SessionFinalization) {
        order += "event"
        events += finalization
    }
}

internal class Harness(scope: CoroutineScope, cacheCapacity: Int = 16) {
    val source = TestSource()
    val sink = RecordingSink()
    val runtime = MonitoringRuntime(
        scope = scope, source = source,
        configurations = mapOf(CameraId("A") to config, CameraId("B") to config),
        pairingStrategy = LatestCompatiblePairingStrategy(CameraId("A"), CameraId("B")),
        fusionConfiguration = FusionConfiguration(.25, .75, .7, 100),
        targetDiameter = .4, persistence = sink, eventSink = sink,
        commandCacheCapacity = cacheCapacity, epochMillis = { 1234L },
    )
    suspend fun send(count: Int, first: Long = 0, camera: String = "A") {
        repeat(count) { source.input.send(frame(first + it, camera)) }
    }
    fun assertFinalized(samples: Int, sessions: Int = 1) {
        assertEquals(sessions, sink.created.size)
        assertEquals(sessions, sink.finalized.size)
        assertEquals(sessions, sink.events.size)
        assertEquals(samples, sink.measurements.size)
        assertEquals(samples.toLong(), sink.finalized.sumOf { it.statistics.sampleCount })
        assertEquals(0, source.activeProducerCount)
        assertEquals(1, source.maximum.get())
        assertEquals(0, runtime.state.value.activeProducerCount)
    }
    companion object {
        val config = VisionConfiguration(CalibrationProfile(
            roiRight = 8, roiBottom = 4, grayscaleMethod = GrayscaleMethod.AVERAGE,
            manualThreshold = 40, edgeSensitivity = 30, minimumPixelWidth = 3,
            maximumPixelWidth = 6, mmPerPixel = .1, minimumConfidence = .5,
        ))
        fun frame(sequence: Long, camera: String = "A", width: Int = 4, lease: FrameLease = FrameLease()) = CameraFrame(
            CameraId(camera), 99 + sequence, 100, 8, 4, PixelFormat.ARGB32,
            PixelBuffer.ints(IntArray(32) {
                val v = if (it % 8 in 2 until 2 + width) 20 else 100
                0xFF000000.toInt() or (v shl 16) or (v shl 8) or v
            }), 0, false, sequence, lease = lease,
        )
    }
}
