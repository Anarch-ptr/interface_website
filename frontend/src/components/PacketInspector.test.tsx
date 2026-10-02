import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { BackendApiClient, BackendApiError } from '../services/BackendApiClient'
import { PacketInspector } from './PacketInspector'

const snapshot = (sequence: number, status = 'VALID') => ({ sequence, cameraId: 'A' as const,
  rawDatagram: { hexPreview: sequence === 1 ? 'AA 01' : 'AA 02', status },
  parsedFrame: { hexPreview: '10 20', payloadFormat: 'GRAY8' as const } })
function apiFixture() {
  let sequence = 0
  return {
    createInspectorLease: vi.fn().mockResolvedValue({ leaseId: 'lease-A', cameraId: 'A', previewBytes: 128, expiresAtMillis: 9999 }),
    releaseInspectorLease: vi.fn().mockResolvedValue({ deleted: true }),
    getInspector: vi.fn().mockImplementation(async () => ({ unchanged: false, etag: `"${++sequence}"`, snapshot: snapshot(sequence) })),
  } as unknown as BackendApiClient
}
afterEach(cleanup)

describe('PacketInspector', () => {
  it('owns lease lifecycle, polls at 2 Hz and freezes display without stopping polling', async () => {
    const api = apiFixture(); const user = userEvent.setup()
    render(<PacketInspector api={api} deviceId="device1" camera="A" />)
    expect(api.createInspectorLease).not.toHaveBeenCalled()
    await user.click(screen.getByRole('button', { name: 'Inspect Packet' }))
    expect(await screen.findByText('AA 01')).toBeVisible()
    await user.click(screen.getByRole('button', { name: 'Freeze' }))
    await waitFor(() => expect(api.getInspector).toHaveBeenCalledTimes(2), { timeout: 1200 })
    expect(screen.getByText('AA 01')).toBeVisible()
    await user.click(screen.getByRole('button', { name: 'Resume' }))
    expect(await screen.findByText('AA 02')).toBeVisible()
    expect(screen.getByText('Raw Datagram')).toBeVisible()
    expect(screen.getByText('Parsed Frame')).toBeVisible()
    await user.click(screen.getByRole('button', { name: 'Close Inspector' }))
    await waitFor(() => expect(api.releaseInspectorLease).toHaveBeenCalledWith('device1', 'A', 'lease-A'))
  })

  it('releases on unmount, supports bounded size changes and handles unchanged polling', async () => {
    const api = apiFixture(); vi.mocked(api.getInspector).mockResolvedValueOnce({ unchanged: true, etag: '"7"' })
    const view = render(<PacketInspector api={api} deviceId="device1" camera="A" />); const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: 'Inspect Packet' }))
    expect(await screen.findByText('Waiting for UDP Datagram…')).toBeVisible()
    await user.selectOptions(screen.getByLabelText('Camera A Inspector preview bytes'), '256')
    await waitFor(() => expect(api.createInspectorLease).toHaveBeenLastCalledWith('device1', 'A', 256, expect.any(AbortSignal)))
    view.unmount()
    await waitFor(() => expect(api.releaseInspectorLease).toHaveBeenCalled())
  })

  it('recovers an expired lease and copies only displayed bounded data', async () => {
    const api = apiFixture(); const user = userEvent.setup(); const writeText = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText } })
    vi.mocked(api.getInspector).mockRejectedValueOnce(new BackendApiError(404, 'NOT_FOUND', 'expired', 'c'))
      .mockResolvedValueOnce({ unchanged: false, etag: '"8"', snapshot: snapshot(8, 'SOURCE_IP_MISMATCH') })
    render(<PacketInspector api={api} deviceId="device1" camera="A" />)
    await user.click(screen.getByRole('button', { name: 'Inspect Packet' }))
    await waitFor(() => expect(api.createInspectorLease).toHaveBeenCalledTimes(2), { timeout: 1200 })
    expect(await screen.findByText('SOURCE_IP_MISMATCH')).toBeVisible()
    await user.click(screen.getByRole('button', { name: 'Copy Hex' }))
    expect(writeText).toHaveBeenCalledWith('AA 02')
    await user.click(screen.getByRole('button', { name: 'Copy Debug Info' }))
    expect(writeText.mock.calls[1][0]).toContain('SOURCE_IP_MISMATCH')
  })
})
