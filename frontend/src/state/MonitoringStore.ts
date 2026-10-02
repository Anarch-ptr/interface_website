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
} from '../domain/monitoring'
import type { FilamentDataSource, Unsubscribe } from '../services/FilamentDataSource'

type StoreListener = () => void

export interface LiveMonitoringSnapshot {
  latestReading: DiameterReading | null
  recentTrend: readonly DiameterReading[]
  connectionState: ConnectionState
  visionSourceState: VisionSourceState
  monitoringState: MonitoringState
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

  start(): void {
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
    this.source.start(this.configuration)
  }

  stop(): void {
    if (!this.started) return
    this.started = false
    this.sourceUnsubscribe?.()
    this.sourceStateUnsubscribe?.()
    this.sourceUnsubscribe = null
    this.sourceStateUnsubscribe = null
    this.source.stop()
    this.recoverActiveAlarm(Date.now())
    this.liveSnapshot = {
      latestReading: null,
      recentTrend: Object.freeze([]),
      connectionState: ConnectionState.DISCONNECTED,
      visionSourceState: VisionSourceState.STOPPED,
      monitoringState: MonitoringState.IDLE,
    }
    this.notify(this.liveListeners)
  }

  dispose(): void {
    if (this.disposed) return
    this.stop()
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

function isDiameterAlarmStatus(
  status: MeasurementStatus,
): status is AlarmEvent['status'] {
  return (
    status === MeasurementStatus.WARNING ||
    status === MeasurementStatus.OUT_OF_TOLERANCE
  )
}
