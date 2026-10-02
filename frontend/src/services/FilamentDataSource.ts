import type {
  DiameterReading,
  MonitoringConfiguration,
  VisionSourceState,
} from '../domain/monitoring'

export type Unsubscribe = () => void
export type ReadingListener = (reading: DiameterReading) => void
export type SourceStateListener = (state: VisionSourceState) => void

export interface FilamentDataSource {
  readonly state: VisionSourceState
  subscribe(listener: ReadingListener): Unsubscribe
  subscribeState(listener: SourceStateListener): Unsubscribe
  start(configuration: MonitoringConfiguration): void
  updateConfiguration(configuration: MonitoringConfiguration): void
  stop(): void
  dispose(): void
}
