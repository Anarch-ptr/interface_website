import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import { udpDiagnostics } from '../test/raspberryPiUdpFixtures'
import { UdpDiagnosticsPanel } from './UdpDiagnosticsPanel'

afterEach(cleanup)

describe('UDP applied network diagnostics', () => {
  it('renders applied bind, destination, source set, generation and actual sender separately', () => {
    render(<UdpDiagnosticsPanel diagnostics={{
      ...udpDiagnostics,
      status: 'SOURCE_IP_MISMATCH',
      lastPacketSourceIpv4: '10.97.199.99',
      lastPacketSourcePort: 6200,
      appliedNetwork: {
        actualBindIpv4: '0.0.0.0', actualBindInterfaceId: null,
        configuredDestination: 'filamentvision-pc.local', resolvedDestinationIpv4: '10.97.199.20',
        configuredSource: 'filament-pi.local', resolvedSourceIpv4s: ['10.97.199.28', '10.97.199.42'],
        resolutionGeneration: 5, planCreatedAtEpochMillis: 1000, appliedAtEpochMillis: 2000,
      },
    }} />)

    expect(screen.getByText('Applied destination').nextElementSibling).toHaveTextContent('filamentvision-pc.local → 10.97.199.20')
    expect(screen.getByText('Applied bind').nextElementSibling).toHaveTextContent('0.0.0.0 · all IPv4 interfaces')
    expect(screen.getByText('Applied source set').nextElementSibling).toHaveTextContent('10.97.199.28, 10.97.199.42')
    expect(screen.getByText('Resolution generation').nextElementSibling).toHaveTextContent('5')
    expect(screen.getByText('Actual sender').nextElementSibling).toHaveTextContent('10.97.199.99:6200')
    expect(screen.getByText('Actual sender').nextElementSibling).toHaveTextContent('does not match applied source set')
  })

  it('labels an absent applied plan as inactive without inventing a destination', () => {
    render(<UdpDiagnosticsPanel diagnostics={{ ...udpDiagnostics, status: 'WAITING_FOR_RASPBERRY_PI', appliedNetwork: null }} />)
    expect(screen.getByText('Applied network').nextElementSibling).toHaveTextContent('Inactive')
    expect(screen.queryByText(/undefined|null/)).not.toBeInTheDocument()
  })
})
