import type { ApplicationFraming, FieldError, PayloadFormat, TransportLimits, TransportMode, UdpCameraProfile, UdpInputProfileSet } from '../domain/udpInput'

const framing = (): ApplicationFraming => ({ headerEnabled: false, headerFormat: 'HEX', headerBytes: '', headerScope: 'DATAGRAM', stripHeader: true,
  footerEnabled: false, footerFormat: 'HEX', footerBytes: '', footerScope: 'DATAGRAM', stripFooter: true })
const limits = (): TransportLimits => ({ maxInFlightFrames: 4, maxFrameBytes: 8 * 1024 * 1024, frameTimeoutMs: 1000,
  maxChunkCount: 4096, maxChunkPayloadBytes: 1400 })
const camera = (port: number): UdpCameraProfile => ({ enabled: true, sourceIp: null, listenPort: port,
  transportMode: 'STRUCTURED_CHUNKED', payload: { format: 'GRAY8', width: 640, height: 480 },
  applicationFraming: framing(), transportLimits: limits() })

export const defaultUdpProfiles = (): UdpInputProfileSet => ({ cameraA: camera(5101), cameraB: camera(5102) })
export const recommendedTransport = (format: PayloadFormat): TransportMode => format === 'DIAMETER' ? 'SINGLE_DATAGRAM' : 'STRUCTURED_CHUNKED'

export function validateUdpProfiles(value: UdpInputProfileSet): FieldError[] {
  const errors: FieldError[] = []
  validateCamera('cameraA', value.cameraA, errors)
  validateCamera('cameraB', value.cameraB, errors)
  if (value.cameraA.enabled && value.cameraB.enabled && value.cameraA.listenPort === value.cameraB.listenPort) {
    errors.push(error('cameraA.listenPort', 'Enabled cameras require different ports'), error('cameraB.listenPort', 'Enabled cameras require different ports'))
  }
  return errors
}

function validateCamera(path: 'cameraA' | 'cameraB', value: UdpCameraProfile, errors: FieldError[]) {
  if (!Number.isInteger(value.listenPort) || value.listenPort < 1 || value.listenPort > 65535) errors.push(error(`${path}.listenPort`, 'Port must be 1..65535'))
  if (value.sourceIp !== null && !ipv4(value.sourceIp)) errors.push(error(`${path}.sourceIp`, 'Version 1 accepts an IPv4 literal only'))
  if ('width' in value.payload) {
    dimension(`${path}.payload.width`, value.payload.width, errors); dimension(`${path}.payload.height`, value.payload.height, errors)
    const channels = value.payload.format === 'RGB888' ? 3 : 1
    const bytes = value.payload.width * value.payload.height * channels
    if (!Number.isSafeInteger(bytes) || bytes > 64 * 1024 * 1024) errors.push(error(`${path}.payload.decodedBytes`, 'Decoded image is too large'))
  }
  if (value.payload.format === 'JPEG') {
    bounded(`${path}.payload.maxFrameBytes`, value.payload.maxFrameBytes, 1, 32 * 1024 * 1024, errors)
    if (value.payload.expectedWidth !== null) dimension(`${path}.payload.expectedWidth`, value.payload.expectedWidth, errors)
    if (value.payload.expectedHeight !== null) dimension(`${path}.payload.expectedHeight`, value.payload.expectedHeight, errors)
  }
  if (value.payload.format === 'DIAMETER') {
    if (!Number.isFinite(value.payload.scale)) errors.push(error(`${path}.payload.scale`, 'Scale must be finite'))
    if (!Number.isFinite(value.payload.offset)) errors.push(error(`${path}.payload.offset`, 'Offset must be finite'))
    bounded(`${path}.payload.directDiameterFusionConfidence`, value.payload.directDiameterFusionConfidence, 0, 1, errors)
  }
  framingValue(path, 'header', value.applicationFraming.headerEnabled, value.applicationFraming.headerFormat, value.applicationFraming.headerBytes, errors)
  framingValue(path, 'footer', value.applicationFraming.footerEnabled, value.applicationFraming.footerFormat, value.applicationFraming.footerBytes, errors)
  bounded(`${path}.transportLimits.maxInFlightFrames`, value.transportLimits.maxInFlightFrames, 1, 32, errors)
  bounded(`${path}.transportLimits.maxFrameBytes`, value.transportLimits.maxFrameBytes, 1, 32 * 1024 * 1024, errors)
  bounded(`${path}.transportLimits.frameTimeoutMs`, value.transportLimits.frameTimeoutMs, 100, 10000, errors)
  bounded(`${path}.transportLimits.maxChunkCount`, value.transportLimits.maxChunkCount, 1, 8192, errors)
  bounded(`${path}.transportLimits.maxChunkPayloadBytes`, value.transportLimits.maxChunkPayloadBytes, 1, 60000, errors)
}

function framingValue(path: string, part: 'header' | 'footer', enabled: boolean, format: 'HEX' | 'ASCII', bytes: string, errors: FieldError[]) {
  if (!enabled) return
  const field = `${path}.applicationFraming.${part}Bytes`
  const valid = format === 'HEX' ? /^(?:[0-9A-Fa-f]{2})(?:[ ]+[0-9A-Fa-f]{2}){0,63}$/.test(bytes.trim())
    : bytes.length > 0 && bytes.length <= 64 && /^[\x20-\x7E]+$/.test(bytes)
  if (!valid) errors.push(error(field, `Invalid ${format} ${part}`))
}
function ipv4(value: string) { const parts = value.split('.'); return parts.length === 4 && parts.every((part) => /^(0|[1-9]\d{0,2})$/.test(part) && Number(part) <= 255) }
function dimension(path: string, value: number, errors: FieldError[]) { bounded(path, value, 1, 8192, errors) }
function bounded(path: string, value: number, min: number, max: number, errors: FieldError[]) {
  if (!Number.isFinite(value) || value < min || value > max) errors.push(error(path, `Value must be ${min}..${max}`))
}
function error(path: string, message: string): FieldError { return { path, message } }
