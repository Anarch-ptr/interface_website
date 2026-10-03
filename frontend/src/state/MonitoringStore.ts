import {
  validateMonitoringConfiguration,
  type ConfigurationStorage,
  type ConfigurationValidationResult,
} from '../config/monitoringConfig'
import {
  AlarmDirection,
  ConnectionState,
  MeasurementStatus,
  MonitoringState,
  VisionSourceState,
  type AlarmEvent,
  type DiameterReading,
  type MonitoringConfiguration,
  type BackendMeasurement, type BackendMeasurementRecord, type BackendEvent,
  type BackendRecovery, type BackendState, type CameraPreview,
  type BackendSession,
  backendAlarms,
} from '../domain/monitoring'
import type { FilamentDataSource, Unsubscribe } from '../services/FilamentDataSource'

type StoreListener = () => void

export interface LiveMonitoringSnapshot {
  latestReading: DiameterReading | null
  recentTrend: readonly DiameterReading[]
  connectionState: ConnectionState
  visionSourceState: VisionSourceState
  monitoringState: MonitoringState
  latestBackendReading: BackendMeasurement | null
  recentBackendTrend: readonly BackendMeasurementRecord[]
  backendState: BackendState | null
  preview: CameraPreview | null
  reliableEvents: readonly BackendEvent[]
  recoveredAlarms: readonly unknown[]
  recoveredErrors: readonly unknown[]
  recoveredSessions: readonly { id: string }[]
}

export interface AlarmHistorySnapshot {
  events: readonly AlarmEvent[]
  activeEventId: string | null
}

export interface ConfigurationSnapshot {
  configuration: Readonly<MonitoringConfiguration>
}

const ALARM_HISTORY_CAPACITY = 300

export class MonitoringStore {
  private readonly source: FilamentDataSource
  private readonly configurationStorage: ConfigurationStorage
  private readonly liveListeners = new Set<StoreListener>()
  private readonly alarmListeners = new Set<StoreListener>()
  private readonly configurationListeners = new Set<StoreListener>()
  private sourceUnsubscribe: Unsubscribe | null = null
  private sourceStateUnsubscribe: Unsubscribe | null = null
  private backendUnsubscribes: Unsubscribe[] = []
  private started = false
  private disposed = false
  private alarmSequence = 0
  private activeEventId: string | null = null
  private configuration: MonitoringConfiguration
  private liveSnapshot: LiveMonitoringSnapshot
  private alarmSnapshot: AlarmHistorySnapshot = {
    events: Object.freeze([]),
    activeEventId: null,
  }
  private configurationSnapshot: ConfigurationSnapshot

  constructor(
    source: FilamentDataSource,
    configurationStorage: ConfigurationStorage,
  ) {
    this.source = source
    this.configurationStorage = configurationStorage
    this.configuration = configurationStorage.load()
    this.configurationSnapshot = {
      configuration: Object.freeze({ ...this.configuration }),
    }
    this.liveSnapshot = {
      latestReading: null,
      recentTrend: Object.freeze([]),
      connectionState: ConnectionState.DISCONNECTED,
      visionSourceState: VisionSourceState.STOPPED,
      monitoringState: MonitoringState.IDLE,
      latestBackendReading: null, recentBackendTrend: Object.freeze([]), backendState: null,
      preview: null, reliableEvents: Object.freeze([]), recoveredAlarms: Object.freeze([]),
      recoveredErrors: Object.freeze([]), recoveredSessions: Object.freeze([]),
    }
  }

  get diagnostics() {
    return {
      liveSubscriberCount: this.liveListeners.size,
      alarmSubscriberCount: this.alarmListeners.size,
      configurationSubscriberCount: this.configurationListeners.size,
      sourceSubscriptionActive:
        this.sourceUnsubscribe !== null && this.sourceStateUnsubscribe !== null,
    } as const
  }

  subscribeLive = (listener: StoreListener): Unsubscribe => {
    this.liveListeners.add(listener)
    return () => this.liveListeners.delete(listener)
  }

  subscribeAlarms = (listener: StoreListener): Unsubscribe => {
    this.alarmListeners.add(listener)
    return () => this.alarmListeners.delete(listener)
  }

  subscribeConfiguration = (listener: StoreListener): Unsubscribe => {
    this.configurationListeners.add(listener)
    return () => this.configurationListeners.delete(listener)
  }

  getLiveSnapshot = (): LiveMonitoringSnapshot => this.liveSnapshot

  getAlarmSnapshot = (): AlarmHistorySnapshot => this.alarmSnapshot

  getConfigurationSnapshot = (): ConfigurationSnapshot =>
    this.configurationSnapshot

  start(): void | Promise<void> {
    this.observe()
    if (this.source.observe) return this.source.start(this.configuration)
  }

  observe(): void {
    if (this.disposed) throw new Error('A disposed monitoring store cannot restart.')
    if (this.started) return
    this.started = true
    this.replaceLiveSnapshot({
      connectionState: ConnectionState.CONNECTING,
      monitoringState: MonitoringState.STARTING,
    })
    this.sourceUnsubscribe = this.source.subscribe((reading) =>
      this.handleReading(reading),
    )
    this.sourceStateUnsubscribe = this.source.subscribeState((state) =>
      this.handleSourceState(state),
    )
    if (this.source.subscribeMeasurement) this.backendUnsubscribes.push(this.source.subscribeMeasurement(value => this.handleBackendMeasurement(value)))
    if (this.source.subscribeBackendState) this.backendUnsubscribes.push(this.source.subscribeBackendState(value => {
      const state = hasCurrentSession(value) ? value : { ...value, latestMeasurement: null, alarm: undefined }
      this.replaceLiveSnapshot({ backendState: state, latestBackendReading: state.latestMeasurement })
    }))
    if (this.source.subscribePreview) this.backendUnsubscribes.push(this.source.subscribePreview(value => this.replaceLiveSnapshot({ preview: value })))
    if (this.source.subscribeEvents) this.backendUnsubscribes.push(this.source.subscribeEvents(value => this.handleBackendEvent(value)))
    if (this.source.subscribeRecovery) this.backendUnsubscribes.push(this.source.subscribeRecovery(value => this.handleRecovery(value)))
    if (this.source.observe) this.source.observe(this.configuration)
    else this.source.start(this.configuration) // Legacy deterministic test sources only.
  }

  stop(): void | Promise<void> {
    if (this.source.unobserve) return this.source.stop()
    this.unobserve()
  }

  unobserve(): void {
    if (!this.started) return
    this.started = false
    this.sourceUnsubscribe?.()
    this.sourceStateUnsubscribe?.()
    this.sourceUnsubscribe = null
    this.sourceStateUnsubscribe = null
    for (const unsubscribe of this.backendUnsubscribes) unsubscribe()
    this.backendUnsubscribes = []
    if (this.source.unobserve) this.source.unobserve()
    else this.source.stop() // Legacy deterministic test sources only.
    this.recoverActiveAlarm(Date.now())
    this.liveSnapshot = {
      ...this.liveSnapshot,
      latestReading: null,
      recentTrend: Object.freeze([]),
      connectionState: ConnectionState.DISCONNECTED,
      visionSourceState: VisionSourceState.STOPPED,
      monitoringState: MonitoringState.IDLE,
      preview: null,
    }
    this.notify(this.liveListeners)
  }

  dispose(): void {
    if (this.disposed) return
    this.unobserve()
    this.disposed = true
    this.source.dispose()
    this.liveListeners.clear()
    this.alarmListeners.clear()
    this.configurationListeners.clear()
  }

  updateConfiguration(
    configuration: MonitoringConfiguration,
  ): ConfigurationValidationResult {
    const validation = validateMonitoringConfiguration(configuration)
    if (!validation.valid) return validation

    this.configuration = { ...configuration }
    this.configurationStorage.save(this.configuration)
    this.source.updateConfiguration(this.configuration)
    this.configurationSnapshot = {
      configuration: Object.freeze({ ...this.configuration }),
    }
    this.notify(this.configurationListeners)
    return validation
  }

  resetConfiguration(): void {
    this.configuration = this.configurationStorage.reset()
    this.source.updateConfiguration(this.configuration)
    this.configurationSnapshot = {
      configuration: Object.freeze({ ...this.configuration }),
    }
    this.notify(this.configurationListeners)
  }

  private handleReading(reading: DiameterReading): void {
    const recentTrend = [
      ...this.liveSnapshot.recentTrend,
      Object.freeze({ ...reading }),
    ].slice(-this.configuration.recentTrendCapacity)
    this.liveSnapshot = {
      ...this.liveSnapshot,
      latestReading: recentTrend.at(-1) ?? null,
      recentTrend: Object.freeze(recentTrend),
    }
    this.notify(this.liveListeners)
    this.updateAlarmEpisode(reading)
  }

  private handleBackendMeasurement(value: BackendMeasurementRecord, historical = false): void {
    const readings = this.liveSnapshot.recentBackendTrend.filter(reading => reading.id !== value.id)
    readings.push(value)
    readings.sort((left, right) => left.measurement.timestamp - right.measurement.timestamp)
    const trend = readings.slice(-this.configuration.recentTrendCapacity)
    const current = this.liveSnapshot.latestBackendReading
    const state = this.liveSnapshot.backendState
    const latest = state && hasCurrentSession(state) && value.sessionId === state.session?.id &&
      (!current || value.measurement.timestamp > current.timestamp ||
        (!historical && value.measurement.timestamp === current.timestamp)) ? value.measurement : current
    this.replaceLiveSnapshot({ recentBackendTrend: Object.freeze(trend),
      latestBackendReading: latest })
  }

  private handleRecovery(value: BackendRecovery): void {
    for (const reading of value.measurements) this.handleBackendMeasurement(reading, true)
    this.replaceLiveSnapshot({ recoveredAlarms: mergeRecords(this.liveSnapshot.recoveredAlarms, value.alarms, true),
      recoveredErrors: mergeRecords(this.liveSnapshot.recoveredErrors, value.errors),
      recoveredSessions: mergeRecords(this.liveSnapshot.recoveredSessions, value.sessions) })
  }

  private handleBackendEvent(value: BackendEvent): void {
    const changes: Partial<LiveMonitoringSnapshot> = {
      reliableEvents: Object.freeze([...this.liveSnapshot.reliableEvents, value].slice(-300)),
    }
    const state = this.liveSnapshot.backendState
    const payload = value.payload
    if (state && value.sequence > state.eventSequence && payload && typeof payload === 'object') {
      if (value.type === 'state.changed' && 'monitoring' in payload && typeof payload.monitoring === 'string') {
        changes.backendState = { ...state, eventSequence: value.sequence, monitoring: payload.monitoring }
        if ('session' in payload && (payload.session === null || isBackendSession(payload.session))) {
          changes.backendState.session = payload.session
        }
      }
      if (value.type === 'session.started' && isBackendSession(payload)) {
        changes.backendState = { ...state, eventSequence: value.sequence, session: payload }
      }
      if (value.type === 'session.finalized' && isBackendSession(payload) && payload.id === state.session?.id) {
        changes.backendState = { ...state, eventSequence: value.sequence, session: null }
      }
    }
    if (state && changes.backendState && (changes.backendState.session?.id !== state.session?.id ||
      !hasCurrentSession(changes.backendState) ||
      (changes.backendState.monitoring === 'RUNNING' && !hasCurrentSession(state)))) {
      changes.backendState = { ...changes.backendState, latestMeasurement: null, alarm: undefined }
      changes.latestBackendReading = null
    }
    if (value.type === 'alarm.transition') {
      changes.recoveredAlarms = mergeRecords(this.liveSnapshot.recoveredAlarms, [value.payload], true)
      const alarm = backendAlarms([value.payload])[0]
      if (state && hasCurrentSession(state) && alarm && alarm.sessionId === state.session?.id &&
        value.sequence > state.eventSequence) changes.backendState = { ...state, eventSequence: value.sequence, alarm: alarm.to }
    }
    if (value.type === 'error.raised') changes.recoveredErrors = mergeRecords(this.liveSnapshot.recoveredErrors, [value.payload])
    if ((value.type === 'session.started' || value.type === 'session.finalized') && value.payload &&
      typeof value.payload === 'object' && 'id' in value.payload && typeof value.payload.id === 'string') {
      changes.recoveredSessions = mergeRecords(this.liveSnapshot.recoveredSessions, [value.payload as { id: string }])
    }
    this.replaceLiveSnapshot(changes)
  }

  private handleSourceState(state: VisionSourceState): void {
    if (state === VisionSourceState.LIVE) {
      this.replaceLiveSnapshot({
        visionSourceState: state,
        connectionState: ConnectionState.CONNECTED,
        monitoringState: MonitoringState.MONITORING,
      })
      return
    }
    if (state === VisionSourceState.STARTING) {
      this.replaceLiveSnapshot({
        visionSourceState: state,
        connectionState: ConnectionState.CONNECTING,
        monitoringState: MonitoringState.STARTING,
      })
      return
    }
    if (state === VisionSourceState.ERROR) {
      this.replaceLiveSnapshot({
        visionSourceState: state,
        connectionState: ConnectionState.ERROR,
        monitoringState: MonitoringState.INTERRUPTED,
      })
      return
    }
    this.replaceLiveSnapshot({
      visionSourceState: state,
      connectionState: ConnectionState.DISCONNECTED,
      monitoringState: MonitoringState.IDLE,
    })
  }

  private updateAlarmEpisode(reading: DiameterReading): void {
    if (!isDiameterAlarmStatus(reading.status)) {
      this.recoverActiveAlarm(reading.timestamp)
      return
    }

    const direction =
      reading.deviationMm >= 0 ? AlarmDirection.HIGH : AlarmDirection.LOW
    const activeEvent = this.alarmSnapshot.events.find(
      (event) => event.id === this.activeEventId,
    )
    if (
      activeEvent !== undefined &&
      activeEvent.status === reading.status &&
      activeEvent.direction === direction
    ) {
      if (
        reading.absoluteDeviationMm >
        activeEvent.peakReading.absoluteDeviationMm
      ) {
        const events = this.alarmSnapshot.events.map((event) =>
          event.id === activeEvent.id
            ? Object.freeze({ ...event, peakReading: Object.freeze({ ...reading }) })
            : event,
        )
        this.publishAlarms(events, activeEvent.id)
      }
      return
    }

    this.recoverActiveAlarm(reading.timestamp)
    this.alarmSequence += 1
    const event: AlarmEvent = Object.freeze({
      id: `alarm-${reading.timestamp}-${this.alarmSequence}`,
      startedAt: reading.timestamp,
      recoveredAt: null,
      status: reading.status,
      direction,
      initialReading: Object.freeze({ ...reading }),
      peakReading: Object.freeze({ ...reading }),
      acknowledgedAt: null,
    })
    this.publishAlarms(
      [event, ...this.alarmSnapshot.events].slice(0, ALARM_HISTORY_CAPACITY),
      event.id,
    )
  }

  private recoverActiveAlarm(timestamp: number): void {
    if (this.activeEventId === null) return
    const activeEventId = this.activeEventId
    const events = this.alarmSnapshot.events.map((event) =>
      event.id === activeEventId && event.recoveredAt === null
        ? Object.freeze({ ...event, recoveredAt: timestamp })
        : event,
    )
    this.publishAlarms(events, null)
  }

  private publishAlarms(
    events: readonly AlarmEvent[],
    activeEventId: string | null,
  ): void {
    this.activeEventId = activeEventId
    this.alarmSnapshot = {
      events: Object.freeze([...events]),
      activeEventId,
    }
    this.notify(this.alarmListeners)
  }

  private replaceLiveSnapshot(
    changes: Partial<LiveMonitoringSnapshot>,
  ): void {
    this.liveSnapshot = { ...this.liveSnapshot, ...changes }
    this.notify(this.liveListeners)
  }

  private notify(listeners: ReadonlySet<StoreListener>): void {
    for (const listener of listeners) listener()
  }
}

function isBackendSession(value: unknown): value is BackendSession {
  return !!value && typeof value === 'object' && 'id' in value && typeof value.id === 'string'
}

function hasCurrentSession(state: BackendState): boolean {
  return state.session !== null && !['IDLE', 'COMPLETED', 'ERROR'].includes(state.monitoring)
}

function mergeRecords<T>(current: readonly T[], incoming: readonly T[], chronological = false): readonly T[] {
  const records = new Map<unknown, T>()
  for (const value of [...current, ...incoming]) {
    const id = value && typeof value === 'object' && 'id' in value ? value.id : value
    records.set(id, value)
  }
  const ordered = [...records.values()]
  if (chronological) ordered.sort((left, right) => recordTimestamp(left) - recordTimestamp(right))
  return Object.freeze(ordered.slice(-300))
}

function recordTimestamp(value: unknown): number {
  return value && typeof value === 'object' && 'timestamp' in value && typeof value.timestamp === 'number'
    ? value.timestamp : 0
}

function isDiameterAlarmStatus(
  status: string,
): status is AlarmEvent['status'] {
  return (
    status === MeasurementStatus.WARNING ||
    status === MeasurementStatus.OUT_OF_TOLERANCE
  )
}
