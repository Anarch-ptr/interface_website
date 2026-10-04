import { describe, expect, it } from 'vitest'
import { createPayloadDraft, defaultRaspberryPiUdpInputProfile, normalizeTwoByteHex, validateRaspberryPiUdpInputProfile } from './raspberryPiUdpInputProfile'
import type { ImagePayload, RaspberryPiUdpInputProfile } from '../domain/raspberryPiUdpInput'

describe('Raspberry Pi expected-input profile', () => {
  it('starts disabled with the backend JPEG defaults and only one input', () => {
    expect(defaultRaspberryPiUdpInputProfile()).toEqual({
      enabled: false, network: { bind: { mode: 'AUTO' }, destination: { mode: 'AUTO' }, sourceFilter: { mode: 'ANY' }, listenPort: 5000 },
      payload: { format: 'JPEG', maxFrameBytes: 16777216 }, envelope: { headerHex: 'AA 01', footerHex: '0D 0A' },
      transportLimits: { maxInFlightFrames: 2, maxFrameBytes: 33554432, frameTimeoutMs: 1000, maxChunkCount: 32768, maxChunkPayloadBytes: 1400 },
    })
    expect(validateRaspberryPiUdpInputProfile(defaultRaspberryPiUdpInputProfile())).toEqual([])
    const second = defaultRaspberryPiUdpInputProfile()
    second.envelope.headerHex = '12 34'
    expect(defaultRaspberryPiUdpInputProfile().envelope.headerHex).toBe('AA 01')
  })

  it('builds only the selected format fields with the backend packing name', () => {
    expect(createPayloadDraft('JPEG')).toEqual({ format: 'JPEG', maxFrameBytes: 16777216 })
    expect(createPayloadDraft('GRAY8')).toEqual({ format: 'GRAY8', width: 640, height: 480 })
    expect(createPayloadDraft('BINARY')).toEqual({ format: 'BINARY', width: 640, height: 480, packing: 'BYTE_PER_PIXEL' })
    expect(createPayloadDraft('RGB888')).toEqual({ format: 'RGB888', width: 640, height: 480, channelOrder: 'RGB' })
  })

  it.each(['AA01', 'aa 01', ' A A\t0 1\n'])('normalizes exactly two HEX bytes: %j', (value) => {
    expect(normalizeTwoByteHex(value)).toBe('AA 01')
  })

  it.each(['', 'AA', 'AA 01 02', 'GG 01', 'AA0', '0xAA01'])('rejects non-two-byte HEX: %j', (value) => {
    expect(normalizeTwoByteHex(value)).toBeNull()
    const profile = defaultRaspberryPiUdpInputProfile()
    profile.envelope = { headerHex: value, footerHex: value }
    expect(validateRaspberryPiUdpInputProfile(profile).map((error) => error.path)).toEqual(['envelope.headerHex', 'envelope.footerHex'])
  })

  it.each(['::1', 'bad host', '192.168.001.2', '256.0.0.1', '1.2.3.4 ', '1.2.3'])('rejects malformed network values: %j', (ipv4) => {
    const profile = defaultRaspberryPiUdpInputProfile()
    profile.network.sourceFilter = { mode: 'IP_OR_HOST', hostOrIpv4: ipv4 }
    profile.network.bind = { mode: 'SPECIFIC_INTERFACE', interfaceId: '', ipv4 }
    expect(validateRaspberryPiUdpInputProfile(profile).map((error) => error.path)).toEqual([
      'network.bind.interfaceId', 'network.bind.ipv4', 'network.sourceFilter.hostOrIpv4',
    ])
  })

  it('accepts canonical IPv4 and unsigned wire-capacity transport bounds', () => {
    const profile = defaultRaspberryPiUdpInputProfile()
    profile.network.sourceFilter = { mode: 'IP_OR_HOST', hostOrIpv4: 'filament-pi.local' }
    profile.network.destination = { mode: 'MANUAL', hostOrIpv4: 'backend.local' }
    profile.network.bind = { mode: 'SPECIFIC_INTERFACE', interfaceId: 'wifi', ipv4: '192.168.1.3' }
    profile.network.listenPort = 65535
    profile.transportLimits = { maxInFlightFrames: 32, maxFrameBytes: 33554432, frameTimeoutMs: 10000, maxChunkCount: 65535, maxChunkPayloadBytes: 60000 }
    expect(validateRaspberryPiUdpInputProfile(profile)).toEqual([])
  })

  it.each([0, 65536, 1.5, NaN, Infinity])('rejects invalid ports %s', (listenPort) => {
    const profile = defaultRaspberryPiUdpInputProfile(); profile.network.listenPort = listenPort
    expect(validateRaspberryPiUdpInputProfile(profile).map((error) => error.path)).toEqual(['network.listenPort'])
  })

  it('rejects fractional, nonfinite and out-of-range transport fields', () => {
    const profile = defaultRaspberryPiUdpInputProfile()
    profile.transportLimits = { maxInFlightFrames: 1.5, maxFrameBytes: Infinity, frameTimeoutMs: 99, maxChunkCount: 65536, maxChunkPayloadBytes: 0 }
    expect(validateRaspberryPiUdpInputProfile(profile).map((error) => error.path)).toEqual([
      'transportLimits.maxInFlightFrames', 'transportLimits.maxFrameBytes', 'transportLimits.frameTimeoutMs',
      'transportLimits.maxChunkCount', 'transportLimits.maxChunkPayloadBytes',
    ])
  })

  it.each<ImagePayload>([
    { format: 'GRAY8', width: 1.5, height: 0 },
    { format: 'BINARY', width: 8193, height: 2, packing: 'BIT_PACKED' },
    { format: 'RGB888', width: Infinity, height: 2, channelOrder: 'BGR' },
    { format: 'JPEG', maxFrameBytes: 0, expectedWidth: NaN, expectedHeight: 8193 },
  ])('validates the selected image fields: %j', (payload) => {
    expect(validateRaspberryPiUdpInputProfile({ ...defaultRaspberryPiUdpInputProfile(), payload }).some((error) => error.path.startsWith('payload.'))).toBe(true)
  })

  it('checks decoded pixels and raw wire bytes including bit-packed ceiling', () => {
    const profile = defaultRaspberryPiUdpInputProfile()
    profile.payload = { format: 'JPEG', maxFrameBytes: 16, expectedWidth: 8192, expectedHeight: 8192 }
    expect(validateRaspberryPiUdpInputProfile(profile).map((error) => error.path)).toEqual(['payload.decodedPixels'])
    profile.payload = { format: 'BINARY', width: 3, height: 3, packing: 'BIT_PACKED' }
    profile.transportLimits.maxFrameBytes = 1
    expect(validateRaspberryPiUdpInputProfile(profile).map((error) => error.path)).toEqual(['payload.expectedPayloadBytes'])
    profile.transportLimits.maxFrameBytes = 2
    expect(validateRaspberryPiUdpInputProfile(profile)).toEqual([])
    profile.payload = { format: 'RGB888', width: 1, height: 1, channelOrder: 'BGR' }
    expect(validateRaspberryPiUdpInputProfile(profile).map((error) => error.path)).toEqual(['payload.expectedPayloadBytes'])
    profile.payload = { format: 'JPEG', maxFrameBytes: 3, expectedWidth: null }
    expect(validateRaspberryPiUdpInputProfile(profile).map((error) => error.path)).toEqual(['payload.maxFrameBytes'])
  })
})

// Compile-time contract checks: these obsolete controls cannot enter a typed draft.
function contractChecks(profile: RaspberryPiUdpInputProfile) {
  // @ts-expect-error no second input
  void profile.cameraB
  // @ts-expect-error fixed transport is metadata, never a profile selector
  void profile.transportMode
  // @ts-expect-error no arbitrary framing
  void profile.applicationFraming
  // @ts-expect-error only four image payloads
  const diameter: ImagePayload = { format: 'DIAMETER' }
  return diameter
}
void contractChecks
