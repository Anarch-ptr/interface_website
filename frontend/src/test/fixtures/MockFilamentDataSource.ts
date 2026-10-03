import { createDiameterReading } from '../../domain/measurement'
import {
  VisionSourceState,
  type MonitoringConfiguration,
} from '../../domain/monitoring'
import type {
  FilamentDataSource,
  ReadingListener,
  SourceStateListener,
  Unsubscribe,
} from '../../services/FilamentDataSource'

export class MockFilamentDataSource implements FilamentDataSource {
  private readonly readingListeners = new Set<ReadingListener>()
  private readonly stateListeners = new Set<SourceStateListener>()
  private timer: ReturnType<typeof setInterval> | null = null
  private configuration: MonitoringConfiguration | null = null
  private sampleIndex = 0
  private disposed = false
  private mutableState: VisionSourceState = VisionSourceState.STOPPED
  private producerStarts = 0

  get state(): VisionSourceState {
    return this.mutableState
  }

  get activeProducerCount(): number {
    return this.timer === null ? 0 : 1
  }

  get totalProducerStarts(): number {
    return this.producerStarts
  }

  get subscriberCount(): number {
    return this.readingListeners.size
  }

  get stateSubscriberCount(): number {
    return this.stateListeners.size
  }

  subscribe(listener: ReadingListener): Unsubscribe {
    this.readingListeners.add(listener)
    return () => this.readingListeners.delete(listener)
  }

  subscribeState(listener: SourceStateListener): Unsubscribe {
    this.stateListeners.add(listener)
    return () => this.stateListeners.delete(listener)
  }

  start(configuration: MonitoringConfiguration): void {
    if (this.disposed) {
      throw new Error('A disposed data source cannot be restarted.')
    }
    if (this.timer !== null) return

    this.configuration = { ...configuration }
    this.setState(VisionSourceState.STARTING)
    this.sampleIndex = 0
    this.emitReading()
    const samplePeriodMs = 1_000 / configuration.measurementRateHz
    this.timer = setInterval(() => this.emitReading(), samplePeriodMs)
    this.producerStarts += 1
    this.setState(VisionSourceState.LIVE)
  }

  updateConfiguration(configuration: MonitoringConfiguration): void {
    this.configuration = { ...configuration }
  }

  stop(): void {
    if (this.timer !== null) {
      clearInterval(this.timer)
      this.timer = null
    }
    this.setState(VisionSourceState.STOPPED)
  }

  dispose(): void {
    if (this.disposed) return
    this.stop()
    this.disposed = true
    this.readingListeners.clear()
    this.stateListeners.clear()
  }

  private emitReading(): void {
    const configuration = this.configuration
    if (configuration === null) return

    const drift = Math.sin(this.sampleIndex * 0.16) * 0.002
    const viewVariation = Math.sin(this.sampleIndex * 0.31) * 0.0015
    const offset = phaseOffset(this.sampleIndex)
    const diameterAMm =
      configuration.targetDiameterMm + offset + drift + viewVariation
    const diameterBMm =
      configuration.targetDiameterMm + offset + drift - viewVariation + 0.0015
    const cameraAConfidence = 0.98
    const cameraBConfidence = 0.97
    const fusedDiameterMm =
      (diameterAMm * cameraAConfidence + diameterBMm * cameraBConfidence) /
      (cameraAConfidence + cameraBConfidence)
    const reading = createDiameterReading({
      diameterMm: fusedDiameterMm,
      confidence: (cameraAConfidence + cameraBConfidence) / 2,
      configuration,
      timestamp: Date.now(),
    })

    this.sampleIndex += 1
    for (const listener of this.readingListeners) listener(reading)
  }

  private setState(state: VisionSourceState): void {
    if (state === this.mutableState) return
    this.mutableState = state
    for (const listener of this.stateListeners) listener(state)
  }
}

function phaseOffset(sampleIndex: number): number {
  const phase = sampleIndex % 128
  if (phase < 32) return 0
  if (phase < 48) return 0.008
  if (phase < 64) return 0.018
  if (phase < 80) return 0.035
  if (phase < 96) return 0
  if (phase < 112) return -0.018
  return -0.035
}
