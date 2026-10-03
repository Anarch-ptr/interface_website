import { defaultRaspberryPiUdpInputProfile } from '../config/raspberryPiUdpInputProfile'
import type { InspectorSnapshot, NetworkInterfaces, UdpConfigurationState, UdpDiagnostics } from '../domain/raspberryPiUdpInput'

export const udpState = (): UdpConfigurationState => ({ savedRevision: 1, appliedRevision: 1,
  configuration: defaultRaspberryPiUdpInputProfile(), appliedConfiguration: defaultRaspberryPiUdpInputProfile(),
  protocolVersion: 1, transportMode: 'STRUCTURED_CHUNKED', outcome: null })
export const networkInterfaces: NetworkInterfaces = {
  interfaces: [
    { id: 'wifi', displayName: 'Wi-Fi', ipv4: '192.168.1.3', isUp: true, isLoopback: false, isVirtual: false, supportsMulticast: true },
    { id: 'vpn', displayName: 'VPN', ipv4: '10.0.0.2', isUp: true, isLoopback: false, isVirtual: true, supportsMulticast: false },
    { id: 'loopback', displayName: 'Loopback', ipv4: '127.0.0.1', isUp: true, isLoopback: true, isVirtual: false, supportsMulticast: false },
    { id: 'down', displayName: 'Offline', ipv4: '10.0.0.4', isUp: false, isLoopback: false, isVirtual: false, supportsMulticast: false },
  ], selection: { mode: 'AUTO' }, advertisedBackendIpv4: '192.168.1.3', listenPort: 5000,
  hasMultipleCandidates: true, statusDetail: null,
}
export const udpDiagnostics: UdpDiagnostics = {
  status: 'SOURCE_IP_MISMATCH', statusDetail: null, datagramsReceived: 3, bytesReceived: 90, framesStarted: null,
  framesCompleted: 0, framesDropped: 0, duplicateChunks: null, outOfOrderChunks: null, missingChunks: null, lateChunks: null,
  reassemblyTimeouts: null, invalidTransportHeaders: 0, oversizedFrames: 0, invalidHeaderCount: 1, invalidFooterCount: 2,
  invalidPayloadSizeCount: 0, decodeFailures: 4, sourceFilteredDatagrams: 3, oversizedOrTruncatedDatagrams: 0,
  lastPacketMonotonicNanos: 123, lastValidFrameMonotonicNanos: null, lastPacketSourceIpv4: '192.168.1.4',
  lastPacketSourcePort: 6000, lastPacketEpochMillis: 456, preAdmissionDroppedInputs: 5, processingBackpressureDrops: 6,
}
export const inspectorSnapshot = (sequence = 1): InspectorSnapshot => ({ sequence,
  rawDatagram: { sourceIpv4: '192.168.1.4', sourcePort: 6000, backendListenPort: 5000, datagramSize: 40,
    receivedAtMonotonicNanos: 123, status: 'HEADER_MISMATCH', headerHex: 'AB 01', footerHex: '0D 0A',
    expectedHeaderHex: 'AA 01', expectedFooterHex: '0D 0A', headerMatched: false, footerMatched: true,
    hexPreview: `AB 0${sequence}`, transport: null },
  parsedFrame: null,
})
export const apiResponse = (data: unknown, status = 200, etag?: string) => new Response(
  status === 304 ? null : JSON.stringify({ correlationId: 'test', data }),
  { status, headers: etag ? { ETag: etag } : undefined },
)
