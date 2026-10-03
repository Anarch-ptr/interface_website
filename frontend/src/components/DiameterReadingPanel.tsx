import { MeasurementStatus, type DiameterReading, type MonitoringConfiguration, type BackendAlarmState } from '../domain/monitoring'

export function DiameterReadingPanel({ reading, configuration, alarm }: { reading: DiameterReading | null; configuration: Readonly<MonitoringConfiguration>; alarm?: BackendAlarmState }) {
  const quality = statusPresentation(reading?.status ?? MeasurementStatus.DISCONNECTED)
  const presentation = alarm?.level === 'CRITICAL' ? { label: 'Critical', protocolLabel: 'OUT OF TOLERANCE', tone: 'critical' } :
    alarm?.level === 'WARNING' ? { label: 'Warning', protocolLabel: 'WARNING', tone: 'warning' } :
    alarm?.level === 'NORMAL' ? { label: 'Within limits', protocolLabel: 'WITHIN TOLERANCE', tone: 'normal' } : quality
  return (
    <section className={`panel reading-panel status-${presentation.tone}`}>
      <div className="reading-panel-header"><div><p className="panel-kicker">CURRENT DIAMETER</p><span>Vision measurement</span></div><span className={`severity-pill ${presentation.tone}`}>{presentation.label}</span></div>
      <div className="diameter-core"><output aria-label="Current filament diameter">{reading ? reading.diameterMm.toFixed(3) : '—'}</output><span>mm</span></div>
      <p className="protocol-status">{presentation.protocolLabel}</p>
      {reading && <p className="measurement-context" aria-label="Measurement quality">Measurement quality: {reading.status.replaceAll('_', ' ')}</p>}
      {reading ? <div className="measurement-context"><span aria-label="Measurement confidence">Confidence {reading.confidence === null ? 'Unavailable' : `${(reading.confidence * 100).toFixed(0)}%`}</span><time aria-label="Measurement captured at" dateTime={new Date(reading.timestamp).toISOString()}>{new Date(reading.timestamp).toLocaleTimeString()}</time></div> : <p className="measurement-context">Waiting for Raspberry Pi</p>}
      <div className="reading-details">
        <div><span>Target</span><strong>{configuration.targetDiameterMm.toFixed(3)} mm</strong></div>
        <div><span>Deviation</span><strong>{reading ? formatSigned(reading.deviationMm, ' mm') : '—'}</strong></div>
        <div><span>Deviation %</span><strong>{reading ? formatSigned(reading.deviationPercent, '%') : '—'}</strong></div>
      </div>
      <div className="threshold-scale" aria-label="Configured alarm thresholds"><span>Warning ±{configuration.warningDeviationMm.toFixed(3)} mm</span><span>Out of tolerance ±{configuration.outOfToleranceDeviationMm.toFixed(3)} mm</span></div>
    </section>
  )
}

function statusPresentation(status: string) {
  if (status === MeasurementStatus.NORMAL) return { label: 'Valid measurement', protocolLabel: 'VALID MEASUREMENT', tone: 'normal' }
  if (status === MeasurementStatus.LOW_CONFIDENCE) return { label: 'Low confidence', protocolLabel: 'LOW CONFIDENCE', tone: 'warning' }
  if (status !== MeasurementStatus.DISCONNECTED) return { label: 'Unavailable', protocolLabel: status.replaceAll('_', ' '), tone: 'muted' }
  return { label: 'Waiting', protocolLabel: 'AWAITING DATA', tone: 'muted' }
}

function formatSigned(value: number, suffix: string): string {
  return `${value >= 0 ? '+' : ''}${value.toFixed(3)}${suffix}`
}
