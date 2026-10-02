import { describe, expect, it } from 'vitest'

import { DEFAULT_MONITORING_CONFIGURATION } from '../config/monitoringConfig'
import { classifyDiameter, createDiameterReading } from './measurement'
import { MeasurementStatus } from './monitoring'

describe('diameter classification', () => {
  it('matches the Android strict warning and out-of-tolerance boundaries', () => {
    const configuration = DEFAULT_MONITORING_CONFIGURATION

    expect(classifyDiameter(1.762, configuration)).toBe(MeasurementStatus.NORMAL)
    expect(classifyDiameter(1.7621, configuration)).toBe(MeasurementStatus.WARNING)
    expect(classifyDiameter(1.775, configuration)).toBe(MeasurementStatus.WARNING)
    expect(classifyDiameter(1.7751, configuration)).toBe(
      MeasurementStatus.OUT_OF_TOLERANCE,
    )
    expect(classifyDiameter(1.7379, configuration)).toBe(MeasurementStatus.WARNING)
    expect(classifyDiameter(1.7249, configuration)).toBe(
      MeasurementStatus.OUT_OF_TOLERANCE,
    )
  })

  it('derives signed, absolute, and percentage deviation from the target', () => {
    const reading = createDiameterReading({
      fusedDiameterMm: 1.775,
      configuration: DEFAULT_MONITORING_CONFIGURATION,
      timestamp: 1_000,
    })

    expect(reading.deviationMm).toBeCloseTo(0.025, 10)
    expect(reading.absoluteDeviationMm).toBeCloseTo(0.025, 10)
    expect(reading.deviationPercent).toBeCloseTo(1.4286, 4)
    expect(reading.status).toBe(MeasurementStatus.WARNING)
  })
})
