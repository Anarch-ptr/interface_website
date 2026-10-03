import { act, cleanup, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it } from 'vitest'

import App from './App'
import {
  DEFAULT_MONITORING_CONFIGURATION,
  type ConfigurationStorage,
} from './config/monitoringConfig'
import { BackendApiClient } from './services/BackendApiClient'
import type { NetworkInterfaces, UdpConfigurationState, UdpDiagnostics } from './domain/raspberryPiUdpInput'
import { networkInterfaces, udpDiagnostics, udpState } from './test/raspberryPiUdpFixtures'
import {
  VisionSourceState,
  type BackendMeasurement, type BackendMeasurementRecord, type BackendState,
  type BackendEvent, type BackendRecovery, type CameraPreview,
  type MonitoringConfiguration,
} from './domain/monitoring'
import type {
  FilamentDataSource,
  ReadingListener,
  SourceStateListener,
} from './services/FilamentDataSource'
import { MonitoringRuntime } from './state/MonitoringRuntime'
import { MonitoringStore } from './state/MonitoringStore'

afterEach(cleanup)

class MemoryConfigurationStorage implements ConfigurationStorage {
  configuration = { ...DEFAULT_MONITORING_CONFIGURATION }

  load(): MonitoringConfiguration {
    return { ...this.configuration }
  }

  save(configuration: MonitoringConfiguration): void {
    this.configuration = { ...configuration }
  }

  reset(): MonitoringConfiguration {
    this.configuration = { ...DEFAULT_MONITORING_CONFIGURATION }
    return this.load()
  }
}

class TestSource implements FilamentDataSource {
  state: VisionSourceState = VisionSourceState.STOPPED
  private readonly readingListeners = new Set<ReadingListener>()
  private readonly stateListeners = new Set<SourceStateListener>()
  private measurementListeners = new Set<(value: BackendMeasurementRecord) => void>()
  private previewListeners = new Set<(value: CameraPreview | null) => void>()
  private backendListeners = new Set<(value: BackendState) => void>()
  private eventListeners = new Set<(value: BackendEvent) => void>()
  private recoveryListeners = new Set<(value: BackendRecovery) => void>()

  subscribe(listener: ReadingListener): () => void {
    this.readingListeners.add(listener)
    return () => this.readingListeners.delete(listener)
  }

  subscribeState(listener: SourceStateListener): () => void {
    this.stateListeners.add(listener)
    return () => this.stateListeners.delete(listener)
  }

  start(): void {
    this.setState(VisionSourceState.LIVE)
  }

  observe(): void { this.setState(VisionSourceState.LIVE) }
  unobserve(): void { this.setState(VisionSourceState.STOPPED) }
  subscribeMeasurement(listener: (value: BackendMeasurementRecord) => void) { this.measurementListeners.add(listener); return () => this.measurementListeners.delete(listener) }
  subscribePreview(listener: (value: CameraPreview | null) => void) { this.previewListeners.add(listener); return () => this.previewListeners.delete(listener) }
  subscribeBackendState(listener: (value: BackendState) => void) { this.backendListeners.add(listener); return () => this.backendListeners.delete(listener) }
  subscribeEvents(listener: (value: BackendEvent) => void) { this.eventListeners.add(listener); return () => this.eventListeners.delete(listener) }
  subscribeRecovery(listener: (value: BackendRecovery) => void) { this.recoveryListeners.add(listener); return () => this.recoveryListeners.delete(listener) }

  updateConfiguration(): void {}

  stop(): void {
    this.setState(VisionSourceState.STOPPED)
  }

  dispose(): void {
    this.stop()
    this.readingListeners.clear()
    this.stateListeners.clear()
  }

  emit(value: BackendMeasurementRecord): void { for (const listener of this.measurementListeners) listener(value) }
  preview(value: CameraPreview | null): void { for (const listener of this.previewListeners) listener(value) }
  backend(value: BackendState): void { for (const listener of this.backendListeners) listener(value) }
  event(value: BackendEvent): void { for (const listener of this.eventListeners) listener(value) }
  recover(value: BackendRecovery): void { for (const listener of this.recoveryListeners) listener(value) }

  private setState(state: VisionSourceState): void {
    this.state = state
    for (const listener of this.stateListeners) listener(state)
  }
}

function createFixture(api: BackendApiClient = udpApiFixture()) {
  const source = new TestSource()
  const storage = new MemoryConfigurationStorage()
  const store = new MonitoringStore(source, storage)
  const runtime = new MonitoringRuntime(store)
  const view = render(<App store={store} runtime={runtime} api={api} />)
  return { source, storage, store, runtime, view }
}

function emitDiameter(
  source: TestSource,
  diameterMm: number,
  timestamp: number,
): void {
  act(() => {
    source.emit({ id: `measurement-${timestamp}`, sessionId: 'session-1', measurement: measurement(diameterMm, timestamp) })
  })
}

describe('FilamentVision application', () => {
  it('clears current values across two sessions while retaining history without a NORMAL transition', async () => {
    const { source, store, runtime, view } = createFixture()
    const alarm = { id: 'old-alarm', sessionId: 'session-1', timestamp: 1000, diameterMm: 1.78,
      from: { level: 'NORMAL', direction: 'NONE', deviationPercent: 0 },
      to: { level: 'CRITICAL', direction: 'HIGH', deviationPercent: 1.71 } }
    try {
      act(() => {
        source.backend({ deviceId: 'filamentvision-1', connection: 'CONNECTED', monitoring: 'RUNNING', eventSequence: 4,
          session: { id: 'session-1' }, latestMeasurement: measurement(1.78, 1000), alarm: alarm.to })
        source.emit({ id: 'old-reading', sessionId: 'session-1', measurement: measurement(1.78, 1000) })
        source.event({ type: 'alarm.transition', sequence: 5, deviceId: 'filamentvision-1', occurredAt: 1000, payload: alarm })
        source.event({ type: 'state.changed', sequence: 6, deviceId: 'filamentvision-1', occurredAt: 2000,
          payload: { monitoring: 'COMPLETED', session: null, activeProducerCount: 0 } })
      })
      expect(screen.getByLabelText('Current filament diameter')).toHaveTextContent('—')
      expect(screen.queryByText('Critical · High diameter')).not.toBeInTheDocument()
      act(() => source.event({ type: 'session.started', sequence: 7, deviceId: 'filamentvision-1', occurredAt: 3000,
        payload: { id: 'session-2', startedAt: 3000, endedAt: null } }))
      expect(screen.getByLabelText('Current filament diameter')).toHaveTextContent('—')
      act(() => {
        source.event({ type: 'state.changed', sequence: 8, deviceId: 'filamentvision-1', occurredAt: 3000,
          payload: { monitoring: 'RUNNING', session: { id: 'session-2' }, activeProducerCount: 1 } })
        source.emit({ id: 'new-reading', sessionId: 'session-2', measurement: measurement(1.75, 4000) })
        source.recover({ sequence: 8, complete: true, sessions: [], errors: [], alarms: [alarm],
          measurements: [{ id: 'old-reading', sessionId: 'session-1', measurement: measurement(1.78, 1000) }] })
      })
      expect(screen.getByLabelText('Current filament diameter')).toHaveTextContent('1.750')
      expect(screen.queryByText('Critical · High diameter')).not.toBeInTheDocument()
      expect(screen.getByText('Awaiting Backend alarm context')).toBeVisible()
      expect(store.getLiveSnapshot().recentBackendTrend).toHaveLength(2)
      expect(store.getLiveSnapshot().recoveredAlarms).toHaveLength(1)
    } finally { view.unmount(); await act(async () => Promise.resolve()); runtime.dispose() }
  })

  it.each(['REST snapshot', 'state.changed', 'session.started'] as const)(
    'clears previous current state when %s directly introduces a new session', async kind => {
      const { source, store, runtime, view } = createFixture()
      try {
        act(() => {
          source.backend({ deviceId: 'filamentvision-1', connection: 'CONNECTED', monitoring: 'RUNNING', eventSequence: 4,
            session: { id: 'session-1' }, latestMeasurement: measurement(1.78, 1000),
            alarm: { level: 'CRITICAL', direction: 'HIGH', deviationPercent: 1.71 } })
          source.emit({ id: 'old', sessionId: 'session-1', measurement: measurement(1.78, 1000) })
          if (kind === 'REST snapshot') source.backend({ deviceId: 'filamentvision-1', connection: 'CONNECTED', monitoring: 'RUNNING',
            eventSequence: 5, session: { id: 'session-2' }, latestMeasurement: null })
          else source.event({ type: kind, sequence: 5, deviceId: 'filamentvision-1', occurredAt: 2000,
            payload: kind === 'session.started' ? { id: 'session-2', startedAt: 2000, endedAt: null } :
              { monitoring: 'RUNNING', session: { id: 'session-2' }, activeProducerCount: 1 } })
        })
        expect(screen.getByLabelText('Current filament diameter')).toHaveTextContent('—')
        expect(screen.queryByText('Critical · High diameter')).not.toBeInTheDocument()
        expect(store.getLiveSnapshot().recentBackendTrend).toHaveLength(1)
      } finally { view.unmount(); await act(async () => Promise.resolve()); runtime.dispose() }
    },
  )

  it('retains a newer live evidence sequence when equal-time historical recovery arrives', async () => {
    const { source, store, runtime, view } = createFixture()
    const current = { ...measurement(1.75, 1000), evidence: [{ ...measurement(1.75, 1000).evidence[0], sequence: 100 }] }
    const historical = { ...measurement(1.77, 1000), evidence: [{ ...measurement(1.77, 1000).evidence[0], sequence: 1 }] }
    try {
      act(() => {
        source.backend({ deviceId: 'filamentvision-1', connection: 'CONNECTED', monitoring: 'RUNNING', eventSequence: 4,
          session: { id: 'session-1' }, latestMeasurement: null })
        source.emit({ id: 'live', sessionId: 'session-1', measurement: current })
        source.recover({ sequence: 4, complete: false, sessions: [], errors: [], alarms: [],
          measurements: [{ id: 'historical', sessionId: 'session-1', measurement: historical }] })
      })
      expect(screen.getByLabelText('Current filament diameter')).toHaveTextContent('1.750')
      expect(store.getLiveSnapshot().latestBackendReading?.evidence[0].sequence).toBe(100)
      expect(store.getLiveSnapshot().recentBackendTrend).toHaveLength(2)
    } finally { view.unmount(); await act(async () => Promise.resolve()); runtime.dispose() }
  })

  it('keeps the newest recovered alarm through more than 300 overlapping history records', async () => {
    const { source, store, runtime, view } = createFixture()
    const alarm = (id: string, timestamp: number) => ({ id, sessionId: 'session-1', timestamp, diameterMm: 1.78,
      from: { level: 'NORMAL', direction: 'NONE', deviationPercent: 0 }, to: { level: 'CRITICAL', direction: 'HIGH', deviationPercent: 1.71 } })
    try {
      act(() => {
        source.recover({ sequence: 4, complete: false, sessions: [], errors: [], measurements: [], alarms: [alarm('newest', 10_000)] })
        source.recover({ sequence: 4, complete: true, sessions: [], errors: [], measurements: [],
          alarms: [alarm('newest', 10_000), ...Array.from({ length: 400 }, (_, index) => alarm(`old-${index}`, index))] })
      })
      const records = store.getLiveSnapshot().recoveredAlarms as { id: string; timestamp: number }[]
      expect(records).toHaveLength(300)
      expect(records.some(value => value.id === 'newest')).toBe(true)
      expect(records.some(value => value.id === 'old-0')).toBe(false)
      expect(new Set(records.map(value => value.id)).size).toBe(300)
      expect(records.map(value => value.timestamp)).toEqual([...records.map(value => value.timestamp)].sort((left, right) => left - right))
      expect(records.at(-1)?.id).toBe('newest')
    } finally { view.unmount(); await act(async () => Promise.resolve()); runtime.dispose() }
  })

  it.each([['WARNING', 1.77, 'Warning'], ['CRITICAL', 1.78, 'Critical']] as const)(
    'keeps valid NORMAL quality distinct from authoritative %s severity', async (level, diameterMm, label) => {
      const { source, runtime, view } = createFixture()
      try {
        act(() => source.backend({ deviceId: 'filamentvision-1', connection: 'CONNECTED', monitoring: 'RUNNING', eventSequence: 4,
          session: { id: 'session-1' }, latestMeasurement: { ...measurement(diameterMm, 1000), status: 'NORMAL' },
          alarm: { level, direction: 'HIGH', deviationPercent: 1.7 } }))
        const panel = within(view.container.querySelector('.reading-panel') as HTMLElement)
        expect(panel.queryByText('WITHIN TOLERANCE')).not.toBeInTheDocument()
        expect(panel.queryByText('Stable')).not.toBeInTheDocument()
        expect(panel.getByText(label)).toBeVisible()
        expect(panel.getByLabelText('Measurement quality')).toHaveTextContent('NORMAL')
      } finally { view.unmount(); await act(async () => Promise.resolve()); runtime.dispose() }
    },
  )

  it('does not claim tolerance from valid measurement quality without alarm evidence', async () => {
    const { source, runtime, view } = createFixture()
    try {
      act(() => source.backend({ deviceId: 'filamentvision-1', connection: 'CONNECTED', monitoring: 'RUNNING', eventSequence: 4,
        session: { id: 'session-1' }, latestMeasurement: { ...measurement(1.78, 1000), status: 'NORMAL' } }))
      const panel = within(view.container.querySelector('.reading-panel') as HTMLElement)
      expect(panel.queryByText('WITHIN TOLERANCE')).not.toBeInTheDocument()
      expect(panel.queryByText('Stable')).not.toBeInTheDocument()
      expect(panel.getByText('Valid measurement')).toBeVisible()
    } finally { view.unmount(); await act(async () => Promise.resolve()); runtime.dispose() }
  })

  it('waits for real Backend data without an image, sample, or asserted healthy feed', async () => {
    const { source, runtime, view } = createFixture()
    try {
      expect(await screen.findByText('UDP disabled')).toBeVisible()
      expect(screen.getAllByText('Waiting for Raspberry Pi').length).toBeGreaterThan(0)
      expect(screen.queryByRole('img', { name: 'Live camera preview' })).not.toBeInTheDocument()
      expect(screen.getByLabelText('Current filament diameter')).toHaveTextContent('—')
      expect(screen.getByText('0 / 300 samples')).toBeVisible()
      expect(screen.queryByText('No abnormal events')).not.toBeInTheDocument()
      expect(screen.queryByText(/8 Hz|NOMINAL|SIMULATION|fused|dual.view|Camera A|Camera B|View difference/i)).not.toBeInTheDocument()
      act(() => source.backend({ deviceId: 'filamentvision-1', connection: 'CONNECTED', monitoring: 'RUNNING',
        eventSequence: 4, session: null, latestMeasurement: null }))
      expect(screen.getByLabelText('Current filament diameter')).toHaveTextContent('—')
    } finally {
      view.unmount()
      await act(async () => Promise.resolve())
      runtime.dispose()
    }
  })

  it('integrates one Raspberry Pi expected-input form alongside monitoring settings', async () => {
    const api = udpApiFixture()
    const user = userEvent.setup()
    const { runtime, view } = createFixture(api)
    try {
      await user.click(screen.getByRole('button', { name: 'Settings' }))
      const form = await screen.findByRole('form', { name: 'Raspberry Pi UDP Input' })
      expect(within(form).getByLabelText('UDP Listen Port')).toHaveValue(5000)
      expect(within(form).getByLabelText('Payload Format')).toHaveValue('JPEG')
      expect(screen.getByLabelText('Target diameter (mm)')).toHaveValue(1.75)
      expect(screen.queryByText('CAMERA A')).not.toBeInTheDocument()
      expect(screen.queryByText('CAMERA B')).not.toBeInTheDocument()
      expect(screen.getAllByRole('button', { name: 'Inspect Packet' })).toHaveLength(1)
    } finally {
      view.unmount()
      await act(async () => Promise.resolve())
      runtime.dispose()
    }
  })

  it('shows neutral Backend readings and one replaceable preview with continuous trend', async () => {
    const user = userEvent.setup()
    const { source, runtime, view } = createFixture()

    expect(screen.getByRole('heading', { name: 'FilamentVision' })).toBeVisible()
    act(() => source.backend({ deviceId: 'filamentvision-1', connection: 'CONNECTED', monitoring: 'RUNNING',
      eventSequence: 4, session: { id: 'session-1' }, latestMeasurement: measurement(1.75, 1000) }))
    expect(screen.getByText('RUNNING')).toBeVisible()
    expect(screen.getByLabelText('Current filament diameter')).toHaveTextContent('1.750')
    act(() => source.preview({ url: 'blob:first', width: 640, height: 480, timestamp: 1000, sourceSequence: 1 }))
    expect(screen.getByRole('img', { name: 'Live camera preview' })).toHaveAttribute('src', 'blob:first')

    emitDiameter(source, 1.75, 1_000)
    expect(screen.getByLabelText('Current filament diameter')).toHaveTextContent(
      '1.750',
    )
    expect(screen.getByText('Valid measurement')).toBeVisible()
    expect(screen.getByLabelText('Measurement confidence')).toHaveTextContent('93%')
    expect(screen.getByLabelText('Measurement captured at')).toHaveAttribute('datetime', '1970-01-01T00:00:01.000Z')

    emitDiameter(source, 1.78, 2_000)
    act(() => source.event({ type: 'alarm.transition', sequence: 5, deviceId: 'filamentvision-1', occurredAt: 2000,
      payload: { id: 'critical-alarm', sessionId: 'session-1', timestamp: 2000, diameterMm: 1.78,
        from: { level: 'NORMAL', direction: 'NONE', deviationPercent: 0 },
        to: { level: 'CRITICAL', direction: 'HIGH', deviationPercent: 1.71 } } }))
    expect(screen.getByText('Critical')).toBeVisible()
    expect(screen.getAllByText('OUT OF TOLERANCE').length).toBeGreaterThan(0)
    act(() => source.preview({ url: 'blob:second', width: 640, height: 480, timestamp: 2000, sourceSequence: 2 }))
    expect(screen.getAllByRole('img', { name: 'Live camera preview' })).toHaveLength(1)
    expect(screen.getByRole('img', { name: 'Live camera preview' })).toHaveAttribute('src', 'blob:second')
    expect(screen.getByText('2 / 300 samples')).toBeVisible()
    expect(view.container.querySelector('.trend-line')?.getAttribute('points')?.split(' ')).toHaveLength(2)

    await user.click(screen.getByRole('button', { name: 'Alarm History' }))
    expect(screen.getByRole('heading', { name: 'Alarm History' })).toBeVisible()
    await user.click(screen.getByRole('button', { name: 'Monitor' }))
    expect(screen.getByText('2 / 300 samples')).toBeVisible()
    expect(screen.getByLabelText('Current filament diameter')).toHaveTextContent('1.780')

    view.unmount()
    await act(async () => Promise.resolve())
    runtime.dispose()
  })

  it('renders durable alarm, error, session and recovered measurement context without local alarm inference', async () => {
    const user = userEvent.setup()
    const { source, runtime, view } = createFixture()
    const alarm = { id: 'alarm-1', sessionId: 'session-1', timestamp: 2000, diameterMm: 1.78,
      from: { level: 'NORMAL', direction: 'NONE', deviationPercent: 0 },
      to: { level: 'CRITICAL', direction: 'HIGH', deviationPercent: 1.71 } }
    act(() => {
      source.backend({ deviceId: 'filamentvision-1', connection: 'CONNECTED', monitoring: 'RUNNING', eventSequence: 4,
        session: { id: 'session-1' }, latestMeasurement: null })
      source.recover({ sequence: 4, complete: true, sessions: [{ id: 'session-1' }],
        measurements: [{ id: 'old', sessionId: 'session-1', measurement: measurement(1.77, 1000) }],
        alarms: [alarm], errors: [{ id: 'error-1', sessionId: 'session-1', timestamp: 1500, code: 'DECODE_FAILED', message: 'Unable to decode frame' }] })
      source.event({ type: 'state.changed', sequence: 5, deviceId: 'filamentvision-1', occurredAt: 2100,
        payload: { monitoring: 'COMPLETED', session: null, activeProducerCount: 0 } })
    })
    expect(screen.getByText('COMPLETED')).toBeVisible()
    expect(screen.getByText('Awaiting Backend alarm context')).toBeVisible()
    expect(screen.getByText(/Latest transition: CRITICAL/)).toBeVisible()
    expect(screen.getByText(/Unable to decode frame/)).toBeVisible()
    expect(screen.getByText('1 retained session')).toBeVisible()
    expect(screen.getByText('1 / 300 samples')).toBeVisible()
    emitDiameter(source, 1.75, 3000)
    expect(screen.queryByText(/Critical · High diameter/)).not.toBeInTheDocument()
    expect(screen.getByLabelText('Current filament diameter')).toHaveTextContent('—')
    await user.click(screen.getByRole('button', { name: 'Alarm History' }))
    expect(screen.getByText('High diameter')).toBeVisible()
    expect(screen.getByText('1.780 mm')).toBeVisible()
    expect(screen.queryByText('Active')).not.toBeInTheDocument()
    expect(screen.getByText('Ended')).toBeVisible()
    act(() => source.event({ type: 'alarm.transition', sequence: 6, deviceId: 'filamentvision-1', occurredAt: 4000,
      payload: { ...alarm, id: 'alarm-2', timestamp: 4000, diameterMm: 1.75, from: alarm.to,
        to: { level: 'NORMAL', direction: 'NONE', deviationPercent: 0 } } }))
    expect(screen.getByText('Recovered')).toBeVisible()
    expect(screen.getAllByRole('listitem')).toHaveLength(2)
    await user.click(screen.getByRole('button', { name: 'Monitor' }))
    expect(screen.getByText(/Recovered ·/)).toBeVisible()
    expect(screen.getByText('2 / 300 samples')).toBeVisible()
    view.unmount(); await act(async () => Promise.resolve()); runtime.dispose()
  })

  it('keeps current snapshot alarm state ahead of older recovered history', async () => {
    const user = userEvent.setup()
    const { source, runtime, view } = createFixture()
    act(() => {
      source.backend({ deviceId: 'filamentvision-1', connection: 'CONNECTED', monitoring: 'RUNNING', eventSequence: 10,
        session: { id: 'current' }, latestMeasurement: measurement(1.75, 5000),
        alarm: { level: 'NORMAL', direction: 'NONE', deviationPercent: 0 } })
      source.recover({ sequence: 10, complete: true, sessions: [{ id: 'old' }], measurements: [], errors: [],
        alarms: [{ id: 'old-alarm', sessionId: 'current', timestamp: 1000, diameterMm: 1.78,
          from: { level: 'NORMAL', direction: 'NONE', deviationPercent: 0 },
          to: { level: 'CRITICAL', direction: 'HIGH', deviationPercent: 1.71 } }] })
    })
    expect(screen.queryByText('Critical · High diameter')).not.toBeInTheDocument()
    expect(within(view.container.querySelector('.alarm-summary') as HTMLElement).getByText('Within limits')).toBeVisible()
    await user.click(screen.getByRole('button', { name: 'Alarm History' }))
    expect(screen.queryByText('Active')).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Monitor' }))
    act(() => source.event({ type: 'alarm.transition', sequence: 5, deviceId: 'filamentvision-1', occurredAt: 1000,
      payload: { id: 'replayed-alarm', sessionId: 'old', timestamp: 1000, diameterMm: 1.78,
        from: { level: 'NORMAL', direction: 'NONE', deviationPercent: 0 },
        to: { level: 'CRITICAL', direction: 'HIGH', deviationPercent: 1.71 } } }))
    expect(within(view.container.querySelector('.alarm-summary') as HTMLElement).getByText('Within limits')).toBeVisible()
    act(() => source.event({ type: 'alarm.transition', sequence: 11, deviceId: 'filamentvision-1', occurredAt: 6000,
      payload: { id: 'new-alarm', sessionId: 'current', timestamp: 6000, diameterMm: 1.77,
        from: { level: 'NORMAL', direction: 'NONE', deviationPercent: 0 },
        to: { level: 'WARNING', direction: 'HIGH', deviationPercent: 1.14 } } }))
    expect(screen.getByText('Warning · High diameter')).toBeVisible()
    view.unmount(); await act(async () => Promise.resolve()); runtime.dispose()
  })

  it('adds older recovered readings to trend without replacing the current measurement', async () => {
    const { source, runtime, view } = createFixture()
    act(() => {
      source.backend({ deviceId: 'filamentvision-1', connection: 'CONNECTED', monitoring: 'RUNNING', eventSequence: 10,
        session: { id: 'current' }, latestMeasurement: measurement(1.75, 5000) })
      source.recover({ sequence: 10, complete: false, sessions: [], errors: [], alarms: [],
        measurements: [{ id: 'old-reading', sessionId: 'old', measurement: measurement(1.77, 1000) }] })
    })
    expect(screen.getByLabelText('Current filament diameter')).toHaveTextContent('1.750')
    expect(screen.getByText('1 / 300 samples')).toBeVisible()
    view.unmount(); await act(async () => Promise.resolve()); runtime.dispose()
  })

  it('orders alarm transitions sharing a timestamp by arrival and does not revive recovered episodes', async () => {
    const user = userEvent.setup()
    const { source, runtime, view } = createFixture()
    const normal = { level: 'NORMAL', direction: 'NONE', deviationPercent: 0 }
    const warning = { level: 'WARNING', direction: 'HIGH', deviationPercent: 1.14 }
    act(() => {
      source.backend({ deviceId: 'filamentvision-1', connection: 'CONNECTED', monitoring: 'RUNNING', eventSequence: 4,
        session: { id: 'session-1' }, latestMeasurement: measurement(1.75, 1000), alarm: normal })
      source.event({ type: 'alarm.transition', sequence: 5, deviceId: 'filamentvision-1', occurredAt: 1000,
        payload: { id: 'warning', sessionId: 'session-1', timestamp: 1000, diameterMm: 1.77, from: normal, to: warning } })
      source.event({ type: 'alarm.transition', sequence: 6, deviceId: 'filamentvision-1', occurredAt: 1000,
        payload: { id: 'recovery', sessionId: 'session-1', timestamp: 1000, diameterMm: 1.75, from: warning, to: normal } })
    })
    expect(screen.getByText(/Recovered ·/)).toBeVisible()
    await user.click(screen.getByRole('button', { name: 'Alarm History' }))
    expect(screen.queryByText('Active')).not.toBeInTheDocument()
    expect(within(screen.getAllByRole('listitem')[0]).getByText('Recovered')).toBeVisible()
    view.unmount(); await act(async () => Promise.resolve()); runtime.dispose()
  })

  it('validates and applies locally stored settings', async () => {
    const user = userEvent.setup()
    const { storage, runtime, view } = createFixture()
    await user.click(screen.getByRole('button', { name: 'Settings' }))

    const targetInput = screen.getByLabelText('Target diameter (mm)')
    const warningInput = screen.getByLabelText('Warning deviation (mm)')
    const outOfToleranceInput = screen.getByLabelText(
      'Out-of-tolerance deviation (mm)',
    )
    await user.clear(warningInput)
    await user.type(warningInput, '0.030')
    expect(
      screen.getByText(
        'Warning deviation must be lower than out-of-tolerance deviation.',
      ),
    ).toBeVisible()
    expect(screen.getByRole('button', { name: 'Save settings' })).toBeDisabled()

    await user.clear(targetInput)
    await user.type(targetInput, '2.85')
    await user.clear(warningInput)
    await user.type(warningInput, '0.020')
    await user.clear(outOfToleranceInput)
    await user.type(outOfToleranceInput, '0.040')
    await user.click(screen.getByRole('button', { name: 'Save settings' }))

    expect(storage.configuration.targetDiameterMm).toBe(2.85)
    await user.click(screen.getByRole('button', { name: 'Monitor' }))
    expect(screen.getByText('2.850 mm')).toBeVisible()

    view.unmount()
    await act(async () => Promise.resolve())
    runtime.dispose()
  })
})

function measurement(diameterMm: number, timestamp: number): BackendMeasurement {
  return { evidence: [{ cameraId: 'camera', timestamp, sequence: timestamp, pixelWidth: 100,
    diameterMm, confidence: 0.93, valid: true, source: 'VISION' }], diameterMm, confidence: 0.93, timestamp, status: 'NORMAL' }
}

function udpApiFixture(): BackendApiClient {
  // Keep App/Settings real; isolate only the remote API boundary to deterministic data.
  return new class extends BackendApiClient {
    override async getRaspberryPiUdpConfiguration(): Promise<UdpConfigurationState> {
      return udpState()
    }

    override async getRaspberryPiUdpDiagnostics(): Promise<UdpDiagnostics> {
      return { ...udpDiagnostics, status: 'DISABLED' }
    }

    override async getNetworkInterfaces(): Promise<NetworkInterfaces> {
      return networkInterfaces
    }
  }()
}
