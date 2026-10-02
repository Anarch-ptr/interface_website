import {
  MeasurementStatus,
  type DiameterReading,
  type MonitoringConfiguration,
} from './monitoring'

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
  fusedDiameterMm: number
  configuration: MonitoringConfiguration
  timestamp: number
  diameterAMm?: number
  diameterBMm?: number
  cameraAConfidence?: number
  cameraBConfidence?: number
}

export function createDiameterReading({
  fusedDiameterMm,
  configuration,
  timestamp,
  diameterAMm = fusedDiameterMm,
  diameterBMm = fusedDiameterMm,
  cameraAConfidence = 1,
  cameraBConfidence = 1,
}: CreateDiameterReadingInput): DiameterReading {
  const deviationMm = fusedDiameterMm - configuration.targetDiameterMm
  const absoluteDeviationMm = Math.abs(deviationMm)

  return {
    timestamp,
    fusedDiameterMm,
    diameterAMm,
    diameterBMm,
    cameraAConfidence,
    cameraBConfidence,
    confidence: (cameraAConfidence + cameraBConfidence) / 2,
    shapeDifferenceMm: Math.abs(diameterAMm - diameterBMm),
    deviationMm,
    absoluteDeviationMm,
    deviationPercent: (deviationMm / configuration.targetDiameterMm) * 100,
    status: classifyDiameter(fusedDiameterMm, configuration),
  }
}
