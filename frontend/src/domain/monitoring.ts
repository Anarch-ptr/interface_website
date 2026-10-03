export const VisionSourceState = {
  STOPPED: 'STOPPED',
  STARTING: 'STARTING',
  LIVE: 'LIVE',
  ERROR: 'ERROR',
} as const

export type VisionSourceState =
  (typeof VisionSourceState)[keyof typeof VisionSourceState]

export const ConnectionState = {
  DISCONNECTED: 'DISCONNECTED',
  SEARCHING: 'SEARCHING',
  CONNECTING: 'CONNECTING',
  CONNECTED: 'CONNECTED',
  RECONNECTING: 'RECONNECTING',
  ERROR: 'ERROR',
} as const

export type ConnectionState =
  (typeof ConnectionState)[keyof typeof ConnectionState]

export const MonitoringState = {
  IDLE: 'IDLE',
  READY: 'READY',
  STARTING: 'STARTING',
  MONITORING: 'MONITORING',
  STOPPING: 'STOPPING',
  COMPLETED: 'COMPLETED',
  INTERRUPTED: 'INTERRUPTED',
} as const

export type MonitoringState =
  (typeof MonitoringState)[keyof typeof MonitoringState]

export const MeasurementStatus = {
  NORMAL: 'NORMAL',
  WARNING: 'WARNING',
  OUT_OF_TOLERANCE: 'OUT_OF_TOLERANCE',
  LOW_CONFIDENCE: 'LOW_CONFIDENCE',
  INVALID: 'INVALID',
  DISCONNECTED: 'DISCONNECTED',
} as const

export type MeasurementStatus =
  (typeof MeasurementStatus)[keyof typeof MeasurementStatus]

export interface MonitoringConfiguration {
  targetDiameterMm: number
  warningDeviationMm: number
  outOfToleranceDeviationMm: number
  measurementRateHz: number
  recentTrendCapacity: number
}

export interface DiameterReading {
  timestamp: number
  diameterMm: number
  confidence: number | null
  deviationMm: number
  absoluteDeviationMm: number
  deviationPercent: number
  status: string
}

// Authoritative measurement transport; display preferences only derive deviation.
export interface BackendMeasurement {
  evidence: readonly { cameraId: string; timestamp: number; sequence: number;
    pixelWidth: number | null; diameterMm: number | null; confidence: number | null;
    valid: boolean; source: string }[]
  diameterMm: number | null
  confidence: number | null
  status: string
  timestamp: number
}

export interface BackendMeasurementRecord {
  id: string
  sessionId: string
  appliedRevision?: number | null
  measurement: BackendMeasurement
}

export interface BackendSession { id: string; startedAt?: number; endedAt?: number | null }
export interface BackendEvent {
  type: string; sequence: number; deviceId: string; occurredAt: number; payload: unknown
}
export interface BackendRecovery {
  sequence: number
  complete: boolean
  sessions: BackendSession[]
  measurements: BackendMeasurementRecord[]
  alarms: unknown[]
  errors: unknown[]
}
export interface BackendState {
  deviceId: string; connection: string; monitoring: string; eventSequence: number
  session: BackendSession | null; latestMeasurement: BackendMeasurement | null
  alarm?: BackendAlarmState
}
export interface CameraPreview {
  url: string; width: number; height: number; timestamp: number; sourceSequence: number
}

export interface BackendAlarmState { level: string; direction: string; deviationPercent: number | null }
export interface BackendAlarmTransition {
  id: string; sessionId: string; timestamp: number; diameterMm: number | null
  from: BackendAlarmState; to: BackendAlarmState
}
export interface BackendErrorRecord {
  id: string; sessionId: string | null; timestamp: number; code: string; message: string
}

export function backendAlarms(values: readonly unknown[]): BackendAlarmTransition[] {
  return values.filter((value): value is BackendAlarmTransition => isRecord(value) &&
    typeof value.id === 'string' && typeof value.sessionId === 'string' && finite(value.timestamp) &&
    (value.diameterMm === null || finite(value.diameterMm)) && alarmState(value.from) && alarmState(value.to))
    .sort((left, right) => compareBackendAlarmOrder(right, left))
}

export function compareBackendAlarmOrder(left: unknown, right: unknown): number {
  const leftTime = isRecord(left) && finite(left.timestamp) ? left.timestamp : 0
  const rightTime = isRecord(right) && finite(right.timestamp) ? right.timestamp : 0
  const leftId = isRecord(left) && typeof left.id === 'string' ? left.id : ''
  const rightId = isRecord(right) && typeof right.id === 'string' ? right.id : ''
  return leftTime - rightTime || (leftId < rightId ? -1 : leftId > rightId ? 1 : 0)
}

export function backendErrors(values: readonly unknown[]): BackendErrorRecord[] {
  return values.filter((value): value is BackendErrorRecord => isRecord(value) &&
    typeof value.id === 'string' && (value.sessionId === null || typeof value.sessionId === 'string') &&
    finite(value.timestamp) && typeof value.code === 'string' && typeof value.message === 'string')
    .sort((left, right) => right.timestamp - left.timestamp)
}

function alarmState(value: unknown): value is BackendAlarmState {
  return isRecord(value) && typeof value.level === 'string' && typeof value.direction === 'string' &&
    (value.deviationPercent === null || finite(value.deviationPercent))
}
function finite(value: unknown): value is number { return typeof value === 'number' && Number.isFinite(value) }
function isRecord(value: unknown): value is Record<string, unknown> { return !!value && typeof value === 'object' }

export const AlarmDirection = {
  HIGH: 'HIGH',
  LOW: 'LOW',
} as const

export type AlarmDirection =
  (typeof AlarmDirection)[keyof typeof AlarmDirection]

export interface AlarmEvent {
  id: string
  startedAt: number
  recoveredAt: number | null
  status:
    | typeof MeasurementStatus.WARNING
    | typeof MeasurementStatus.OUT_OF_TOLERANCE
  direction: AlarmDirection
  initialReading: DiameterReading
  peakReading: DiameterReading
  acknowledgedAt: number | null
  serverId?: string
}
