import type {
  DiameterReading,
  MonitoringConfiguration,
  VisionSourceState,
  BackendMeasurementRecord, BackendEvent, BackendRecovery, BackendState, CameraPreview,
} from '../domain/monitoring'

export type Unsubscribe = () => void
export type ReadingListener = (reading: DiameterReading) => void
export type SourceStateListener = (state: VisionSourceState) => void

export interface FilamentDataSource {
  readonly state: VisionSourceState
  subscribe(listener: ReadingListener): Unsubscribe
  subscribeState(listener: SourceStateListener): Unsubscribe
  observe?(configuration: MonitoringConfiguration): void
  unobserve?(): void
  subscribeMeasurement?(listener: (value: BackendMeasurementRecord) => void): Unsubscribe
  subscribeEvents?(listener: (value: BackendEvent) => void): Unsubscribe
  subscribeRecovery?(listener: (value: BackendRecovery) => void): Unsubscribe
  subscribeBackendState?(listener: (value: BackendState) => void): Unsubscribe
  subscribePreview?(listener: (value: CameraPreview | null) => void): Unsubscribe
  start(configuration: MonitoringConfiguration): void | Promise<void>
  updateConfiguration(configuration: MonitoringConfiguration): void
  stop(): void | Promise<void>
  dispose(): void
}
