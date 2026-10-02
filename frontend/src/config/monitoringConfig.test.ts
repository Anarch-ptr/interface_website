import { describe, expect, it } from 'vitest'

import {
  DEFAULT_MONITORING_CONFIGURATION,
  LocalConfigurationStorage,
  validateMonitoringConfiguration,
} from './monitoringConfig'

class MemoryStorage implements Storage {
  private readonly entries = new Map<string, string>()

  get length(): number {
    return this.entries.size
  }

  clear(): void {
    this.entries.clear()
  }

  getItem(key: string): string | null {
    return this.entries.get(key) ?? null
  }

  key(index: number): string | null {
    return [...this.entries.keys()][index] ?? null
  }

  removeItem(key: string): void {
    this.entries.delete(key)
  }

  setItem(key: string, value: string): void {
    this.entries.set(key, value)
  }
}

describe('monitoring configuration', () => {
  it('accepts Android defaults and rejects non-positive or unordered values', () => {
    expect(validateMonitoringConfiguration(DEFAULT_MONITORING_CONFIGURATION)).toEqual({
      valid: true,
      errors: {},
    })

    const result = validateMonitoringConfiguration({
      ...DEFAULT_MONITORING_CONFIGURATION,
      targetDiameterMm: 0,
      warningDeviationMm: 0.03,
      outOfToleranceDeviationMm: 0.025,
    })

    expect(result.valid).toBe(false)
    expect(result.errors.targetDiameterMm).toBe('Target diameter must be positive.')
    expect(result.errors.warningDeviationMm).toBe(
      'Warning deviation must be lower than out-of-tolerance deviation.',
    )
  })

  it('loads a valid saved configuration without coupling consumers to localStorage', () => {
    const storage = new MemoryStorage()
    const configurationStorage = new LocalConfigurationStorage(storage)
    const configuration = {
      ...DEFAULT_MONITORING_CONFIGURATION,
      targetDiameterMm: 2.85,
      warningDeviationMm: 0.02,
      outOfToleranceDeviationMm: 0.04,
    }

    configurationStorage.save(configuration)

    expect(configurationStorage.load()).toEqual(configuration)
  })

  it('falls back to defaults for malformed or invalid persisted values', () => {
    const storage = new MemoryStorage()
    const configurationStorage = new LocalConfigurationStorage(storage)

    storage.setItem('filamentvision.monitoring-configuration', '{bad json')
    expect(configurationStorage.load()).toEqual(DEFAULT_MONITORING_CONFIGURATION)

    storage.setItem(
      'filamentvision.monitoring-configuration',
      JSON.stringify({
        ...DEFAULT_MONITORING_CONFIGURATION,
        warningDeviationMm: 0.05,
        outOfToleranceDeviationMm: 0.01,
      }),
    )
    expect(configurationStorage.load()).toEqual(DEFAULT_MONITORING_CONFIGURATION)
  })
})
