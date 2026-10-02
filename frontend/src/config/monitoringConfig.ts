import type { MonitoringConfiguration } from '../domain/monitoring'

export const DEFAULT_MONITORING_CONFIGURATION: Readonly<MonitoringConfiguration> = {
  targetDiameterMm: 1.75,
  warningDeviationMm: 0.012,
  outOfToleranceDeviationMm: 0.025,
  measurementRateHz: 8,
  recentTrendCapacity: 300,
}

export type ConfigurationErrors = Partial<
  Record<keyof MonitoringConfiguration, string>
>

export interface ConfigurationValidationResult {
  valid: boolean
  errors: ConfigurationErrors
}

export interface ConfigurationStorage {
  load(): MonitoringConfiguration
  save(configuration: MonitoringConfiguration): void
  reset(): MonitoringConfiguration
}

const CONFIGURATION_STORAGE_KEY = 'filamentvision.monitoring-configuration'

export function validateMonitoringConfiguration(
  configuration: MonitoringConfiguration,
): ConfigurationValidationResult {
  const errors: ConfigurationErrors = {}

  if (!Number.isFinite(configuration.targetDiameterMm) || configuration.targetDiameterMm <= 0) {
    errors.targetDiameterMm = 'Target diameter must be positive.'
  }
  if (
    !Number.isFinite(configuration.warningDeviationMm) ||
    configuration.warningDeviationMm <= 0
  ) {
    errors.warningDeviationMm = 'Warning deviation must be positive.'
  }
  if (
    !Number.isFinite(configuration.outOfToleranceDeviationMm) ||
    configuration.outOfToleranceDeviationMm <= 0
  ) {
    errors.outOfToleranceDeviationMm =
      'Out-of-tolerance deviation must be positive.'
  }
  if (
    configuration.warningDeviationMm >=
    configuration.outOfToleranceDeviationMm
  ) {
    errors.warningDeviationMm =
      'Warning deviation must be lower than out-of-tolerance deviation.'
  }
  if (
    !Number.isInteger(configuration.measurementRateHz) ||
    configuration.measurementRateHz <= 0
  ) {
    errors.measurementRateHz = 'Measurement rate must be a positive integer.'
  }
  if (
    !Number.isInteger(configuration.recentTrendCapacity) ||
    configuration.recentTrendCapacity <= 0
  ) {
    errors.recentTrendCapacity =
      'Recent Trend capacity must be a positive integer.'
  }

  return { valid: Object.keys(errors).length === 0, errors }
}

export class LocalConfigurationStorage implements ConfigurationStorage {
  private readonly storage: Storage
  private readonly storageKey: string

  constructor(
    storage: Storage,
    storageKey = CONFIGURATION_STORAGE_KEY,
  ) {
    this.storage = storage
    this.storageKey = storageKey
  }

  load(): MonitoringConfiguration {
    const rawValue = this.storage.getItem(this.storageKey)
    if (rawValue === null) return cloneDefaults()

    try {
      const parsed: unknown = JSON.parse(rawValue)
      if (!isMonitoringConfiguration(parsed)) return cloneDefaults()
      if (!validateMonitoringConfiguration(parsed).valid) return cloneDefaults()
      return { ...parsed }
    } catch {
      return cloneDefaults()
    }
  }

  save(configuration: MonitoringConfiguration): void {
    const validation = validateMonitoringConfiguration(configuration)
    if (!validation.valid) {
      throw new Error('Cannot save an invalid monitoring configuration.')
    }
    this.storage.setItem(this.storageKey, JSON.stringify(configuration))
  }

  reset(): MonitoringConfiguration {
    this.storage.removeItem(this.storageKey)
    return cloneDefaults()
  }
}

function cloneDefaults(): MonitoringConfiguration {
  return { ...DEFAULT_MONITORING_CONFIGURATION }
}

function isMonitoringConfiguration(value: unknown): value is MonitoringConfiguration {
  if (typeof value !== 'object' || value === null) return false
  const candidate = value as Record<string, unknown>
  return (
    typeof candidate.targetDiameterMm === 'number' &&
    typeof candidate.warningDeviationMm === 'number' &&
    typeof candidate.outOfToleranceDeviationMm === 'number' &&
    typeof candidate.measurementRateHz === 'number' &&
    typeof candidate.recentTrendCapacity === 'number'
  )
}
