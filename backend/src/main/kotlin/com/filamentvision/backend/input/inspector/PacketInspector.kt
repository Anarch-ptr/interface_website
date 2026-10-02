package com.filamentvision.backend.input.inspector

import com.filamentvision.backend.input.profile.UdpCameraId
import java.net.InetSocketAddress
import java.util.UUID

/** Lease-controlled, latest-only diagnostic tap. Processing never reads this state. */
class PacketInspector(
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val leaseDurationMillis: Long = 30_000,
) {
    private val leases = linkedMapOf<String, PacketInspectorLease>()
    private val snapshots = mutableMapOf<UdpCameraId, PacketInspectorSnapshot>()
    private var sequence = 0L
    var previewCopies: Long = 0
        private set

    @Synchronized fun createLease(cameraId: UdpCameraId, previewBytes: Int): PacketInspectorLease {
        require(previewBytes in ALLOWED_PREVIEW_BYTES)
        expire()
        return PacketInspectorLease(UUID.randomUUID().toString(), cameraId, previewBytes,
            Math.addExact(clockMillis(), leaseDurationMillis)).also { leases[it.id] = it }
    }

    @Synchronized fun releaseLease(id: String, cameraId: UdpCameraId): Boolean {
        expire()
        val lease = leases[id]?.takeIf { it.cameraId == cameraId } ?: return false
        leases.remove(lease.id)
        discardIfInactive(cameraId)
        return true
    }

    @Synchronized fun captureEnabled(cameraId: UdpCameraId): Boolean {
        expire(); return leases.values.any { it.cameraId == cameraId }
    }

    @Synchronized fun captureRaw(cameraId: UdpCameraId, bytes: ByteArray, length: Int,
        source: InetSocketAddress, receivedAtMonotonicNanos: Long, status: RawParseStatus,
        transport: TransportInspection? = null): Boolean {
        require(length in 0..bytes.size)
        val limit = activePreviewLimit(cameraId) ?: return false
        val preview = bytes.copyOfRange(0, minOf(length, limit, HARD_MAX_PREVIEW_BYTES))
        previewCopies++
        snapshots[cameraId] = PacketInspectorSnapshot(++sequence, cameraId,
            RawDatagramInspection(source.address.hostAddress, source.port, length, receivedAtMonotonicNanos,
                status, preview, transport), null)
        return true
    }

    @Synchronized fun captureParsed(cameraId: UdpCameraId, bytes: ByteArray, payloadFormat: String,
        decodeResult: String, applicationHeaderBytes: Int, applicationFooterBytes: Int,
        receivedAtMonotonicNanos: Long): Boolean {
        val limit = activePreviewLimit(cameraId) ?: return false
        val prior = snapshots[cameraId] ?: return false
        val preview = bytes.copyOfRange(0, minOf(bytes.size, limit, HARD_MAX_PREVIEW_BYTES))
        previewCopies++
        snapshots[cameraId] = prior.copy(sequence = ++sequence, parsedFrame = ParsedFrameInspection(payloadFormat,
            decodeResult, applicationHeaderBytes, applicationFooterBytes, receivedAtMonotonicNanos, preview))
        return true
    }

    @Synchronized fun attachTransport(cameraId: UdpCameraId, transport: TransportInspection): Boolean {
        activePreviewLimit(cameraId) ?: return false
        val prior = snapshots[cameraId] ?: return false
        snapshots[cameraId] = prior.copy(sequence = ++sequence, raw = prior.raw.copy(transport = transport))
        return true
    }

    @Synchronized fun latest(leaseId: String, cameraId: UdpCameraId, previewBytes: Int? = null): PacketInspectorSnapshot? {
        expire()
        val lease = leases[leaseId]?.takeIf { it.cameraId == cameraId } ?: return null
        previewBytes?.let { require(it in ALLOWED_PREVIEW_BYTES) }
        return snapshots[cameraId]?.bounded(minOf(lease.previewBytes, previewBytes ?: lease.previewBytes))
    }

    @get:Synchronized val retainedSnapshotCount: Int get() { expire(); return snapshots.size }
    @get:Synchronized val retainedPreviewBytes: Int get() { expire(); return snapshots.values.sumOf {
        it.raw.preview.size + (it.parsedFrame?.preview?.size ?: 0)
    } }

    private fun PacketInspectorSnapshot.bounded(limit: Int) = copy(
        raw = raw.copy(preview = raw.preview.copyOf(minOf(raw.preview.size, limit))),
        parsedFrame = parsedFrame?.copy(preview = parsedFrame.preview.copyOf(minOf(parsedFrame.preview.size, limit))),
    )
    private fun activePreviewLimit(cameraId: UdpCameraId): Int? {
        expire()
        return leases.values.filter { it.cameraId == cameraId }.maxOfOrNull { it.previewBytes }
    }
    private fun expire() {
        val now = clockMillis()
        val cameras = leases.values.filter { it.expiresAtMillis <= now }.map { it.cameraId }.toSet()
        leases.entries.removeIf { it.value.expiresAtMillis <= now }
        cameras.forEach(::discardIfInactive)
    }
    private fun discardIfInactive(cameraId: UdpCameraId) {
        if (leases.values.none { it.cameraId == cameraId }) snapshots.remove(cameraId)
    }
    companion object {
        const val HARD_MAX_PREVIEW_BYTES = 512
        val ALLOWED_PREVIEW_BYTES = setOf(64, 128, 256, 512)
    }
}
