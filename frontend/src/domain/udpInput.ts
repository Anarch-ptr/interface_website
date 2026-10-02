export type CameraId = 'A' | 'B'
export type TransportMode = 'SINGLE_DATAGRAM' | 'STRUCTURED_CHUNKED'
export type PayloadFormat = 'GRAY8' | 'BINARY' | 'JPEG' | 'RGB888' | 'DIAMETER'
export type BinaryPacking = 'BYTE_PER_PIXEL' | 'BIT_PACKED'
export type ChannelOrder = 'RGB' | 'BGR'
export type DiameterEncoding = 'FLOAT32' | 'FLOAT64' | 'INT16' | 'INT32' | 'ASCII'
export type ByteOrder = 'BIG_ENDIAN' | 'LITTLE_ENDIAN'
export type DiameterUnit = 'MM' | 'MICROMETER' | 'INCH'
export type FramingFormat = 'HEX' | 'ASCII'
export type FramingScope = 'DATAGRAM' | 'FRAME'

export type UdpPayload =
  | { format: 'GRAY8'; width: number; height: number }
  | { format: 'BINARY'; width: number; height: number; binaryPacking: BinaryPacking }
  | { format: 'RGB888'; width: number; height: number; channelOrder: ChannelOrder }
  | { format: 'JPEG'; maxFrameBytes: number; expectedWidth: number | null; expectedHeight: number | null }
  | { format: 'DIAMETER'; valueEncoding: DiameterEncoding; byteOrder: ByteOrder; scale: number; offset: number;
      unit: DiameterUnit; directDiameterFusionConfidence: number }

export interface ApplicationFraming {
  headerEnabled: boolean; headerFormat: FramingFormat; headerBytes: string; headerScope: FramingScope; stripHeader: boolean
  footerEnabled: boolean; footerFormat: FramingFormat; footerBytes: string; footerScope: FramingScope; stripFooter: boolean
}
export interface TransportLimits {
  maxInFlightFrames: number; maxFrameBytes: number; frameTimeoutMs: number; maxChunkCount: number; maxChunkPayloadBytes: number
}
export interface UdpCameraProfile {
  enabled: boolean; sourceIp: string | null; listenPort: number; transportMode: TransportMode
  payload: UdpPayload; applicationFraming: ApplicationFraming; transportLimits: TransportLimits
}
export interface UdpInputProfileSet { cameraA: UdpCameraProfile; cameraB: UdpCameraProfile }
export interface UdpConfigurationState {
  savedRevision: number; appliedRevision: number; configuration: UdpInputProfileSet; outcome?: 'APPLIED' | null
}
export interface FieldError { path: string; message: string }
export interface UdpDiagnostics { cameraId: CameraId; transportMode: TransportMode; [key: string]: unknown }
export interface InspectorLease { leaseId: string; cameraId: CameraId; previewBytes: number; expiresAtMillis: number }
export interface InspectorSnapshot { sequence: number; cameraId: CameraId; rawDatagram: { hexPreview: string; status: string; [key: string]: unknown };
  parsedFrame: { hexPreview: string; payloadFormat: PayloadFormat; [key: string]: unknown } | null }
