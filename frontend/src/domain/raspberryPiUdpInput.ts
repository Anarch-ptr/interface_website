/** Backend expected input only: Save/Apply never configures the Raspberry Pi sender. */
export type PayloadFormat = 'JPEG' | 'GRAY8' | 'BINARY' | 'RGB888'
export type ImagePayload =
  | { format: 'JPEG'; maxFrameBytes: number; expectedWidth?: number | null; expectedHeight?: number | null }
  | { format: 'GRAY8'; width: number; height: number }
  | { format: 'BINARY'; width: number; height: number; packing: 'BYTE_PER_PIXEL' | 'BIT_PACKED' }
  | { format: 'RGB888'; width: number; height: number; channelOrder: 'RGB' | 'BGR' }
export type NetworkInterfaceSelection =
  | { mode: 'AUTO' }
  | { mode: 'SPECIFIC'; interfaceId: string; ipv4: string }
export interface DatagramEnvelope { headerHex: string; footerHex: string }
export interface TransportLimits {
  maxInFlightFrames: number; maxFrameBytes: number; frameTimeoutMs: number; maxChunkCount: number; maxChunkPayloadBytes: number
}
export interface RaspberryPiUdpInputProfile {
  enabled: boolean; networkInterface: NetworkInterfaceSelection; listenPort: number; raspberryPiIpv4: string | null
  payload: ImagePayload; envelope: DatagramEnvelope; transportLimits: TransportLimits
}
export interface UdpConfigurationState {
  savedRevision: number; appliedRevision: number; configuration: RaspberryPiUdpInputProfile
  appliedConfiguration: RaspberryPiUdpInputProfile; outcome: 'APPLIED' | 'CONFLICT' | 'INVALID' | null
  protocolVersion: 1; transportMode: 'STRUCTURED_CHUNKED'
}
export interface SaveUdpConfigurationRequest {
  commandId: string; expectedSavedRevision: number; configuration: RaspberryPiUdpInputProfile
}
export interface ApplyUdpConfigurationRequest { commandId: string; revision: number; expectedSavedRevision: number }
export interface FieldError { path: string; message: string }
export interface Ipv4NetworkInterface {
  id: string; displayName: string; ipv4: string; isUp: boolean; isLoopback: boolean; isVirtual: boolean; supportsMulticast: boolean
}
export interface NetworkInterfaces {
  interfaces: Ipv4NetworkInterface[]; selection: NetworkInterfaceSelection; advertisedBackendIpv4: string | null
  listenPort: number; hasMultipleCandidates: boolean; statusDetail: string | null
}
export type UdpOperationalStatus = 'DISABLED' | 'UDP_READY' | 'WAITING_FOR_RASPBERRY_PI' | 'RECEIVING'
  | 'NO_PACKETS_RECENTLY' | 'SOURCE_IP_MISMATCH' | 'BIND_FAILED'
export interface UdpDiagnostics {
  status: UdpOperationalStatus; statusDetail: string | null; datagramsReceived: number; bytesReceived: number
  framesStarted: number | null; framesCompleted: number; framesDropped: number; duplicateChunks: number | null
  outOfOrderChunks: number | null; missingChunks: number | null; lateChunks: number | null; reassemblyTimeouts: number | null
  invalidTransportHeaders: number; oversizedFrames: number; invalidHeaderCount: number; invalidFooterCount: number
  invalidPayloadSizeCount: number; decodeFailures: number; sourceFilteredDatagrams: number; oversizedOrTruncatedDatagrams: number
  lastPacketMonotonicNanos: number | null; lastValidFrameMonotonicNanos: number | null; lastPacketSourceIpv4: string | null
  lastPacketSourcePort: number | null; lastPacketEpochMillis: number | null; preAdmissionDroppedInputs: number; processingBackpressureDrops: number
}
export type InspectorPreviewBytes = 64 | 128 | 256
export interface InspectorLease { leaseId: string; previewBytes: number; expiresAtMillis: number }
export type RawParseStatus = 'VALID' | 'SOURCE_IP_MISMATCH' | 'DATAGRAM_TOO_LARGE_OR_TRUNCATED'
  | 'DATAGRAM_TOO_SHORT' | 'HEADER_MISMATCH' | 'FOOTER_MISMATCH' | 'INVALID_TRANSPORT_HEADER'
export interface TransportInspection {
  protocolVersion: number; compatibilityStreamId: number
  /** uint64 decimal text from Backend; never convert to a JavaScript number. */
  frameId: string
  chunkIndex: number; chunkCount: number; sourceTimestampMicros: number; payloadLength: number
  framePayloadBytes: number; status: string; hexPreview: string
}
export interface RawDatagramInspection {
  sourceIpv4: string; sourcePort: number; backendListenPort: number; datagramSize: number; receivedAtMonotonicNanos: number
  status: RawParseStatus; headerHex: string | null; footerHex: string | null; expectedHeaderHex: string; expectedFooterHex: string
  headerMatched: boolean | null; footerMatched: boolean | null; hexPreview: string; transport: TransportInspection | null
}
export interface ParsedFrameInspection {
  payloadFormat: PayloadFormat; decodeResult: string; width: number | null; height: number | null
  completedFrameBytes: number; receivedAtMonotonicNanos: number; hexPreview: string
}
export interface InspectorSnapshot { sequence: number; rawDatagram: RawDatagramInspection; parsedFrame: ParsedFrameInspection | null }
export type InspectorPollResult = { unchanged: true; etag: string | null }
  | { unchanged: false; etag: string | null; snapshot: InspectorSnapshot }
