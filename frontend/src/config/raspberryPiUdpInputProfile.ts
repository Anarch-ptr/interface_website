import type { FieldError, ImagePayload, PayloadFormat, RaspberryPiUdpInputProfile } from '../domain/raspberryPiUdpInput'

export function createPayloadDraft(format: PayloadFormat): ImagePayload {
  switch (format) {
    case 'JPEG': return { format, maxFrameBytes: 16 * 1024 * 1024 }
    case 'GRAY8': return { format, width: 640, height: 480 }
    case 'BINARY': return { format, width: 640, height: 480, packing: 'BYTE_PER_PIXEL' }
    case 'RGB888': return { format, width: 640, height: 480, channelOrder: 'RGB' }
  }
}

export function defaultRaspberryPiUdpInputProfile(): RaspberryPiUdpInputProfile {
  return { enabled: false, network: { bind: { mode: 'AUTO' }, destination: { mode: 'AUTO' }, sourceFilter: { mode: 'ANY' }, listenPort: 5000 },
    payload: createPayloadDraft('JPEG'), envelope: { headerHex: 'AA 01', footerHex: '0D 0A' },
    transportLimits: { maxInFlightFrames: 2, maxFrameBytes: 32 * 1024 * 1024, frameTimeoutMs: 1000,
      maxChunkCount: 32768, maxChunkPayloadBytes: 1400 } }
}

export function normalizeTwoByteHex(value: string): string | null {
  const compact = value.replace(/\s/g, '')
  if (!/^[0-9a-fA-F]{4}$/.test(compact)) return null
  return `${compact.slice(0, 2)} ${compact.slice(2)}`.toUpperCase()
}

/** Immediate draft feedback only. Backend remains authoritative and the client sends the original draft. */
export function validateRaspberryPiUdpInputProfile(value: RaspberryPiUdpInputProfile): FieldError[] {
  const errors: FieldError[] = []
  const bounded = (path: string, number: number, min: number, max: number) => {
    if (!Number.isInteger(number) || number < min || number > max) errors.push({ path, message: `Value must be an integer between ${min} and ${max}` })
  }
  const ipv4 = (path: string, address: string) => {
    const parts = address.split('.')
    if (parts.length !== 4 || !parts.every((part) => /^(0|[1-9]\d{0,2})$/.test(part) && Number(part) <= 255)) {
      errors.push({ path, message: 'Accepts only canonical IPv4 literals' })
    }
  }
  const hostOrIpv4 = (path: string, input: string) => {
    const candidate = input.trim().toLowerCase()
    const parts = candidate.split('.')
    const canonicalIpv4 = parts.length === 4 && parts.every((part) =>
      /^(0|[1-9]\d{0,2})$/.test(part) && Number(part) <= 255)
    const malformedNumeric = /^[0-9.]+$/.test(candidate) && !canonicalIpv4
    if (input !== input.trim() || malformedNumeric || !candidate || candidate.length > 253 || candidate.includes(':') ||
      (!/^([a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)(\.([a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?))*$/.test(candidate) &&
        !/^(0|[1-9]\d{0,2})(\.(0|[1-9]\d{0,2})){3}$/.test(candidate))) {
      errors.push({ path, message: 'Enter a valid hostname or canonical IPv4 address' })
    }
  }
  bounded('network.listenPort', value.network.listenPort, 1, 65535)
  if (value.network.bind.mode === 'SPECIFIC_INTERFACE') {
    if (!value.network.bind.interfaceId.trim()) errors.push({ path: 'network.bind.interfaceId', message: 'Select a network interface' })
    ipv4('network.bind.ipv4', value.network.bind.ipv4)
  } else if (value.network.bind.mode === 'MANUAL_IP') ipv4('network.bind.ipv4', value.network.bind.ipv4)
  if (value.network.destination.mode === 'MANUAL') hostOrIpv4('network.destination.hostOrIpv4', value.network.destination.hostOrIpv4)
  if (value.network.sourceFilter.mode === 'IP_OR_HOST') hostOrIpv4('network.sourceFilter.hostOrIpv4', value.network.sourceFilter.hostOrIpv4)
  const limits = value.transportLimits
  bounded('transportLimits.maxInFlightFrames', limits.maxInFlightFrames, 1, 32)
  bounded('transportLimits.maxFrameBytes', limits.maxFrameBytes, 1, 32 * 1024 * 1024)
  bounded('transportLimits.frameTimeoutMs', limits.frameTimeoutMs, 100, 10000)
  bounded('transportLimits.maxChunkCount', limits.maxChunkCount, 1, 65535)
  bounded('transportLimits.maxChunkPayloadBytes', limits.maxChunkPayloadBytes, 1, 60000)
  const payload = value.payload
  if (payload.format === 'JPEG') {
    bounded('payload.maxFrameBytes', payload.maxFrameBytes, 1, Math.min(32 * 1024 * 1024, limits.maxFrameBytes))
  }
  const width = payload.format === 'JPEG' ? payload.expectedWidth : payload.width
  const height = payload.format === 'JPEG' ? payload.expectedHeight : payload.height
  if (width != null) bounded(payload.format === 'JPEG' ? 'payload.expectedWidth' : 'payload.width', width, 1, 8192)
  if (height != null) bounded(payload.format === 'JPEG' ? 'payload.expectedHeight' : 'payload.height', height, 1, 8192)
  if (width != null && height != null && Number.isInteger(width) && Number.isInteger(height) && width >= 1 && width <= 8192 && height >= 1 && height <= 8192) {
    const pixels = width * height
    if (pixels > 16777216) errors.push({ path: 'payload.decodedPixels', message: 'Decoded image exceeds the pixel or decoded byte hard limit' })
    else if (payload.format !== 'JPEG') {
      const bytes = payload.format === 'RGB888' ? pixels * 3
        : payload.format === 'BINARY' && payload.packing === 'BIT_PACKED' ? Math.ceil(pixels / 8) : pixels
      if (bytes > limits.maxFrameBytes) errors.push({ path: 'payload.expectedPayloadBytes', message: 'Payload exceeds configured maximum frame bytes' })
    }
  }
  for (const part of ['headerHex', 'footerHex'] as const) {
    if (normalizeTwoByteHex(value.envelope[part]) === null) errors.push({ path: `envelope.${part}`, message: 'Envelope value must contain exactly two HEX bytes' })
  }
  return errors
}
