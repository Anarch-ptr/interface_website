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
  CAMERA_MISMATCH: 'CAMERA_MISMATCH',
  CAMERA_A_FAILURE: 'CAMERA_A_FAILURE',
  CAMERA_B_FAILURE: 'CAMERA_B_FAILURE',
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
  fusedDiameterMm: number
  diameterAMm: number
  diameterBMm: number
  cameraAConfidence: number
  cameraBConfidence: number
  confidence: number
  shapeDifferenceMm: number
  deviationMm: number
  absoluteDeviationMm: number
  deviationPercent: number
  status: MeasurementStatus
}

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
