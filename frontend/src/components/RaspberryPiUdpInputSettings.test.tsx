import { act, cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { NetworkResolutionPreview, UdpConfigurationState } from '../domain/raspberryPiUdpInput'
import { BackendApiClient } from '../services/BackendApiClient'
import { apiResponse, networkInterfaces, udpDiagnostics, udpState } from '../test/raspberryPiUdpFixtures'
import { RaspberryPiUdpInputSettings } from './RaspberryPiUdpInputSettings'

const changedPreview = (generation = 1): NetworkResolutionPreview => ({
  configuredDestination: 'backend.local',
  configuredSource: 'filament-pi.local',
  candidatePlan: {
    actualBindIpv4: '0.0.0.0', actualBindInterfaceId: null,
    configuredDestination: 'backend.local', resolvedDestinationIpv4: '192.168.1.42',
    configuredSource: 'filament-pi.local', resolvedSourceIpv4s: ['192.168.1.42'],
    destinationStatus: 'RESOLVED', sourceStatus: 'RESOLVED',
    planCreatedAtEpochMillis: 200, appliedAtEpochMillis: null, resolutionGeneration: generation,
  },
  destinationStatus: 'RESOLVED', sourceStatus: 'RESOLVED',
  networkConsistencyStatus: 'REAPPLY_REQUIRED', reapplyRequired: true,
  differences: ['resolvedDestinationIpv4', 'resolvedSourceIpv4s'], warnings: [], errorCode: null,
  previewedAtEpochMillis: 200, appliedResolutionGeneration: generation, superseded: false,
})

function fixture(initial: UdpConfigurationState = udpState()) {
  let state = structuredClone(initial)
  let preview = changedPreview(state.resolutionGeneration)
  const fetch = vi.fn().mockImplementation(async (url: string, options?: RequestInit) => {
    if (url.endsWith('/network-interfaces')) return apiResponse(networkInterfaces)
    if (url.endsWith('/udp-diagnostics')) return apiResponse(udpDiagnostics)
    if (url.endsWith('/udp-network-resolution-preview')) return apiResponse(preview)
    if (options?.method === 'PUT') {
      const request = JSON.parse(String(options.body)) as { configuration: UdpConfigurationState['configuration'] }
      state = { ...state, savedRevision: state.savedRevision + 1, configuration: request.configuration }
    } else if (url.endsWith('/apply')) {
      const request = JSON.parse(String(options?.body)) as { revision: number }
      const candidate = preview.candidatePlan
      state = {
        ...state,
        appliedRevision: request.revision,
        appliedConfiguration: state.configuration,
        resolutionGeneration: state.resolutionGeneration + 1,
        appliedEffectiveNetworkPlan: candidate
          ? { ...candidate, appliedAtEpochMillis: 201, resolutionGeneration: state.resolutionGeneration + 1 }
          : state.appliedEffectiveNetworkPlan,
        networkConsistencyStatus: 'HEALTHY', reapplyRequired: false, outcome: 'APPLIED',
      }
      preview = { ...preview, reapplyRequired: false, networkConsistencyStatus: 'HEALTHY', differences: [] }
    }
    return apiResponse(state)
  })
  vi.stubGlobal('fetch', fetch)
  return { fetch, api: new BackendApiClient('/api/v1', () => 'cmd') }
}

afterEach(() => { cleanup(); vi.unstubAllGlobals() })

describe('Raspberry Pi flexible UDP network settings', () => {
  it('shows simple AUTO defaults and only applied network facts as the send target', async () => {
    const { api } = fixture()
    render(<RaspberryPiUdpInputSettings api={api} />)

    const form = await screen.findByRole('form', { name: 'Raspberry Pi UDP Input' })
    expect(within(form).getByLabelText('Backend Destination Host/IP')).toHaveValue('')
    expect(within(form).getByLabelText('Raspberry Pi Source Host/IP')).toHaveValue('')
    expect(within(form).getByLabelText('UDP Listen Port')).toHaveValue(5000)
    expect(screen.getByText('Actual Bind Address:').parentElement).toHaveTextContent('0.0.0.0')
    expect(screen.getByText('Raspberry Pi should send UDP to: 192.168.1.3:5000')).toBeVisible()
    expect(screen.getByText('Advanced Network')).toBeVisible()
    expect(screen.queryByLabelText('Resolution preview')).not.toBeInTheDocument()
  })

  it('saves manual hostnames and maps an empty source to ANY without applying', async () => {
    const { api, fetch } = fixture()
    const user = userEvent.setup()
    render(<RaspberryPiUdpInputSettings api={api} />)

    const destination = await screen.findByLabelText('Backend Destination Host/IP')
    await user.type(destination, 'backend.local')
    const source = screen.getByLabelText('Raspberry Pi Source Host/IP')
    await user.type(source, 'filament-pi.local')
    await user.clear(source)
    await user.click(screen.getByRole('button', { name: 'Save expected input' }))

    await screen.findByText('Saved r2')
    const request = fetch.mock.calls.find(([, options]) => options?.method === 'PUT')
    expect(request).toBeDefined()
    expect(JSON.parse(String(request?.[1]?.body)).configuration.network).toEqual({
      bind: { mode: 'AUTO' },
      destination: { mode: 'MANUAL', hostOrIpv4: 'backend.local' },
      sourceFilter: { mode: 'ANY' },
      listenPort: 5000,
    })
    expect(fetch.mock.calls.some(([url]) => String(url).endsWith('/apply'))).toBe(false)
  })

  it('exposes every advanced bind mode and preserves the selected stable interface identity', async () => {
    const { api } = fixture()
    const user = userEvent.setup()
    render(<RaspberryPiUdpInputSettings api={api} />)

    await user.click(await screen.findByText('Advanced Network'))
    const bindMode = screen.getByLabelText('Bind Mode')
    expect(within(bindMode).getAllByRole('option').map((option) => option.textContent)).toEqual([
      'AUTO', 'ALL_INTERFACES', 'SPECIFIC_INTERFACE', 'MANUAL_IP',
    ])
    await user.selectOptions(bindMode, 'SPECIFIC_INTERFACE')
    await user.selectOptions(screen.getByLabelText('Specific Interface'), 'vpn|10.0.0.2')
    expect(screen.getByLabelText('Specific Interface')).toHaveValue('vpn|10.0.0.2')
    await user.selectOptions(bindMode, 'MANUAL_IP')
    await user.type(screen.getByLabelText('Manual Bind IPv4'), '10.0.0.8')
    expect(screen.getByLabelText('Manual Bind IPv4')).toHaveValue('10.0.0.8')
  })

  it('keeps Resolve preview explicitly Not Applied and leaves the active target unchanged', async () => {
    const { api, fetch } = fixture()
    const user = userEvent.setup()
    render(<RaspberryPiUdpInputSettings api={api} />)

    await user.click(await screen.findByRole('button', { name: 'Resolve / Refresh' }))
    const preview = await screen.findByLabelText('Resolution preview')
    expect(preview).toHaveTextContent('Not Applied')
    expect(preview).toHaveTextContent('RESOLUTION_CHANGED · REAPPLY_REQUIRED')
    expect(preview).toHaveTextContent('192.168.1.42')
    expect(screen.getByText('Raspberry Pi should send UDP to: 192.168.1.3:5000')).toBeVisible()
    const request = fetch.mock.calls.find(([url]) => String(url).endsWith('/udp-network-resolution-preview'))
    expect(JSON.parse(String(request?.[1]?.body))).toEqual({ network: udpState().configuration.network })
  })

  it('shows ambiguous AUTO destination candidates without presenting one as applied', async () => {
    const { api, fetch } = fixture()
    const user = userEvent.setup()
    fetch.mockImplementation(async (url: string) => {
      if (url.endsWith('/network-interfaces')) return apiResponse(networkInterfaces)
      if (url.endsWith('/udp-diagnostics')) return apiResponse(udpDiagnostics)
      if (url.endsWith('/udp-network-resolution-preview')) return apiResponse({
        ...changedPreview(), candidatePlan: null, destinationStatus: 'AMBIGUOUS',
        networkConsistencyStatus: 'REAPPLY_REQUIRED', errorCode: 'DESTINATION_AMBIGUOUS',
      })
      return apiResponse(udpState())
    })
    render(<RaspberryPiUdpInputSettings api={api} />)

    await user.click(await screen.findByRole('button', { name: 'Resolve / Refresh' }))
    expect(await screen.findByLabelText('Resolution preview')).toHaveTextContent('DESTINATION_AMBIGUOUS')
    expect(screen.getByText('Raspberry Pi should send UDP to: 192.168.1.3:5000')).toBeVisible()
  })

  it('saves then applies with the complete revision and generation CAS contract', async () => {
    const { api, fetch } = fixture()
    const user = userEvent.setup()
    render(<RaspberryPiUdpInputSettings api={api} />)

    const port = await screen.findByLabelText('UDP Listen Port')
    await user.clear(port)
    await user.type(port, '5100')
    await user.click(screen.getByRole('button', { name: 'Save expected input' }))
    await screen.findByText('Saved r2')
    await user.click(screen.getByRole('button', { name: 'Apply saved revision' }))
    await screen.findByText('Applied r2')

    const request = fetch.mock.calls.find(([url]) => String(url).endsWith('/apply'))
    expect(JSON.parse(String(request?.[1]?.body))).toEqual({
      commandId: 'cmd', revision: 2, expectedSavedRevision: 2,
      expectedAppliedRevision: 1, expectedResolutionGeneration: 1, forceReapply: false,
    })
  })

  it('same-revision Re-Apply activates changed DNS, increments generation and clears the preview warning', async () => {
    const initial = udpState()
    initial.reapplyRequired = true
    initial.networkConsistencyStatus = 'REAPPLY_REQUIRED'
    initial.configuration = {
      ...initial.configuration,
      network: {
        ...initial.configuration.network,
        destination: { mode: 'MANUAL', hostOrIpv4: 'backend.local' },
        sourceFilter: { mode: 'IP_OR_HOST', hostOrIpv4: 'filament-pi.local' },
      },
    }
    initial.appliedConfiguration = initial.configuration
    const { api, fetch } = fixture(initial)
    const user = userEvent.setup()
    render(<RaspberryPiUdpInputSettings api={api} />)

    await user.click(await screen.findByRole('button', { name: 'Resolve / Refresh' }))
    await screen.findByText('RESOLUTION_CHANGED · REAPPLY_REQUIRED')
    await user.click(screen.getByRole('button', { name: 'Re-Apply network resolution' }))

    await waitFor(() => expect(screen.getByText('Resolved Destination IPv4:').parentElement).toHaveTextContent('192.168.1.42'))
    expect(screen.queryByLabelText('Resolution preview')).not.toBeInTheDocument()
    expect(screen.queryByText('RESOLUTION_CHANGED · REAPPLY_REQUIRED')).not.toBeInTheDocument()
    const request = fetch.mock.calls.find(([url]) => String(url).endsWith('/apply'))
    expect(JSON.parse(String(request?.[1]?.body))).toEqual({
      commandId: 'cmd', revision: 1, expectedSavedRevision: 1,
      expectedAppliedRevision: 1, expectedResolutionGeneration: 1, forceReapply: true,
    })
  })

  it('renders authoritative nested validation errors', async () => {
    const { api, fetch } = fixture()
    const user = userEvent.setup()
    render(<RaspberryPiUdpInputSettings api={api} />)
    await screen.findByLabelText('UDP Listen Port')
    fetch.mockResolvedValueOnce(new Response(JSON.stringify({
      code: 'INVALID_REQUEST', message: 'Backend rejected draft', correlationId: 'c',
      details: { fieldErrors: [{ path: 'network.listenPort', message: 'Port is unavailable' }] },
    }), { status: 400 }))

    await user.click(screen.getByRole('button', { name: 'Save expected input' }))
    expect(await screen.findByText('Port is unavailable')).toBeVisible()
    expect(screen.getByLabelText('UDP Listen Port')).toHaveAttribute('aria-invalid', 'true')
  })

  it('keeps offline Save available and reports the network failure without applying', async () => {
    const { api, fetch } = fixture()
    const user = userEvent.setup()
    render(<RaspberryPiUdpInputSettings api={api} />)
    await screen.findByLabelText('UDP Listen Port')
    fetch.mockRejectedValueOnce(new Error('offline'))

    await user.click(screen.getByRole('button', { name: 'Save expected input' }))
    expect(await screen.findByText('Unable to reach Backend.')).toBeVisible()
    expect(screen.getByRole('button', { name: 'Save expected input' })).toBeEnabled()
    expect(fetch.mock.calls.some(([url]) => String(url).endsWith('/apply'))).toBe(false)
  })

  it('ignores a stale initial response after a newer Reload completes', async () => {
    const { api, fetch } = fixture()
    const user = userEvent.setup()
    let resolveInitial!: (value: Response) => void
    let configurationLoads = 0
    const fresh = udpState()
    fresh.savedRevision = 2
    fresh.configuration = { ...fresh.configuration, network: { ...fresh.configuration.network, listenPort: 5100 } }
    fetch.mockImplementation(async (url: string) => {
      if (url.endsWith('/network-interfaces')) return apiResponse(networkInterfaces)
      if (url.endsWith('/udp-diagnostics')) return apiResponse(udpDiagnostics)
      if (++configurationLoads === 1) return new Promise<Response>((resolve) => { resolveInitial = resolve })
      return apiResponse(fresh)
    })
    render(<RaspberryPiUdpInputSettings api={api} />)

    await user.click(screen.getByRole('button', { name: 'Reload expected input' }))
    await screen.findByText('Saved r2')
    await act(async () => resolveInitial(apiResponse(udpState())))
    expect(screen.getByText('Saved r2')).toBeVisible()
    expect(screen.getByLabelText('UDP Listen Port')).toHaveValue(5100)
  })

  it('aborts configuration, diagnostics and preview requests and stops polling on unmount', async () => {
    const { api, fetch } = fixture()
    const user = userEvent.setup()
    const view = render(<RaspberryPiUdpInputSettings api={api} />)
    await user.click(await screen.findByRole('button', { name: 'Resolve / Refresh' }))
    await screen.findByLabelText('Resolution preview')
    view.unmount()

    expect(fetch.mock.calls.every(([, options]) => options?.signal?.aborted)).toBe(true)
    const calls = fetch.mock.calls.length
    await new Promise((resolve) => setTimeout(resolve, 2100))
    expect(fetch).toHaveBeenCalledTimes(calls)
  })
})
