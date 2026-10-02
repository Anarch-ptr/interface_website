import { act, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'

import App from './App'
import {
  DEFAULT_MONITORING_CONFIGURATION,
  type ConfigurationStorage,
} from './config/monitoringConfig'
import { createDiameterReading } from './domain/measurement'
import {
  VisionSourceState,
  type DiameterReading,
  type MonitoringConfiguration,
} from './domain/monitoring'
import type {
  FilamentDataSource,
  ReadingListener,
  SourceStateListener,
} from './services/FilamentDataSource'
import { MonitoringRuntime } from './state/MonitoringRuntime'
import { MonitoringStore } from './state/MonitoringStore'

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

  updateConfiguration(): void {}

  stop(): void {
    this.setState(VisionSourceState.STOPPED)
  }

  dispose(): void {
    this.stop()
    this.readingListeners.clear()
    this.stateListeners.clear()
  }

  emit(reading: DiameterReading): void {
    for (const listener of this.readingListeners) listener(reading)
  }

  private setState(state: VisionSourceState): void {
    this.state = state
    for (const listener of this.stateListeners) listener(state)
  }
}

function createFixture() {
  const source = new TestSource()
  const storage = new MemoryConfigurationStorage()
  const store = new MonitoringStore(source, storage)
  const runtime = new MonitoringRuntime(store)
  const view = render(<App store={store} runtime={runtime} />)
  return { source, storage, store, runtime, view }
}

function emitDiameter(
  source: TestSource,
  diameterMm: number,
  timestamp: number,
): void {
  act(() => {
    source.emit(
      createDiameterReading({
        fusedDiameterMm: diameterMm,
        configuration: DEFAULT_MONITORING_CONFIGURATION,
        timestamp,
        diameterAMm: diameterMm - 0.001,
        diameterBMm: diameterMm + 0.001,
        cameraAConfidence: 0.98,
        cameraBConfidence: 0.97,
      }),
    )
  })
}

describe('FilamentVision application', () => {
  it('presents live system health and records out-of-tolerance alarms as Critical', async () => {
    const user = userEvent.setup()
    const { source, runtime, view } = createFixture()

    expect(screen.getByRole('heading', { name: 'FilamentVision' })).toBeVisible()
    expect(screen.getByText('CONNECTED')).toBeVisible()
    expect(screen.getByText('MONITORING')).toBeVisible()
    expect(screen.getByText('1.750 mm')).toBeVisible()

    emitDiameter(source, 1.75, 1_000)
    expect(screen.getByLabelText('Current filament diameter')).toHaveTextContent(
      '1.750',
    )
    expect(screen.getByText('Stable')).toBeVisible()

    emitDiameter(source, 1.78, 2_000)
    expect(screen.getByText('Critical')).toBeVisible()
    expect(screen.getByText('OUT OF TOLERANCE')).toBeVisible()

    await user.click(screen.getByRole('button', { name: 'Alarm History' }))
    expect(screen.getByRole('heading', { name: 'Alarm History' })).toBeVisible()
    expect(screen.getByText('High diameter')).toBeVisible()
    expect(screen.getByText('+0.030 mm')).toBeVisible()

    view.unmount()
    await act(async () => Promise.resolve())
    runtime.dispose()
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
