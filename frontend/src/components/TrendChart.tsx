import type { DiameterReading, MonitoringConfiguration } from '../domain/monitoring'

const VIEW_WIDTH = 760
const VIEW_HEIGHT = 278
const PADDING = { top: 24, right: 24, bottom: 34, left: 58 }

export function TrendChart({ readings, configuration }: { readings: readonly DiameterReading[]; configuration: Readonly<MonitoringConfiguration> }) {
  const yPadding = configuration.outOfToleranceDeviationMm * 1.45
  const yMin = configuration.targetDiameterMm - yPadding
  const yMax = configuration.targetDiameterMm + yPadding
  const plotWidth = VIEW_WIDTH - PADDING.left - PADDING.right
  const plotHeight = VIEW_HEIGHT - PADDING.top - PADDING.bottom
  const xFor = (index: number) => PADDING.left + (readings.length <= 1 ? plotWidth : (index / (readings.length - 1)) * plotWidth)
  const yFor = (diameter: number) => PADDING.top + ((yMax - diameter) / (yMax - yMin)) * plotHeight
  const points = readings.map((reading, index) => `${xFor(index)},${yFor(reading.fusedDiameterMm)}`).join(' ')
  const latest = readings.at(-1)

  return (
    <div className="trend-chart">
      <svg viewBox={`0 0 ${VIEW_WIDTH} ${VIEW_HEIGHT}`} role="img" aria-label="Recent filament diameter trend">
        <rect className="warning-zone" x={PADDING.left} y={yFor(configuration.targetDiameterMm + configuration.outOfToleranceDeviationMm)} width={plotWidth} height={yFor(configuration.targetDiameterMm + configuration.warningDeviationMm) - yFor(configuration.targetDiameterMm + configuration.outOfToleranceDeviationMm)} />
        <rect className="warning-zone" x={PADDING.left} y={yFor(configuration.targetDiameterMm - configuration.warningDeviationMm)} width={plotWidth} height={yFor(configuration.targetDiameterMm - configuration.outOfToleranceDeviationMm) - yFor(configuration.targetDiameterMm - configuration.warningDeviationMm)} />
        <ThresholdLine y={yFor(configuration.targetDiameterMm)} label={`${configuration.targetDiameterMm.toFixed(3)} target`} className="target-line" />
        <ThresholdLine y={yFor(configuration.targetDiameterMm + configuration.warningDeviationMm)} label="+ warning" className="warning-line" />
        <ThresholdLine y={yFor(configuration.targetDiameterMm - configuration.warningDeviationMm)} label="− warning" className="warning-line" />
        <ThresholdLine y={yFor(configuration.targetDiameterMm + configuration.outOfToleranceDeviationMm)} label="+ limit" className="limit-line" />
        <ThresholdLine y={yFor(configuration.targetDiameterMm - configuration.outOfToleranceDeviationMm)} label="− limit" className="limit-line" />
        {latest ? <><polyline className="trend-line-glow" points={points} /><polyline className="trend-line" points={points} /><circle className={`latest-point ${latest.status.toLowerCase()}`} cx={xFor(readings.length - 1)} cy={yFor(latest.fusedDiameterMm)} r="4.5" /></> : <text className="chart-empty" x="50%" y="50%" textAnchor="middle">Waiting for measurement data…</text>}
      </svg>
      <div className="chart-axis-caption"><span>Older</span><span>Recent Trend · transient 300-sample window</span><span>Now</span></div>
    </div>
  )
}

function ThresholdLine({ y, label, className }: { y: number; label: string; className: string }) {
  return <g className={className}><line x1={PADDING.left} x2={VIEW_WIDTH - PADDING.right} y1={y} y2={y} /><text x={PADDING.left - 8} y={y + 3} textAnchor="end">{label}</text></g>
}
