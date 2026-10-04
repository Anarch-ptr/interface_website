import { afterEach, describe, expect, it, vi } from 'vitest'
import { BackendApiClient, BackendApiError } from './BackendApiClient'
import { defaultRaspberryPiUdpInputProfile } from '../config/raspberryPiUdpInputProfile'
import type { InspectorSnapshot, NetworkInterfaces, UdpConfigurationState, UdpDiagnostics } from '../domain/raspberryPiUdpInput'

const response = (data: unknown, status = 200, headers?: HeadersInit) =>
  new Response(status === 304 ? null : JSON.stringify({ correlationId: 'corr', data }), { status, headers: { 'Content-Type': 'application/json', ...headers } })

const profile = () => defaultRaspberryPiUdpInputProfile()
const state = (): UdpConfigurationState => ({ savedRevision: 2, appliedRevision: 1, configuration: profile(),
  appliedConfiguration: { ...profile(), network: { ...profile().network, listenPort: 5001 } },
  resolutionGeneration: 0, appliedEffectiveNetworkPlan: null, networkConsistencyStatus: 'HEALTHY',
  reapplyRequired: false, lastApplyError: null, outcome: null, protocolVersion: 1, transportMode: 'STRUCTURED_CHUNKED' })
const interfaces: NetworkInterfaces = {
  interfaces: [{ id: 'wifi', displayName: 'Wi-Fi', ipv4: '192.168.1.3', isUp: true, isLoopback: false, isVirtual: false, supportsMulticast: true }],
}
const diagnostics: UdpDiagnostics = {
  status: 'SOURCE_IP_MISMATCH', statusDetail: null, datagramsReceived: 3, bytesReceived: 90, framesStarted: null, framesCompleted: 0,
  framesDropped: 0, duplicateChunks: null, outOfOrderChunks: null, missingChunks: null, lateChunks: null, reassemblyTimeouts: null,
  invalidTransportHeaders: 0, oversizedFrames: 0, invalidHeaderCount: 1, invalidFooterCount: 2, invalidPayloadSizeCount: 0,
  decodeFailures: 0, sourceFilteredDatagrams: 3, oversizedOrTruncatedDatagrams: 0, lastPacketMonotonicNanos: 123,
  lastValidFrameMonotonicNanos: null, lastPacketSourceIpv4: '192.168.1.4', lastPacketSourcePort: 6000, lastPacketEpochMillis: 456,
  preAdmissionDroppedInputs: 0, processingBackpressureDrops: 0, appliedNetwork: null,
}
const snapshot: InspectorSnapshot = {
  sequence: 7,
  rawDatagram: { sourceIpv4: '192.168.1.2', sourcePort: 6000, backendListenPort: 5000, datagramSize: 40,
    receivedAtMonotonicNanos: 123, status: 'VALID', headerHex: 'AA 01', footerHex: '0D 0A',
    expectedHeaderHex: 'AA 01', expectedFooterHex: '0D 0A', headerMatched: true, footerMatched: true, hexPreview: 'AA 01',
    transport: { protocolVersion: 1, compatibilityStreamId: 1, frameId: '18446744073709551615', chunkIndex: 0, chunkCount: 1,
      sourceTimestampMicros: 456, payloadLength: 2, framePayloadBytes: 2, status: 'VALID', hexPreview: '01 02' }, appliedNetwork: null },
  parsedFrame: { payloadFormat: 'GRAY8', decodeResult: 'VALID', width: 2, height: 1, completedFrameBytes: 2,
    receivedAtMonotonicNanos: 123, hexPreview: '01 02' },
}

describe('BackendApiClient single-stream contracts', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('uses exact configuration CAS bodies and returns saved/applied values unchanged', async () => {
    const fetch = vi.fn().mockImplementation(() => Promise.resolve(response(state())))
    vi.stubGlobal('fetch', fetch)
    const api = new BackendApiClient('/api/v1', () => 'cmd-20')
    const signal = new AbortController().signal
    expect(await api.getRaspberryPiUdpConfiguration('pi/device', signal)).toEqual(state())
    expect(await api.saveRaspberryPiUdpConfiguration('pi/device', profile(), 2, signal)).toEqual(state())
    expect(await api.applyRaspberryPiUdpConfiguration('pi/device', 2, 2, 1, 0, false, signal)).toEqual(state())
    expect(fetch.mock.calls.map(([url]) => url)).toEqual([
      '/api/v1/devices/pi%2Fdevice/udp-input-configuration', '/api/v1/devices/pi%2Fdevice/udp-input-configuration',
      '/api/v1/devices/pi%2Fdevice/udp-input-configuration/apply',
    ])
    expect(fetch.mock.calls[1][1]).toEqual({ method: 'PUT', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ commandId: 'cmd-20', expectedSavedRevision: 2, configuration: profile() }), signal })
    expect(fetch.mock.calls[2][1]).toEqual({ method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ commandId: 'cmd-20', revision: 2, expectedSavedRevision: 2,
        expectedAppliedRevision: 1, expectedResolutionGeneration: 0, forceReapply: false }), signal })
    expect(fetch.mock.calls[0][1].signal).toBe(signal)
  })

  it('enumerates backend interfaces and reads one diagnostics endpoint without field renaming', async () => {
    const fetch = vi.fn().mockResolvedValueOnce(response(interfaces)).mockResolvedValueOnce(response(diagnostics))
    vi.stubGlobal('fetch', fetch)
    const api = new BackendApiClient()
    const signal = new AbortController().signal
    expect(await api.getNetworkInterfaces(signal)).toEqual(interfaces)
    expect(await api.getRaspberryPiUdpDiagnostics('pi', signal)).toEqual(diagnostics)
    expect(fetch.mock.calls).toEqual([['/api/v1/network-interfaces', { signal }], ['/api/v1/devices/pi/udp-diagnostics', { signal }]])
  })

  it('previews without mutation and sends same-revision forced reapply CAS identity', async () => {
    const preview = { configuredDestination: null, configuredSource: null, candidatePlan: null,
      destinationStatus: 'NOT_REQUIRED', sourceStatus: 'NOT_REQUIRED', networkConsistencyStatus: 'HEALTHY',
      reapplyRequired: false, differences: [], warnings: [], errorCode: null, previewedAtEpochMillis: 1,
      appliedResolutionGeneration: 4, superseded: false } as const
    const fetch = vi.fn().mockResolvedValueOnce(response(preview)).mockResolvedValueOnce(response(state()))
    vi.stubGlobal('fetch', fetch)
    const api = new BackendApiClient('/api/v1', () => 'cmd-reapply')
    const signal = new AbortController().signal
    expect(await api.previewRaspberryPiUdpNetwork('pi', profile().network, signal)).toEqual(preview)
    await api.applyRaspberryPiUdpConfiguration('pi', 12, 12, 12, 4, true, signal)
    expect(JSON.parse(fetch.mock.calls[0][1].body)).toEqual({ network: profile().network })
    expect(JSON.parse(fetch.mock.calls[1][1].body)).toEqual({ commandId: 'cmd-reapply', revision: 12,
      expectedSavedRevision: 12, expectedAppliedRevision: 12, expectedResolutionGeneration: 4, forceReapply: true })
  })

  it('preserves explicit no-interface state and nullable diagnostic metadata', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response({ interfaces: [] })))
    expect(await new BackendApiClient().getNetworkInterfaces()).toEqual({ interfaces: [] })
  })

  it('creates polls and releases one lease with encoded ids and full unsigned frame metadata', async () => {
    const lease = { leaseId: 'lease/id', previewBytes: 128, expiresAtMillis: 9000 }
    const fetch = vi.fn().mockResolvedValueOnce(response(lease, 201)).mockResolvedValueOnce(response(snapshot, 200, { ETag: '"7"' }))
      .mockResolvedValueOnce(response(null, 304, { ETag: '"7"' })).mockResolvedValueOnce(response({ deleted: true }))
    vi.stubGlobal('fetch', fetch)
    const signal = new AbortController().signal
    const api = new BackendApiClient()
    expect(await api.createRaspberryPiInspectorLease('pi', 128, signal)).toEqual(lease)
    expect(await api.getRaspberryPiInspector('pi', lease.leaseId, 128, undefined, signal)).toEqual({ unchanged: false, etag: '"7"', snapshot })
    expect(await api.getRaspberryPiInspector('pi', lease.leaseId, 128, '"7"', signal)).toEqual({ unchanged: true, etag: '"7"' })
    expect(await api.releaseRaspberryPiInspectorLease('pi', lease.leaseId, signal)).toEqual({ deleted: true })
    expect(fetch.mock.calls.map(([url]) => url)).toEqual([
      '/api/v1/devices/pi/packet-inspector/lease', '/api/v1/devices/pi/packet-inspector?leaseId=lease%2Fid&previewBytes=128',
      '/api/v1/devices/pi/packet-inspector?leaseId=lease%2Fid&previewBytes=128', '/api/v1/devices/pi/packet-inspector/lease/lease%2Fid',
    ])
    expect(fetch.mock.calls[0][1].body).toBe('{"previewBytes":128}')
    expect(fetch.mock.calls[2][1]).toEqual({ headers: { 'If-None-Match': '"7"' }, signal })
    expect(fetch.mock.calls[3][1]).toEqual({ method: 'DELETE', headers: { 'Content-Type': 'application/json' }, body: undefined, signal })
    expect(fetch.mock.calls.every(([, options]) => options.signal === signal)).toBe(true)
  })

  it('preserves null raw boundaries and absent transport/frame after a source mismatch', async () => {
    const rejected: InspectorSnapshot = { sequence: 8, rawDatagram: { ...snapshot.rawDatagram, status: 'SOURCE_IP_MISMATCH',
      headerHex: null, footerHex: null, headerMatched: null, footerMatched: null, transport: null }, parsedFrame: null }
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response(rejected)))
    expect(await new BackendApiClient().getRaspberryPiInspector('pi', 'lease', 64)).toEqual({ unchanged: false, etag: null, snapshot: rejected })
  })

  it.each([400, 409, 404, 422, 500])('preserves authoritative backend errors with status %s', async (status) => {
    const failure = { code: 'CONFLICT', message: 'Saved revision changed', correlationId: 'corr-20',
      details: { fieldErrors: [{ path: 'payload.packing', message: 'Unsupported value' }, { path: 'envelope.headerHex', message: 'Backend says invalid' }] } }
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify(failure), { status })))
    const error = await new BackendApiClient().saveRaspberryPiUdpConfiguration('pi', profile(), 2).catch((value: unknown) => value)
    expect(error).toBeInstanceOf(BackendApiError)
    expect(error).toMatchObject({ status, code: failure.code, message: failure.message, correlationId: failure.correlationId,
      fieldErrors: failure.details.fieldErrors })
  })

  it.each(['<html>private platform details</html>', '{}', '{"data":'])('reports a safe error for malformed responses %j', async (body) => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(body, { status: 502 })))
    await expect(new BackendApiClient().getNetworkInterfaces()).rejects.toMatchObject({ status: 502, code: 'INVALID_RESPONSE', message: 'Invalid Backend response.', correlationId: '', fieldErrors: [] })
  })

  it('rejects a successful response without data safely', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('{}')))
    await expect(new BackendApiClient().getNetworkInterfaces()).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
  })

  it('keeps cancellation intact and hides network exception details', async () => {
    const aborted = new DOMException('Canceled', 'AbortError')
    vi.stubGlobal('fetch', vi.fn().mockRejectedValueOnce(aborted).mockRejectedValueOnce(new TypeError('private network details')))
    const api = new BackendApiClient()
    await expect(api.getNetworkInterfaces(new AbortController().signal)).rejects.toBe(aborted)
    await expect(api.getRaspberryPiUdpDiagnostics('pi')).rejects.toMatchObject({ status: 0, code: 'NETWORK_ERROR', message: 'Unable to reach Backend.' })
  })

  it('preserves cancellation while reading the response body', async () => {
    const aborted = new DOMException('Canceled', 'AbortError')
    const value = response(interfaces)
    vi.spyOn(value, 'json').mockRejectedValue(aborted)
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(value))
    await expect(new BackendApiClient().getNetworkInterfaces()).rejects.toBe(aborted)
  })

  const bodyCancellationCases: [string, (api: BackendApiClient, signal: AbortSignal) => Promise<unknown>][] = [
    ['configuration GET', (api, signal) => api.getRaspberryPiUdpConfiguration('pi', signal)],
    ['configuration Save', (api, signal) => api.saveRaspberryPiUdpConfiguration('pi', profile(), 1, signal)],
    ['configuration Apply', (api, signal) => api.applyRaspberryPiUdpConfiguration('pi', 1, 1, 1, 0, false, signal)],
    ['network interfaces', (api, signal) => api.getNetworkInterfaces(signal)],
    ['network preview', (api, signal) => api.previewRaspberryPiUdpNetwork('pi', profile().network, signal)],
    ['diagnostics', (api, signal) => api.getRaspberryPiUdpDiagnostics('pi', signal)],
    ['Inspector create', (api, signal) => api.createRaspberryPiInspectorLease('pi', 128, signal)],
    ['Inspector release', (api, signal) => api.releaseRaspberryPiInspectorLease('pi', 'lease', signal)],
    ['Inspector poll', (api, signal) => api.getRaspberryPiInspector('pi', 'lease', 128, undefined, signal)],
  ]
  it.each(bodyCancellationCases)('preserves a custom signal reason during %s body reading', async (_name, call) => {
    const controller = new AbortController()
    const reason = new Error('custom cancellation reason')
    const value = response(interfaces)
    vi.spyOn(value, 'json').mockImplementation(async () => {
      controller.abort(reason)
      throw reason
    })
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(value))
    await expect(call(new BackendApiClient('/api/v1', () => 'cmd'), controller.signal)).rejects.toBe(reason)
  })

  it('sends the original draft and preserves backend rejection instead of treating feedback as authority', async () => {
    const draft = profile()
    draft.envelope.headerHex = 'aa01'
    draft.listenPort = 0
    draft.payload = { format: 'BINARY', width: 3, height: 3, packing: 'BIT_PACKED' }
    const fetch = vi.fn().mockResolvedValue(new Response(JSON.stringify({ code: 'INVALID_REQUEST',
      message: 'Invalid request', correlationId: 'corr', details: { fieldErrors: [{ path: 'listenPort', message: 'Listen port must be between 1 and 65535' }] } }), { status: 400 }))
    vi.stubGlobal('fetch', fetch)
    await expect(new BackendApiClient('/api/v1', () => 'cmd').saveRaspberryPiUdpConfiguration('pi', draft, 1)).rejects.toMatchObject({
      fieldErrors: [{ path: 'listenPort', message: 'Listen port must be between 1 and 65535' }],
    })
    expect(JSON.parse(fetch.mock.calls[0][1].body)).toEqual({ commandId: 'cmd', expectedSavedRevision: 1, configuration: draft })
  })
})
