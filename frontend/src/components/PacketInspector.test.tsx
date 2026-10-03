import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { BackendApiClient } from '../services/BackendApiClient'
import { apiResponse, inspectorSnapshot } from '../test/raspberryPiUdpFixtures'
import { PacketInspector } from './PacketInspector'

function fixture() {
  let sequence = 0; let leases = 0
  const fetch = vi.fn().mockImplementation(async (_url: string, options?: RequestInit) => {
    if (options?.method === 'DELETE') return apiResponse({ deleted: true })
    if (options?.method === 'POST') return apiResponse({ leaseId: `lease-${++leases}`, previewBytes: JSON.parse(String(options.body)).previewBytes, expiresAtMillis: 9999 })
    return apiResponse(inspectorSnapshot(++sequence), 200, `"${sequence}"`)
  })
  vi.stubGlobal('fetch', fetch)
  return { fetch, api: new BackendApiClient() }
}
afterEach(() => { cleanup(); vi.unstubAllGlobals() })
describe('single Packet Inspector', () => {
  it('owns one selector-free lease, freezes display and aborts/releases on close', async () => {
    const { api, fetch } = fixture(); const user = userEvent.setup(); render(<PacketInspector api={api} deviceId="pi" />)
    expect(fetch).not.toHaveBeenCalled(); await user.click(screen.getByRole('button', { name: 'Inspect Packet' })); expect(await screen.findByText('AB 01')).toBeVisible()
    expect(screen.getByRole('region', { name: 'Packet Inspector' })).toBeVisible(); expect(screen.queryByLabelText(/Camera [AB]/)).not.toBeInTheDocument()
    for (const name of ['Raw Datagram', 'Structured Transport', 'Parsed Frame']) expect(screen.getByRole('heading', { name })).toBeVisible()
    expect(screen.getByText(/Actual header: AB 01 · Expected header: AA 01 · Mismatch/)).toBeVisible()
    await user.click(screen.getByRole('button', { name: 'Freeze' })); await waitFor(() => expect(fetch.mock.calls.filter(([url]) => url.includes('?leaseId=')).length).toBeGreaterThanOrEqual(2), { timeout: 1200 }); expect(screen.getByText('AB 01')).toBeVisible()
    await user.click(screen.getByRole('button', { name: 'Resume' })); expect(await screen.findByText('AB 02')).toBeVisible()
    await user.click(screen.getByRole('button', { name: 'Close Inspector' })); await waitFor(() => expect(fetch.mock.calls.some(([url, options]) => url.endsWith('/lease/lease-1') && options?.method === 'DELETE')).toBe(true))
    const poll = fetch.mock.calls.find(([url]) => url.includes('?leaseId='))!; expect(poll[1].signal.aborted).toBe(true)
    const calls = fetch.mock.calls.length; await new Promise((resolve) => setTimeout(resolve, 600)); expect(fetch).toHaveBeenCalledTimes(calls)
  })
  it('retains displayed data for 304, changes bounded size and releases on unmount', async () => {
    const { api, fetch } = fixture(); const user = userEvent.setup(); const view = render(<PacketInspector api={api} deviceId="pi" />)
    await user.click(screen.getByRole('button', { name: 'Inspect Packet' })); await screen.findByText('AB 01'); fetch.mockResolvedValueOnce(apiResponse(null, 304, '"1"'))
    await waitFor(() => expect(fetch.mock.calls.filter(([url]) => url.includes('?leaseId=')).length).toBe(2), { timeout: 1200 }); expect(screen.getByText('AB 01')).toBeVisible(); expect(fetch.mock.calls[2][1].headers).toEqual({ 'If-None-Match': '"1"' })
    await user.selectOptions(screen.getByLabelText('Inspector preview bytes'), '256'); await waitFor(() => expect(fetch.mock.calls.some(([, options]) => options?.body === '{"previewBytes":256}')).toBe(true))
    view.unmount(); await waitFor(() => expect(fetch.mock.calls.filter(([, options]) => options?.method === 'DELETE')).toHaveLength(2))
  })
  it('releases a late-created lease after unmount without starting a poll', async () => {
    const { api, fetch } = fixture(); const user = userEvent.setup(); let resolve!: (value: Response) => void
    fetch.mockImplementationOnce(() => new Promise<Response>((done) => { resolve = done }))
    const view = render(<PacketInspector api={api} deviceId="pi" />); await user.click(screen.getByRole('button', { name: 'Inspect Packet' }))
    view.unmount(); resolve(apiResponse({ leaseId: 'late', previewBytes: 128, expiresAtMillis: 9999 }))
    await waitFor(() => expect(fetch.mock.calls.some(([url, options]) => url.endsWith('/lease/late') && options?.method === 'DELETE')).toBe(true)); expect(fetch.mock.calls.some(([url]) => url.includes('?leaseId='))).toBe(false)
  })
  it('recovers expired leases and copies displayed bounded data', async () => {
    const { api, fetch } = fixture(); const user = userEvent.setup(); const writeText = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText } }); render(<PacketInspector api={api} deviceId="pi" />)
    fetch.mockResolvedValueOnce(apiResponse({ leaseId: 'expired', previewBytes: 128, expiresAtMillis: 9 })).mockResolvedValueOnce(new Response(JSON.stringify({ code: 'NOT_FOUND', message: 'expired', correlationId: 'c' }), { status: 404 }))
    await user.click(screen.getByRole('button', { name: 'Inspect Packet' })); await screen.findByText('AB 01'); await user.click(screen.getByRole('button', { name: 'Copy Hex' })); expect(writeText).toHaveBeenCalledWith('AB 01')
    await user.click(screen.getByRole('button', { name: 'Copy Debug Info' })); expect(writeText.mock.calls[1][0]).toContain('HEADER_MISMATCH')
  })
  it('shows complete structured and parsed image metadata without rounding a uint64 frame id', async () => {
    const { api, fetch } = fixture(); const user = userEvent.setup()
    const snapshot = inspectorSnapshot(); snapshot.rawDatagram.status = 'VALID'; snapshot.rawDatagram.headerHex = 'AA 01'; snapshot.rawDatagram.headerMatched = true
    snapshot.rawDatagram.transport = { protocolVersion: 1, compatibilityStreamId: 1, frameId: '18446744073709551615', chunkIndex: 0, chunkCount: 1, sourceTimestampMicros: 456, payloadLength: 2, framePayloadBytes: 2, status: 'VALID', hexPreview: '01 02' }
    snapshot.parsedFrame = { payloadFormat: 'GRAY8', decodeResult: 'VALID', width: 2, height: 1, completedFrameBytes: 2, receivedAtMonotonicNanos: 123, hexPreview: '10 20' }
    fetch.mockResolvedValueOnce(apiResponse({ leaseId: 'valid', previewBytes: 128, expiresAtMillis: 9999 })).mockResolvedValueOnce(apiResponse(snapshot, 200, '"1"'))
    render(<PacketInspector api={api} deviceId="pi" />); await user.click(screen.getByRole('button', { name: 'Inspect Packet' }))
    expect(await screen.findByText('18446744073709551615')).toBeVisible(); expect(screen.getByText(/GRAY8 · 2 × 1 · Completed frame bytes: 2/)).toBeVisible(); expect(screen.getByText(/Actual footer: 0D 0A · Expected footer: 0D 0A · Matched/)).toBeVisible()
    expect(fetch.mock.calls[0][0]).toBe('/api/v1/devices/pi/packet-inspector/lease')
  })
  it('never overlaps a slow poll and aborts it when closed', async () => {
    const { api, fetch } = fixture(); const user = userEvent.setup()
    fetch.mockResolvedValueOnce(apiResponse({ leaseId: 'slow', previewBytes: 128, expiresAtMillis: 9999 })).mockImplementationOnce(() => new Promise(() => undefined))
    render(<PacketInspector api={api} deviceId="pi" />); await user.click(screen.getByRole('button', { name: 'Inspect Packet' }))
    await new Promise((resolve) => setTimeout(resolve, 650)); expect(fetch.mock.calls.filter(([url]) => url.includes('?leaseId='))).toHaveLength(1)
    await user.click(screen.getByRole('button', { name: 'Close Inspector' })); expect(fetch.mock.calls[1][1].signal.aborted).toBe(true)
  })
  it('stays closed after lease-start failure and handles release failure safely', async () => {
    const { api, fetch } = fixture(); const user = userEvent.setup(); fetch.mockRejectedValueOnce(new Error('offline'))
    render(<PacketInspector api={api} deviceId="pi" />); await user.click(screen.getByRole('button', { name: 'Inspect Packet' })); expect(await screen.findByText(/Unable to start Inspector/)).toBeVisible()
    await user.click(screen.getByRole('button', { name: 'Close Inspector' })); await user.click(screen.getByRole('button', { name: 'Inspect Packet' })); await screen.findByText('AB 01')
    fetch.mockRejectedValueOnce(new Error('release offline')); await user.click(screen.getByRole('button', { name: 'Close Inspector' })); expect(screen.getByRole('button', { name: 'Inspect Packet' })).toBeVisible()
  })
})
