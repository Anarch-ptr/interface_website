import {
  MeasurementStatus,
  type DiameterReading,
  type MonitoringConfiguration,
  type BackendMeasurement,
} from './monitoring'

export function toDiameterReading(measurement: BackendMeasurement, configuration: MonitoringConfiguration): DiameterReading | null {
  if (measurement.diameterMm === null) return null
  return createDiameterReading({ diameterMm: measurement.diameterMm, confidence: measurement.confidence,
    status: measurement.status, timestamp: measurement.timestamp, configuration })
}

export function classifyDiameter(
  diameterMm: number,
  configuration: MonitoringConfiguration,
): MeasurementStatus {
  const absoluteDeviationMm = Math.abs(
    diameterMm - configuration.targetDiameterMm,
  )
  if (
    exceedsThreshold(
      absoluteDeviationMm,
      configuration.outOfToleranceDeviationMm,
    )
  ) {
    return MeasurementStatus.OUT_OF_TOLERANCE
  }
  if (exceedsThreshold(absoluteDeviationMm, configuration.warningDeviationMm)) {
    return MeasurementStatus.WARNING
  }
  return MeasurementStatus.NORMAL
}

function exceedsThreshold(value: number, threshold: number): boolean {
  const comparisonTolerance = Number.EPSILON * Math.max(1, value, threshold) * 8
  return value - threshold > comparisonTolerance
}

interface CreateDiameterReadingInput {
  diameterMm: number
  confidence: number | null
  status?: string
  configuration: MonitoringConfiguration
  timestamp: number
}

export function createDiameterReading({
  diameterMm,
  confidence,
  status,
  configuration,
  timestamp,
}: CreateDiameterReadingInput): DiameterReading {
  const deviationMm = diameterMm - configuration.targetDiameterMm
  const absoluteDeviationMm = Math.abs(deviationMm)

  return {
    timestamp,
    diameterMm,
    confidence,
    deviationMm,
    absoluteDeviationMm,
    deviationPercent: (deviationMm / configuration.targetDiameterMm) * 100,
    status: status ?? classifyDiameter(diameterMm, configuration),
  }
}
