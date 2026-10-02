import { afterEach, describe, expect, it, vi } from 'vitest'
import { defaultUdpProfiles } from '../config/udpInputProfile'
import { BackendApiClient, BackendApiError } from './BackendApiClient'

const response = (data: unknown, status = 200, headers?: HeadersInit) =>
  new Response(status === 304 ? null : JSON.stringify({ correlationId: 'corr', data }), { status, headers: { 'Content-Type': 'application/json', ...headers } })

describe('BackendApiClient', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('calls GET Save Apply diagnostics and lease routes with command ids', async () => {
    const fetch = vi.fn()
      .mockResolvedValueOnce(response({ savedRevision: 1, appliedRevision: 1, configuration: defaultUdpProfiles() }))
      .mockResolvedValueOnce(response({ savedRevision: 2, appliedRevision: 1, configuration: defaultUdpProfiles() }))
      .mockResolvedValueOnce(response({ savedRevision: 2, appliedRevision: 2, configuration: defaultUdpProfiles(), outcome: 'APPLIED' }))
      .mockResolvedValueOnce(response({ cameraId: 'A', transportMode: 'STRUCTURED_CHUNKED' }))
      .mockResolvedValueOnce(response({ leaseId: 'lease', cameraId: 'A', previewBytes: 128, expiresAtMillis: 9 }, 201))
      .mockResolvedValueOnce(response({ deleted: true }))
    vi.stubGlobal('fetch', fetch)
    const api = new BackendApiClient('/api/v1', () => 'cmd')
    await api.getUdpConfiguration('device1')
    await api.saveUdpConfiguration('device1', defaultUdpProfiles())
    await api.applyUdpConfiguration('device1', 2, 2)
    await api.getUdpDiagnostics('device1', 'A')
    await api.createInspectorLease('device1', 'A', 128)
    await api.releaseInspectorLease('device1', 'A', 'lease')
    expect(fetch).toHaveBeenCalledTimes(6)
    expect(fetch.mock.calls[1][1].body).toContain('"commandId":"cmd"')
    expect(fetch.mock.calls[2][1].body).toContain('"expectedSavedRevision":2')
  })

  it('preserves backend validation paths and CAS errors', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ code: 'INVALID_REQUEST', message: 'Invalid request',
      correlationId: 'c', details: { fieldErrors: [{ path: 'cameraA.listenPort', message: 'bad' }] } }), { status: 400 })))
    const error = await new BackendApiClient().saveUdpConfiguration('device1', defaultUdpProfiles()).catch((value) => value)
    expect(error).toBeInstanceOf(BackendApiError)
    expect(error.fieldErrors[0].path).toBe('cameraA.listenPort')
  })

  it('supports ETag 304 and forwards abort signals', async () => {
    const fetch = vi.fn().mockResolvedValue(response(null, 304, { ETag: '"7"' }))
    vi.stubGlobal('fetch', fetch)
    const controller = new AbortController()
    const result = await new BackendApiClient().getInspector('device1', 'A', 'lease', 128, '"7"', controller.signal)
    expect(result).toEqual({ unchanged: true, etag: '"7"' })
    expect(fetch.mock.calls[0][1].signal).toBe(controller.signal)
  })
})
