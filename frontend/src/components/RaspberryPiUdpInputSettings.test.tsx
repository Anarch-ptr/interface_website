import { act, cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { BackendApiClient } from '../services/BackendApiClient'
import { apiResponse, networkInterfaces, udpDiagnostics, udpState } from '../test/raspberryPiUdpFixtures'
import { RaspberryPiUdpInputSettings } from './RaspberryPiUdpInputSettings'

function fixture() {
  let state = udpState()
  const fetch = vi.fn().mockImplementation(async (url: string, options?: RequestInit) => {
    if (url.endsWith('/network-interfaces')) return apiResponse(networkInterfaces)
    if (url.endsWith('/udp-diagnostics')) return apiResponse(udpDiagnostics)
    if (options?.method === 'PUT') state = { ...state, savedRevision: state.savedRevision + 1, configuration: JSON.parse(String(options.body)).configuration }
    if (url.endsWith('/apply')) state = { ...state, appliedRevision: state.savedRevision, appliedConfiguration: state.configuration, outcome: 'APPLIED' }
    return apiResponse(state)
  })
  vi.stubGlobal('fetch', fetch)
  return { fetch, api: new BackendApiClient('/api/v1', () => 'cmd') }
}
afterEach(() => { cleanup(); vi.unstubAllGlobals() })
describe('Raspberry Pi expected-input settings', () => {
  it('presents one accessible form with JPEG defaults, fixed transport and a real target', async () => {
    const { api } = fixture(); render(<RaspberryPiUdpInputSettings api={api} />)
    const form = await screen.findByRole('form', { name: 'Raspberry Pi UDP Input' })
    for (const section of ['Network', 'Camera Stream', 'Advanced Transport', 'Diagnostics', 'Packet Inspector']) expect(within(form).getByRole('heading', { name: section })).toBeVisible()
    expect(screen.getByLabelText('Payload Format')).toHaveValue('JPEG')
    expect(within(screen.getByLabelText('Payload Format')).getAllByRole('option').map((item) => item.textContent)).toEqual(['JPEG', 'GRAY8', 'BINARY', 'RGB888'])
    expect(screen.getByText('Structured Chunked · protocol v1')).toBeVisible()
    expect(screen.getByLabelText('Backend IPv4')).toHaveTextContent('192.168.1.3')
    expect(screen.getByText(/Raspberry Pi should send UDP to: 192.168.1.3:5000/)).toBeVisible()
    expect(screen.getByText(/Apply does not remotely configure Raspberry Pi output/)).toBeVisible()
    expect(screen.queryByLabelText(/Transport Mode|scope|strip|Camera [AB]/)).not.toBeInTheDocument()
    expect(screen.getAllByRole('button', { name: 'Inspect Packet' })).toHaveLength(1)
    expect(screen.getByText('SOURCE_IP_MISMATCH')).toBeVisible()
    expect(screen.getByText('192.168.1.4:6000')).toBeVisible()
    expect(screen.getByText('Last packet time')).toBeVisible()
    expect(screen.getByText('Last valid frame (monotonic ns)')).toBeVisible()
    expect(screen.getByText('Processing backpressure drops').nextElementSibling).toHaveTextContent('6')
  })
  it('selects eligible interfaces and accepts an optional Pi IPv4', async () => {
    const { api } = fixture(); const user = userEvent.setup(); render(<RaspberryPiUdpInputSettings api={api} />)
    const select = await screen.findByLabelText('Network Interface')
    expect(within(select).getAllByRole('option').map((item) => item.textContent)).toEqual(['AUTO', 'Wi-Fi · 192.168.1.3', 'VPN · 10.0.0.2 (virtual)'])
    await user.selectOptions(select, 'vpn|10.0.0.2'); expect(screen.getByText(/Selected interface: VPN/)).toBeVisible()
    await user.type(screen.getByLabelText('Raspberry Pi IPv4'), '192.168.1.8'); expect(screen.getByLabelText('Raspberry Pi IPv4')).toHaveValue('192.168.1.8')
  })
  it('never advertises a wildcard or missing address as a target', async () => {
    const { api, fetch } = fixture()
    fetch.mockImplementation(async (url: string) => url.endsWith('/network-interfaces') ? apiResponse({ ...networkInterfaces, interfaces: [], advertisedBackendIpv4: '0.0.0.0', statusDetail: 'NO_USABLE_IPV4_INTERFACE' }) : url.endsWith('/udp-diagnostics') ? apiResponse(udpDiagnostics) : apiResponse(udpState()))
    render(<RaspberryPiUdpInputSettings api={api} />)
    expect(await screen.findByText(/No usable IPv4 interface/)).toBeVisible(); expect(screen.queryByText(/0\.0\.0\.0/)).not.toBeInTheDocument()
  })
  it('shows conditional format fields and exact two-byte validation immediately', async () => {
    const { api } = fixture(); const user = userEvent.setup(); render(<RaspberryPiUdpInputSettings api={api} />)
    const format = await screen.findByLabelText('Payload Format')
    expect(screen.getByLabelText('JPEG Max Frame Bytes')).toHaveValue(16777216)
    await user.selectOptions(format, 'GRAY8'); expect(screen.getByLabelText('Width')).toHaveValue(640); expect(screen.queryByLabelText('JPEG Max Frame Bytes')).not.toBeInTheDocument()
    await user.selectOptions(format, 'BINARY'); await user.selectOptions(screen.getByLabelText('Binary Packing'), 'BIT_PACKED'); expect(screen.getByLabelText('Binary Packing')).toHaveValue('BIT_PACKED')
    await user.selectOptions(format, 'RGB888'); await user.selectOptions(screen.getByLabelText('Channel Order'), 'BGR'); expect(screen.queryByLabelText('Binary Packing')).not.toBeInTheDocument(); expect(screen.getByLabelText('Channel Order')).toHaveValue('BGR')
    await user.clear(screen.getByLabelText('Expected Header (2-byte HEX)')); await user.type(screen.getByLabelText('Expected Header (2-byte HEX)'), 'AA')
    expect(screen.getByLabelText('Expected Header (2-byte HEX)')).toHaveAttribute('aria-invalid', 'true'); expect(screen.getByText('Envelope value must contain exactly two HEX bytes')).toBeVisible()
  })
  it('saves with CAS and preserves applied values until explicit Apply', async () => {
    const { api, fetch } = fixture(); const user = userEvent.setup(); render(<RaspberryPiUdpInputSettings api={api} />)
    const port = await screen.findByLabelText('UDP Listen Port'); await user.clear(port); await user.type(port, '5100')
    await user.click(screen.getByRole('button', { name: 'Save expected input' })); expect(await screen.findByText('Saved r2')).toBeVisible()
    expect(screen.getByRole('region', { name: 'Saved expected input' })).toHaveTextContent('5100'); expect(screen.getByRole('region', { name: 'Applied expected input' })).toHaveTextContent('5000')
    const save = fetch.mock.calls.find(([, options]) => options?.method === 'PUT')!
    expect(JSON.parse(String(save[1].body))).toEqual({ commandId: 'cmd', expectedSavedRevision: 1, configuration: { ...udpState().configuration, listenPort: 5100 } })
    expect(fetch.mock.calls.some(([url]) => String(url).endsWith('/apply'))).toBe(false)
    await user.click(screen.getByRole('button', { name: 'Apply saved revision' })); expect(await screen.findByText('Applied r2')).toBeVisible(); expect(screen.getByRole('region', { name: 'Applied expected input' })).toHaveTextContent('5100')
    const apply = fetch.mock.calls.find(([url]) => String(url).endsWith('/apply'))!; expect(JSON.parse(String(apply[1].body))).toEqual({ commandId: 'cmd', revision: 2, expectedSavedRevision: 2 })
  })
  it('keeps authoritative field errors visible and offers reload after a CAS conflict', async () => {
    const { api, fetch } = fixture(); const user = userEvent.setup(); render(<RaspberryPiUdpInputSettings api={api} />); await screen.findByLabelText('UDP Listen Port')
    fetch.mockResolvedValueOnce(new Response(JSON.stringify({ code: 'INVALID_REQUEST', message: 'Backend rejected draft', correlationId: 'c', details: { fieldErrors: [{ path: 'listenPort', message: 'Port reserved' }, { path: 'payload.futureRule', message: 'Unsupported image constraint' }] } }), { status: 400 }))
    await user.click(screen.getByRole('button', { name: 'Save expected input' })); expect(await screen.findByText(/payload.futureRule: Unsupported image constraint/)).toBeVisible(); expect(screen.getByLabelText('UDP Listen Port')).toHaveAttribute('aria-invalid', 'true'); expect(screen.getByText('Port reserved')).toBeVisible()
    fetch.mockResolvedValueOnce(new Response(JSON.stringify({ code: 'CONFLICT', message: 'changed', correlationId: 'c' }), { status: 409 }))
    await user.click(screen.getByRole('button', { name: 'Save expected input' })); expect(await screen.findByText(/Saved revision changed/)).toBeVisible()
    await user.click(screen.getByRole('button', { name: 'Reload expected input' })); expect(await screen.findByText('Saved r1')).toBeVisible()
  })
  it('keeps errors for hidden format fields visible after changing format', async () => {
    const { api, fetch } = fixture(); const user = userEvent.setup(); render(<RaspberryPiUdpInputSettings api={api} />); await screen.findByLabelText('Payload Format')
    fetch.mockResolvedValueOnce(new Response(JSON.stringify({ code: 'INVALID_REQUEST', message: 'Invalid JPEG limit', correlationId: 'c', details: { fieldErrors: [{ path: 'payload.maxFrameBytes', message: 'JPEG limit rejected' }] } }), { status: 400 }))
    await user.click(screen.getByRole('button', { name: 'Save expected input' })); await screen.findByText('JPEG limit rejected')
    await user.selectOptions(screen.getByLabelText('Payload Format'), 'GRAY8')
    expect(screen.getByText('payload.maxFrameBytes: JPEG limit rejected')).toBeVisible()
  })
  it('preserves normalized server values and explicitly applies the saved draft while newer edits stay unsaved', async () => {
    const { api, fetch } = fixture(); const user = userEvent.setup(); render(<RaspberryPiUdpInputSettings api={api} />); await screen.findByLabelText('Payload Format')
    await user.clear(screen.getByLabelText('Expected Header (2-byte HEX)')); await user.type(screen.getByLabelText('Expected Header (2-byte HEX)'), 'aa01')
    const normalized = { ...udpState(), savedRevision: 2, configuration: { ...udpState().configuration, envelope: { headerHex: 'AA 01', footerHex: '0D 0A' } } }
    fetch.mockResolvedValueOnce(apiResponse(normalized)); await user.click(screen.getByRole('button', { name: 'Save expected input' })); await screen.findByText('Saved r2')
    expect(screen.getByLabelText('Expected Header (2-byte HEX)')).toHaveValue('AA 01')
    await user.clear(screen.getByLabelText('UDP Listen Port')); await user.type(screen.getByLabelText('UDP Listen Port'), '5200')
    fetch.mockResolvedValueOnce(apiResponse({ ...normalized, appliedRevision: 2, appliedConfiguration: normalized.configuration }))
    await user.click(screen.getByRole('button', { name: 'Apply saved revision' })); await screen.findByText('Applied r2')
    expect(screen.getByLabelText('UDP Listen Port')).toHaveValue(5200); expect(screen.getByRole('region', { name: 'Applied expected input' })).toHaveTextContent('5000')
    const save = fetch.mock.calls.find(([, options]) => options?.method === 'PUT')!; expect(JSON.parse(String(save[1].body)).configuration.envelope.headerHex).toBe('aa01')
  })
  it('reports a stale specific interface without silently selecting another adapter', async () => {
    const { api, fetch } = fixture()
    const state = udpState(); state.configuration.networkInterface = { mode: 'SPECIFIC', interfaceId: 'missing', ipv4: '10.2.0.1' }
    fetch.mockImplementation(async (url: string) => url.endsWith('/network-interfaces') ? apiResponse(networkInterfaces) : url.endsWith('/udp-diagnostics') ? apiResponse(udpDiagnostics) : apiResponse(state))
    render(<RaspberryPiUdpInputSettings api={api} />)
    expect(await screen.findByText(/Selected interface is no longer usable/)).toBeVisible(); expect(screen.getByLabelText('Network Interface')).toHaveValue('missing|10.2.0.1')
  })
  it('aborts settings and diagnostics requests and stops polling on unmount', async () => {
    const { api, fetch } = fixture(); const view = render(<RaspberryPiUdpInputSettings api={api} />); await screen.findByLabelText('Payload Format'); view.unmount()
    expect(fetch.mock.calls.every(([, options]) => options?.signal?.aborted)).toBe(true)
    const calls = fetch.mock.calls.length; await new Promise((resolve) => setTimeout(resolve, 2100)); expect(fetch).toHaveBeenCalledTimes(calls)
  })
  it('shows a load error and permits retry without abandoning the settings panel', async () => {
    const { api, fetch } = fixture(); const user = userEvent.setup(); fetch.mockRejectedValueOnce(new Error('offline'))
    render(<RaspberryPiUdpInputSettings api={api} />); expect(await screen.findByText(/Unable to load expected-input settings/)).toBeVisible()
    await user.click(screen.getByRole('button', { name: 'Reload expected input' })); await waitFor(() => expect(screen.getByLabelText('Payload Format')).toHaveValue('JPEG'))
  })
  it.each(['success', 'failure'] as const)('ignores stale initial-load %s after a newer Reload completes', async (settlement) => {
    const { api, fetch } = fixture(); const user = userEvent.setup()
    let resolveInitial!: (value: Response) => void; let rejectInitial!: (reason: Error) => void; let loads = 0; let interfaceLoads = 0
    const fresh = { ...udpState(), savedRevision: 2, configuration: { ...udpState().configuration, listenPort: 5100 } }
    fetch.mockImplementation(async (url: string) => {
      if (url.endsWith('/udp-diagnostics')) return apiResponse(udpDiagnostics)
      if (url.endsWith('/network-interfaces')) return apiResponse(++interfaceLoads === 1 ? networkInterfaces : { ...networkInterfaces, advertisedBackendIpv4: '10.0.0.2', listenPort: 5100 })
      if (++loads === 1) return new Promise<Response>((resolve, reject) => { resolveInitial = resolve; rejectInitial = reject })
      return apiResponse(fresh)
    })
    render(<RaspberryPiUdpInputSettings api={api} />)
    await user.click(screen.getByRole('button', { name: 'Reload expected input' })); await screen.findByText('Saved r2')
    await act(async () => {
      if (settlement === 'success') resolveInitial(apiResponse(udpState()))
      else rejectInitial(new Error('stale load failed'))
    })
    expect(screen.getByText('Saved r2')).toBeVisible(); expect(screen.getByLabelText('UDP Listen Port')).toHaveValue(5100)
    expect(screen.getByLabelText('Backend IPv4')).toHaveTextContent('10.0.0.2')
    expect(screen.queryByText(/Unable to load expected-input settings/)).not.toBeInTheDocument()
  })
})
