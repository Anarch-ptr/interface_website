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
  type BackendState, type BackendEvent,
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
  readonly backendListeners = new Set<(value: BackendState) => void>()
  readonly eventListeners = new Set<(value: BackendEvent) => void>()
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

  subscribeBackendState(listener: (value: BackendState) => void) { this.backendListeners.add(listener); return () => this.backendListeners.delete(listener) }
  subscribeEvents(listener: (value: BackendEvent) => void) { this.eventListeners.add(listener); return () => this.eventListeners.delete(listener) }
  backend(value: BackendState) { for (const listener of this.backendListeners) listener(value) }
  event(value: BackendEvent) { for (const listener of this.eventListeners) listener(value) }

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
    diameterMm,
    confidence: 1,
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
  it('retains authoritative state after its reliable event leaves the bounded event list', () => {
    const { source, store } = createFixture()
    source.backend({ deviceId: 'filamentvision-1', connection: 'CONNECTED', monitoring: 'RUNNING',
      eventSequence: 4, session: { id: 'session-1' }, latestMeasurement: null })
    source.event({ type: 'state.changed', sequence: 5, deviceId: 'filamentvision-1', occurredAt: 1000,
      payload: { monitoring: 'COMPLETED', session: null, activeProducerCount: 0 } })
    for (let sequence = 6; sequence < 307; sequence++) source.event({ type: 'measurement.sample', sequence,
      deviceId: 'filamentvision-1', occurredAt: sequence, payload: {} })
    expect(store.getLiveSnapshot().reliableEvents).toHaveLength(300)
    expect(store.getLiveSnapshot().backendState).toMatchObject({ monitoring: 'COMPLETED', session: null })
    store.dispose()
  })

  it('retains session lifecycle context independently of the bounded reliable event list', () => {
    const { source, store } = createFixture()
    source.backend({ deviceId: 'filamentvision-1', connection: 'CONNECTED', monitoring: 'RUNNING',
      eventSequence: 4, session: null, latestMeasurement: null })
    source.event({ type: 'session.started', sequence: 5, deviceId: 'filamentvision-1', occurredAt: 1000,
      payload: { id: 'session-1', startedAt: 1000, endedAt: null } })
    expect(store.getLiveSnapshot().backendState?.session?.id).toBe('session-1')
    source.event({ type: 'session.finalized', sequence: 6, deviceId: 'filamentvision-1', occurredAt: 2000,
      payload: { id: 'session-1', startedAt: 1000, endedAt: 2000 } })
    expect(store.getLiveSnapshot().backendState?.session).toBeNull()
    expect(store.getLiveSnapshot().recoveredSessions).toEqual([{ id: 'session-1', startedAt: 1000, endedAt: 2000 }])
    store.dispose()
  })

  it('retains a newer REST checkpoint while older lifecycle events replay', () => {
    const { source, store } = createFixture()
    source.backend({ deviceId: 'filamentvision-1', connection: 'CONNECTED', monitoring: 'COMPLETED',
      eventSequence: 10, session: null, latestMeasurement: null })
    source.event({ type: 'state.changed', sequence: 5, deviceId: 'filamentvision-1', occurredAt: 1000,
      payload: { monitoring: 'RUNNING', session: { id: 'old' }, activeProducerCount: 1 } })
    source.event({ type: 'session.started', sequence: 6, deviceId: 'filamentvision-1', occurredAt: 1000,
      payload: { id: 'old', startedAt: 1000, endedAt: null } })
    expect(store.getLiveSnapshot().backendState).toMatchObject({ monitoring: 'COMPLETED', session: null, eventSequence: 10 })
    expect(store.getLiveSnapshot().recoveredSessions).toEqual([{ id: 'old', startedAt: 1000, endedAt: null }])
    store.dispose()
  })
  it('starts and stops each legacy fixture once per explicit lifecycle command', () => {
    const { source, store } = createFixture()
    expect(source.startCount).toBe(1)
    store.stop()
    expect(source.stopCount).toBe(1)
    store.dispose()
  })
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
    expect(alarms[0]?.peakReading.diameterMm).toBeCloseTo(1.772, 3)
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
