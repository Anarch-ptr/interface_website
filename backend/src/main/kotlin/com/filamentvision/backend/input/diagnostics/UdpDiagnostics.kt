package com.filamentvision.backend.input.diagnostics

import com.filamentvision.backend.input.profile.TransportMode
import com.filamentvision.backend.input.runtime.UdpProcessingStage
import com.filamentvision.backend.input.runtime.RaspberryPiProcessingStage
import com.filamentvision.backend.input.runtime.ReceivedDatagramMetadata
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

data class UdpDiagnosticsSnapshot(
    val transportMode: TransportMode,
    val datagramsReceived: Long, val bytesReceived: Long,
    val framesStarted: Long?, val framesCompleted: Long, val framesDropped: Long,
    val duplicateChunks: Long?, val outOfOrderChunks: Long?, val missingChunks: Long?,
    val lateChunks: Long?, val reassemblyTimeouts: Long?,
    val invalidTransportHeaders: Long, val oversizedFrames: Long,
    val invalidHeaderCount: Long, val invalidFooterCount: Long,
    val invalidPayloadSizeCount: Long, val decodeFailures: Long,
    val sourceFilteredDatagrams: Long, val oversizedOrTruncatedDatagrams: Long,
    val lastPacketMonotonicNanos: Long?, val lastValidFrameMonotonicNanos: Long?,
    val lastPacketSourceIpv4: String? = null, val lastPacketSourcePort: Int? = null,
    val lastPacketEpochMillis: Long? = null, val preAdmissionDroppedInputs: Long = 0,
    val processingBackpressureDrops: Long = 0,
)

/** Fixed-size per-camera counters. A snapshot allocates one immutable value and no history. */
class UdpDiagnostics(private val transportMode: TransportMode) {
    private val datagrams = AtomicLong(); private val bytes = AtomicLong()
    private val started = AtomicLong(); private val completed = AtomicLong(); private val dropped = AtomicLong()
    private val duplicates = AtomicLong(); private val outOfOrder = AtomicLong(); private val missing = AtomicLong()
    private val late = AtomicLong(); private val timeouts = AtomicLong()
    private val invalidTransport = AtomicLong(); private val oversized = AtomicLong()
    private val invalidHeader = AtomicLong(); private val invalidFooter = AtomicLong(); private val invalidPayload = AtomicLong()
    private val decode = AtomicLong(); private val sourceFiltered = AtomicLong(); private val truncated = AtomicLong()
    private val lastPacket = AtomicLong(NO_TIME); private val lastValid = AtomicLong(NO_TIME)
    private val lastMetadata = AtomicReference<ReceivedDatagramMetadata?>(null)
    private val preAdmissionDrops = AtomicLong()
    private val processingBackpressure = AtomicLong()

    fun datagramReceived(size: Int, monotonicNanos: Long) { datagrams.incrementAndGet(); bytes.addAndGet(size.toLong()); lastPacket.set(monotonicNanos) }
    fun datagramReceived(size: Int, metadata: ReceivedDatagramMetadata) {
        datagramReceived(size, metadata.receivedAtMonotonicNanos)
        lastMetadata.set(metadata)
    }
    fun preAdmissionDropped() { preAdmissionDrops.incrementAndGet(); dropped.incrementAndGet() }
    fun rejected(stage: RaspberryPiProcessingStage, reason: String) = rejected(when (stage) {
        RaspberryPiProcessingStage.ENVELOPE -> UdpProcessingStage.DATAGRAM_FRAMING
        RaspberryPiProcessingStage.TRANSPORT -> UdpProcessingStage.TRANSPORT
        RaspberryPiProcessingStage.REASSEMBLY -> UdpProcessingStage.REASSEMBLY
        RaspberryPiProcessingStage.DECODER -> UdpProcessingStage.DECODER
    }, reason)
    fun frameStarted() { started.incrementAndGet() }
    fun frameCompleted(monotonicNanos: Long) { completed.incrementAndGet(); lastValid.set(monotonicNanos) }
    fun chunkDuplicate() { duplicates.incrementAndGet() }
    fun chunkOutOfOrder() { outOfOrder.incrementAndGet() }
    fun missingChunk(count: Long = 1) { missing.addAndGet(count) }
    fun lateChunk() { late.incrementAndGet() }
    fun reassemblyTimeout(missingCount: Long = 0) { timeouts.incrementAndGet(); missing.addAndGet(missingCount); dropped.incrementAndGet() }
    fun reassemblyEviction(missingCount: Long = 0) { missing.addAndGet(missingCount); dropped.incrementAndGet() }
    fun sourceFiltered() { sourceFiltered.incrementAndGet() }
    fun datagramTooLargeOrTruncated() { truncated.incrementAndGet() }
    fun rejected(stage: UdpProcessingStage, reason: String) {
        dropped.incrementAndGet()
        when {
            stage == UdpProcessingStage.REASSEMBLY && reason == "PROCESSING_BACKPRESSURE" -> processingBackpressure.incrementAndGet()
            stage == UdpProcessingStage.TRANSPORT -> invalidTransport.incrementAndGet()
            reason == "OVERSIZED_FRAME" -> oversized.incrementAndGet()
            reason == "HEADER_MISMATCH" || reason == "INVALID_HEADER" -> invalidHeader.incrementAndGet()
            reason == "FOOTER_MISMATCH" || reason == "INVALID_FOOTER" -> invalidFooter.incrementAndGet()
            reason == "INVALID_PAYLOAD_SIZE" -> { invalidPayload.incrementAndGet(); if (stage == UdpProcessingStage.DECODER) decode.incrementAndGet() }
            stage == UdpProcessingStage.DECODER -> decode.incrementAndGet()
        }
    }
    fun snapshot(): UdpDiagnosticsSnapshot {
        val chunked = transportMode == TransportMode.STRUCTURED_CHUNKED
        fun AtomicLong.time() = get().takeUnless { it == NO_TIME }
        val metadata = lastMetadata.get()
        return UdpDiagnosticsSnapshot(transportMode, datagrams.get(), bytes.get(), started.get().takeIf { chunked },
            completed.get(), dropped.get(), duplicates.get().takeIf { chunked }, outOfOrder.get().takeIf { chunked },
            missing.get().takeIf { chunked }, late.get().takeIf { chunked }, timeouts.get().takeIf { chunked },
            invalidTransport.get(), oversized.get(), invalidHeader.get(), invalidFooter.get(), invalidPayload.get(), decode.get(),
            sourceFiltered.get(), truncated.get(), lastPacket.time(), lastValid.time(),
            metadata?.source?.address?.hostAddress, metadata?.source?.port, metadata?.receivedAtEpochMillis, preAdmissionDrops.get(),
            processingBackpressure.get())
    }
    private companion object { const val NO_TIME = Long.MIN_VALUE }
}
