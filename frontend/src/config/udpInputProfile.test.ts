import { describe, expect, it } from 'vitest'
import { defaultUdpProfiles, recommendedTransport, validateUdpProfiles } from './udpInputProfile'

describe('UDP input profile validation', () => {
  it('validates conditional fields, IPv4, distinct ports, framing and bounds', () => {
    const value = defaultUdpProfiles()
    value.cameraA.sourceIp = '::1'
    value.cameraA.listenPort = value.cameraB.listenPort
    value.cameraA.payload = { format: 'BINARY', width: 0, height: 10, binaryPacking: 'BIT_PACKED' }
    value.cameraA.applicationFraming = { ...value.cameraA.applicationFraming, headerEnabled: true, headerFormat: 'HEX', headerBytes: 'AA GG' }
    const paths = validateUdpProfiles(value).map((error) => error.path)
    expect(paths).toContain('cameraA.sourceIp')
    expect(paths).toContain('cameraA.listenPort')
    expect(paths).toContain('cameraB.listenPort')
    expect(paths).toContain('cameraA.payload.width')
    expect(paths).toContain('cameraA.applicationFraming.headerBytes')
  })

  it('recommends but never forces a transport mode', () => {
    expect(recommendedTransport('DIAMETER')).toBe('SINGLE_DATAGRAM')
    expect(recommendedTransport('GRAY8')).toBe('STRUCTURED_CHUNKED')
    const value = defaultUdpProfiles()
    value.cameraA.transportMode = 'SINGLE_DATAGRAM'
    expect(validateUdpProfiles(value)).toEqual([])
  })
})
