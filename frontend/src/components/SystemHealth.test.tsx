import { act, cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { BackendApiClient } from '../services/BackendApiClient'
import { MonitoringStore } from '../state/MonitoringStore'
import { LocalConfigurationStorage } from '../config/monitoringConfig'
import { MockFilamentDataSource } from '../test/fixtures/MockFilamentDataSource'
import { udpDiagnostics } from '../test/raspberryPiUdpFixtures'
import type { UdpDiagnostics, UdpOperationalStatus } from '../domain/raspberryPiUdpInput'
import { SystemHealth } from './SystemHealth'

afterEach(() => { cleanup(); vi.useRealTimers() })
function live() {
  return new MonitoringStore(new MockFilamentDataSource(), new LocalConfigurationStorage(window.localStorage)).getLiveSnapshot()
}

describe('SystemHealth UDP status', () => {
  it.each<[UdpOperationalStatus, string]>([
    ['DISABLED', 'UDP disabled'], ['UDP_READY', 'UDP ready'],
    ['WAITING_FOR_RASPBERRY_PI', 'Waiting for Raspberry Pi'], ['RECEIVING', 'Receiving UDP packets'],
    ['NO_PACKETS_RECENTLY', 'No UDP packets recently'], ['SOURCE_IP_MISMATCH', 'Unexpected Raspberry Pi source IP'],
    ['BIND_FAILED', 'UDP bind failed'],
  ])('presents Backend %s without calling UDP connected', async (status, label) => {
    const api = new class extends BackendApiClient {
      override async getRaspberryPiUdpDiagnostics() { return { ...udpDiagnostics, status } }
    }()
    render(<SystemHealth live={live()} api={api} />)
    expect(await screen.findByText(label)).toBeVisible()
    expect(screen.queryByText('CONNECTED')).not.toBeInTheDocument()
  })

  it('polls sequentially and cancels pending reads on unmount', async () => {
    vi.useFakeTimers()
    let resolve!: (value: UdpDiagnostics) => void
    const signals: AbortSignal[] = []
    const read = vi.fn((signal?: AbortSignal) => {
      if (signal) signals.push(signal)
      return new Promise<UdpDiagnostics>(done => { resolve = done })
    })
    const api = new class extends BackendApiClient {
      override getRaspberryPiUdpDiagnostics(_device: string, signal?: AbortSignal) { return read(signal) }
    }()
    const view = render(<SystemHealth live={live()} api={api} />)
    expect(read).toHaveBeenCalledTimes(1)
    await act(async () => { await vi.advanceTimersByTimeAsync(10_000) })
    expect(read).toHaveBeenCalledTimes(1)
    await act(async () => { resolve({ ...udpDiagnostics, status: 'RECEIVING' }) })
    expect(screen.getByText('Receiving UDP packets')).toBeVisible()
    await act(async () => { await vi.advanceTimersByTimeAsync(1000) })
    expect(read).toHaveBeenCalledTimes(2)
    view.unmount()
    expect(signals.at(-1)?.aborted).toBe(true)
    await act(async () => { resolve({ ...udpDiagnostics, status: 'UDP_READY' }); await vi.advanceTimersByTimeAsync(5000) })
    expect(read).toHaveBeenCalledTimes(2)
    expect(vi.getTimerCount()).toBe(0)
  })

  it('marks diagnostics unavailable after a failed poll instead of retaining receiving status', async () => {
    vi.useFakeTimers()
    let reads = 0
    const api = new class extends BackendApiClient {
      override async getRaspberryPiUdpDiagnostics() {
        if (reads++ > 0) throw new Error('offline')
        return { ...udpDiagnostics, status: 'RECEIVING' as const }
      }
    }()
    render(<SystemHealth live={live()} api={api} />)
    await act(async () => Promise.resolve())
    expect(screen.getByText('Receiving UDP packets')).toBeVisible()
    await act(async () => { await vi.advanceTimersByTimeAsync(1000) })
    expect(screen.getByText('UDP status unavailable')).toBeVisible()
    expect(screen.queryByText('Receiving UDP packets')).not.toBeInTheDocument()
  })
})
