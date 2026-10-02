import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { defaultUdpProfiles } from '../config/udpInputProfile'
import type { UdpConfigurationState } from '../domain/udpInput'
import { BackendApiClient, BackendApiError } from '../services/BackendApiClient'
import { UdpInputSettings } from './UdpInputSettings'

function fixture() {
  const configuration = defaultUdpProfiles()
  const state: UdpConfigurationState = { savedRevision: 1, appliedRevision: 1, configuration }
  const api = {
    getUdpConfiguration: vi.fn().mockResolvedValue(state),
    saveUdpConfiguration: vi.fn().mockResolvedValue({ ...state, savedRevision: 2 }),
    applyUdpConfiguration: vi.fn().mockResolvedValue({ ...state, savedRevision: 2, appliedRevision: 2, outcome: 'APPLIED' }),
    getUdpDiagnostics: vi.fn().mockResolvedValue({ cameraId: 'A', transportMode: 'STRUCTURED_CHUNKED', datagramsReceived: 4 }),
  } as unknown as BackendApiClient
  return { api, configuration }
}

describe('UdpInputSettings', () => {
  afterEach(cleanup)
  it('keeps independent camera drafts, conditional fields and advanced layers', async () => {
    const { api } = fixture(); const user = userEvent.setup()
    render(<UdpInputSettings api={api} />)
    await screen.findByRole('heading', { name: 'UDP Input Settings' })
    const portA = screen.getByLabelText('Camera A Listen Port')
    const portB = screen.getByLabelText('Camera B Listen Port')
    await user.clear(portA); await user.type(portA, '6201')
    expect(portB).toHaveValue(5102)
    await user.selectOptions(screen.getByLabelText('Camera B Payload Format'), 'DIAMETER')
    expect(screen.getByLabelText('Camera B valueEncoding')).toBeVisible()
    expect(screen.queryByLabelText('Camera B Width')).not.toBeInTheDocument()
    expect(screen.getAllByText('Advanced Transport').length).toBeGreaterThan(0)
    expect(screen.getAllByText('Advanced UDP Framing')).toHaveLength(2)
  })

  it('saves the full pair, applies separately and shows authoritative backend errors', async () => {
    const { api } = fixture(); const user = userEvent.setup()
    render(<UdpInputSettings api={api} />); await screen.findByText('Saved r1')
    await user.click(screen.getByRole('button', { name: 'Save UDP settings' }))
    await waitFor(() => expect(api.saveUdpConfiguration).toHaveBeenCalledTimes(1))
    expect(screen.getByText('Saved r2')).toBeVisible()
    expect(screen.getByText('Applied r1')).toBeVisible()
    await user.click(screen.getByRole('button', { name: 'Apply revision' }))
    await waitFor(() => expect(api.applyUdpConfiguration).toHaveBeenCalledWith('simulator-1', 2, 2))
    expect(await screen.findByText('Applied r2')).toBeVisible()

    vi.mocked(api.saveUdpConfiguration).mockRejectedValueOnce(new BackendApiError(400, 'INVALID_REQUEST', 'Invalid request', 'c',
      [{ path: 'cameraA.listenPort', message: 'Port is unavailable' }]))
    await user.click(screen.getByRole('button', { name: 'Save UDP settings' }))
    expect(await screen.findByText('Port is unavailable')).toBeVisible()
  })
})
