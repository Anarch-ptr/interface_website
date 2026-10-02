import { describe, expect, it, vi } from 'vitest'

import {
  DEFAULT_MONITORING_CONFIGURATION,
  type ConfigurationStorage,
} from '../config/monitoringConfig'
import { createDiameterReading } from '../domain/measurement'
import {
  MeasurementStatus,
  VisionSourceState,
  type DiameterReading,
  type MonitoringConfiguration,
} from '../domain/monitoring'
import type {
  FilamentDataSource,
  ReadingListener,
  SourceStateListener,
} from '../services/FilamentDataSource'
import { MonitoringStore } from './MonitoringStore'

class MemoryConfigurationStorage implements ConfigurationStorage {
  configuration = { ...DEFAULT_MONITORING_CONFIGURATION }
  saveCount = 0

  load(): MonitoringConfiguration {
    return { ...this.configuration }
  }

  save(configuration: MonitoringConfiguration): void {
    this.configuration = { ...configuration }
    this.saveCount += 1
  }

  reset(): MonitoringConfiguration {
    this.configuration = { ...DEFAULT_MONITORING_CONFIGURATION }
    return this.load()
  }
}

class TestFilamentDataSource implements FilamentDataSource {
  state: VisionSourceState = VisionSourceState.STOPPED
  readonly readingListeners = new Set<ReadingListener>()
  readonly stateListeners = new Set<SourceStateListener>()
  configuration: MonitoringConfiguration | null = null
  startCount = 0
  stopCount = 0

  subscribe(listener: ReadingListener): () => void {
    this.readingListeners.add(listener)
    return () => this.readingListeners.delete(listener)
  }

  subscribeState(listener: SourceStateListener): () => void {
    this.stateListeners.add(listener)
    return () => this.stateListeners.delete(listener)
  }

  start(configuration: MonitoringConfiguration): void {
    this.configuration = { ...configuration }
    this.startCount += 1
    this.setState(VisionSourceState.LIVE)
  }

  updateConfiguration(configuration: MonitoringConfiguration): void {
    this.configuration = { ...configuration }
  }

  stop(): void {
    this.stopCount += 1
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

function reading(diameterMm: number, timestamp: number): DiameterReading {
  return createDiameterReading({
    fusedDiameterMm: diameterMm,
    configuration: DEFAULT_MONITORING_CONFIGURATION,
    timestamp,
  })
}

function createFixture() {
  const source = new TestFilamentDataSource()
  const configurationStorage = new MemoryConfigurationStorage()
  const store = new MonitoringStore(source, configurationStorage)
  store.start()
  return { source, configurationStorage, store }
}

describe('MonitoringStore', () => {
  it('keeps Recent Trend bounded to the newest 300 readings', () => {
    const { source, store } = createFixture()

    for (let index = 1; index <= 301; index += 1) {
      source.emit(reading(1.75, index))
    }

    const trend = store.getLiveSnapshot().recentTrend
    expect(trend).toHaveLength(300)
    expect(trend[0]?.timestamp).toBe(2)
    expect(trend.at(-1)?.timestamp).toBe(301)

    store.dispose()
  })

  it('opens, escalates, updates, and recovers alarm episodes', () => {
    const { source, store } = createFixture()

    source.emit(reading(1.77, 10))
    source.emit(reading(1.772, 11))
    let alarms = store.getAlarmSnapshot().events
    expect(alarms).toHaveLength(1)
    expect(alarms[0]?.status).toBe(MeasurementStatus.WARNING)
    expect(alarms[0]?.peakReading.fusedDiameterMm).toBeCloseTo(1.772, 3)
    expect(alarms[0]?.recoveredAt).toBeNull()

    source.emit(reading(1.78, 12))
    alarms = store.getAlarmSnapshot().events
    expect(alarms).toHaveLength(2)
    expect(alarms[0]?.status).toBe(MeasurementStatus.OUT_OF_TOLERANCE)
    expect(alarms[1]?.recoveredAt).toBe(12)

    source.emit(reading(1.75, 13))
    alarms = store.getAlarmSnapshot().events
    expect(alarms[0]?.recoveredAt).toBe(13)
    expect(store.getAlarmSnapshot().activeEventId).toBeNull()

    store.dispose()
  })

  it('bounds Alarm History to the newest 300 episodes', () => {
    const { source, store } = createFixture()

    for (let index = 0; index < 301; index += 1) {
      source.emit(reading(1.77, index * 2))
      source.emit(reading(1.75, index * 2 + 1))
    }

    const events = store.getAlarmSnapshot().events
    expect(events).toHaveLength(300)
    expect(events[0]?.startedAt).toBe(600)
    expect(events.at(-1)?.startedAt).toBe(2)

    store.dispose()
  })

  it('keeps high-frequency live notifications isolated from configuration', () => {
    const { source, configurationStorage, store } = createFixture()
    const liveListener = vi.fn()
    const configurationListener = vi.fn()
    store.subscribeLive(liveListener)
    store.subscribeConfiguration(configurationListener)

    source.emit(reading(1.75, 1))

    expect(liveListener).toHaveBeenCalledTimes(1)
    expect(configurationListener).not.toHaveBeenCalled()

    const updated = {
      ...DEFAULT_MONITORING_CONFIGURATION,
      targetDiameterMm: 2.85,
      warningDeviationMm: 0.02,
      outOfToleranceDeviationMm: 0.04,
    }
    expect(store.updateConfiguration(updated).valid).toBe(true)
    expect(configurationListener).toHaveBeenCalledTimes(1)
    expect(configurationStorage.saveCount).toBe(1)
    expect(source.configuration).toEqual(updated)

    store.dispose()
  })
})
