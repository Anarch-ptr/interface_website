package com.filamentvision.backend.input.inspector

import com.filamentvision.backend.input.profile.*
import com.filamentvision.backend.input.runtime.ReceivedDatagramMetadata
import com.filamentvision.backend.input.transport.StructuredTransportHeader
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Optional latest-only observer. Producer taps never wait for a lease/polling lock. */
class PacketInspector(
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val leaseDurationMillis: Long = 30_000,
) {
    private val lock = ReentrantLock()
    private val leases = linkedMapOf<String, PacketInspectorLease>()
    private var snapshot: PacketInspectorSnapshot? = null
    private var sequence = 0L
    private var rawCaptureId = 0L
    @Volatile private var enabled = false
    @Volatile var previewCopies: Long = 0
        private set

    fun createLease(previewBytes: Int): PacketInspectorLease = lock.withLock {
        require(previewBytes in ALLOWED_PREVIEW_BYTES)
        expire()
        PacketInspectorLease(UUID.randomUUID().toString(), previewBytes,
            Math.addExact(clockMillis(), leaseDurationMillis)).also { leases[it.id] = it; enabled = true }
    }

    fun releaseLease(id: String): Boolean = lock.withLock {
        expire()
        val removed = leases.remove(id) != null
        discardIfInactive()
        removed
    }

    fun captureEnabled(): Boolean = observe(false) { true }

    /** Return a token so a skipped/newer capture cannot acquire this Datagram's later metadata. */
    fun captureRaw(bytes: ByteArray, length: Int, metadata: ReceivedDatagramMetadata,
        profile: RaspberryPiUdpInputProfile, status: RawParseStatus): Long = observe(0L) {
        require(length in 0..bytes.size)
        val limit = previewLimit()
        val header = normalizeTwoByteHex(profile.envelope.headerHex)
        val footer = normalizeTwoByteHex(profile.envelope.footerHex)
        // Source rejects never enter envelope validation, even for Inspector.
        val checkEnvelope = status != RawParseStatus.SOURCE_IP_MISMATCH && status != RawParseStatus.DATAGRAM_TOO_LARGE_OR_TRUNCATED
        val raw = RawDatagramInspection(metadata.source.address.hostAddress, metadata.source.port, profile.listenPort,
            length, metadata.receivedAtMonotonicNanos, status, header, footer,
            if (checkEnvelope) matches(bytes, length, 0, header) else null,
            if (checkEnvelope) matches(bytes, length, length - 2, footer) else null,
            bytes.copyOfRange(0, minOf(length, limit)))
        previewCopies++
        rawCaptureId = ++sequence
        snapshot = PacketInspectorSnapshot(sequence, raw, null)
        rawCaptureId
    }

    fun attachTransport(captureId: Long, header: StructuredTransportHeader): Boolean = observe(false) {
        val prior = current(captureId) ?: return@observe false
        val transport = TransportInspection(header.protocolVersion, header.compatibilityStreamId.toInt(),
            header.frameId.toString(), header.chunkIndex, header.chunkCount, header.sourceTimestampMicros,
            header.payloadLength, header.framePayloadBytes)
        snapshot = prior.copy(sequence = ++sequence, raw = prior.raw.copy(transport = transport))
        true
    }

    fun rejectTransport(captureId: Long): Boolean = observe(false) {
        val prior = current(captureId) ?: return@observe false
        snapshot = prior.copy(sequence = ++sequence, raw = prior.raw.copy(status = RawParseStatus.INVALID_TRANSPORT_HEADER))
        true
    }

    fun captureParsed(captureId: Long, bytes: ByteArray, payload: ImagePayloadProfile,
        decodeResult: String, width: Int?, height: Int?, receivedAtMonotonicNanos: Long): Boolean = observe(false) {
        val prior = current(captureId) ?: return@observe false
        val limit = minOf(previewLimit(), HARD_MAX_PREVIEW_BYTES - prior.raw.preview.size)
        val preview = bytes.copyOfRange(0, minOf(bytes.size, limit))
        previewCopies++
        val (format, dimensions) = when (payload) {
            is ImagePayloadProfile.Jpeg -> "JPEG" to (payload.expectedWidth to payload.expectedHeight)
            is ImagePayloadProfile.Gray8 -> "GRAY8" to (payload.width to payload.height)
            is ImagePayloadProfile.Binary -> "BINARY" to (payload.width to payload.height)
            is ImagePayloadProfile.Rgb888 -> "RGB888" to (payload.width to payload.height)
        }
        snapshot = prior.copy(sequence = ++sequence, parsedFrame = ParsedFrameInspection(format, decodeResult,
            width ?: dimensions.first, height ?: dimensions.second, bytes.size, receivedAtMonotonicNanos, preview))
        true
    }

    fun latest(leaseId: String, previewBytes: Int? = null): PacketInspectorSnapshot? = lock.withLock {
        expire()
        previewBytes?.let { require(it in ALLOWED_PREVIEW_BYTES) }
        val lease = leases[leaseId] ?: return null
        val limit = minOf(lease.previewBytes, previewBytes ?: lease.previewBytes)
        snapshot?.let { prior -> prior.copy(
            raw = prior.raw.copy(preview = prior.raw.preview.copyOf(minOf(prior.raw.preview.size, limit))),
            parsedFrame = prior.parsedFrame?.let { it.copy(preview = it.preview.copyOf(minOf(it.preview.size, limit))) },
        ) }
    }

    val retainedSnapshotCount: Int get() = lock.withLock { expire(); if (snapshot == null) 0 else 1 }
    val retainedPreviewBytes: Int get() = lock.withLock {
        expire(); snapshot?.let { it.raw.preview.size + (it.parsedFrame?.preview?.size ?: 0) } ?: 0
    }

    private fun current(captureId: Long) = snapshot.takeIf { captureId != 0L && captureId == rawCaptureId }
    private fun previewLimit() = minOf(leases.values.maxOf { it.previewBytes }, HARD_MAX_PREVIEW_BYTES / 2)
    private fun expire() {
        if (!enabled) return
        val now = clockMillis()
        leases.entries.removeIf { it.value.expiresAtMillis <= now }
        discardIfInactive()
    }
    private fun discardIfInactive() {
        enabled = leases.isNotEmpty()
        if (!enabled) { snapshot = null; rawCaptureId = 0 }
    }
    private inline fun <T> observe(skipped: T, block: () -> T): T {
        if (!enabled || !lock.tryLock()) return skipped
        return try {
            expire()
            if (enabled) block() else skipped
        } catch (_: Exception) {
            // Diagnostic failures cannot fail or backpressure the processing/reliable path.
            skipped
        } finally { lock.unlock() }
    }
    private fun matches(bytes: ByteArray, length: Int, offset: Int, hex: String): Boolean =
        offset >= 0 && offset + 2 <= length &&
            (bytes[offset].toInt() and 0xff) == hex.substring(0, 2).toInt(16) &&
            (bytes[offset + 1].toInt() and 0xff) == hex.substring(3, 5).toInt(16)

    companion object {
        /** Aggregate unique owned raw + completed-frame preview bytes. Transport owns no bytes. */
        const val HARD_MAX_PREVIEW_BYTES = 512
        val ALLOWED_PREVIEW_BYTES = setOf(64, 128, 256)
    }
}
