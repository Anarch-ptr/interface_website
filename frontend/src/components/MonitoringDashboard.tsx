import { toDiameterReading } from '../domain/measurement'
import { backendAlarms, backendErrors } from '../domain/monitoring'
import type { BackendApiClient } from '../services/BackendApiClient'
import type { MonitoringStore } from '../state/MonitoringStore'
import { useLiveMonitoring, useMonitoringConfiguration } from '../state/useMonitoringStore'
import { DiameterReadingPanel } from './DiameterReadingPanel'
import { LiveCameraPreview } from './LiveCameraPreview'
import { SystemHealth } from './SystemHealth'
import { TrendChart } from './TrendChart'

export function MonitoringDashboard({ store, api }: { store: MonitoringStore; api?: BackendApiClient }) {
  const live = useLiveMonitoring(store)
  const { configuration } = useMonitoringConfiguration(store)
  const reading = live.latestBackendReading ? toDiameterReading(live.latestBackendReading, configuration) : null
  const readings = live.recentBackendTrend.flatMap(record => {
    const value = toDiameterReading(record.measurement, configuration)
    return value ? [value] : []
  })
  const latestAlarm = backendAlarms(live.recoveredAlarms)[0]
  const latestError = backendErrors(live.recoveredErrors)[0]
  const alarm = live.backendState?.alarm ?? latestAlarm?.to
  const alarmLabel = alarm?.level === 'CRITICAL' ? 'Critical' : alarm?.level === 'WARNING' ? 'Warning' : 'Within limits'
  const direction = alarm?.direction === 'HIGH' ? 'High diameter' : alarm?.direction === 'LOW' ? 'Low diameter' : 'Within limits'
  const sessionCount = live.recoveredSessions.length

  return (
    <section className="page" aria-labelledby="monitor-title">
      <div className="page-heading"><div><p className="eyebrow">REAL-TIME MONITORING</p><h2 id="monitor-title">System overview</h2><p>Live camera measurement with diameter trend and alarm context.</p></div></div>
      <SystemHealth live={live} api={api} />
      <div className="dashboard-grid">
        <LiveCameraPreview preview={live.preview} />
        <DiameterReadingPanel reading={reading} configuration={configuration} />
        <section className="panel trend-panel" aria-labelledby="trend-title">
          <div className="panel-heading"><div><p className="panel-kicker">RECENT TREND / HISTORY</p><h3 id="trend-title">Diameter stability</h3></div><span>{readings.length} / {configuration.recentTrendCapacity} samples</span></div>
          <TrendChart readings={readings} configuration={configuration} />
        </section>
        <section className="panel alarm-summary" aria-labelledby="latest-alarm-title">
          <div className="panel-heading"><div><p className="panel-kicker">WARNING / ALARM</p><h3 id="latest-alarm-title">Event context</h3></div></div>
          {alarm ? <div className={`alarm-callout ${alarm.level === 'CRITICAL' ? 'out_of_tolerance' : alarm.level.toLowerCase()}`}>
            <strong>{alarm.level === 'NORMAL' ? 'Within limits' : `${alarmLabel} · ${direction}`}</strong>
            {latestAlarm && <span>{latestAlarm.to.level === 'NORMAL' ? 'Recovered' : `Latest transition: ${latestAlarm.to.level}`} · {latestAlarm.diameterMm === null ? 'Diameter unavailable' : `${latestAlarm.diameterMm.toFixed(3)} mm`} · {new Date(latestAlarm.timestamp).toLocaleTimeString()}</span>}
          </div> : <div className="empty-compact"><div><strong>Awaiting Backend alarm context</strong><span>Reliable alarm transitions appear here.</span></div></div>}
          {latestError && <p className="backend-error" role="alert">{latestError.code}: {latestError.message}</p>}
          <p className="session-context">{live.backendState?.session ? `Session ${live.backendState.session.id} · ` : ''}{sessionCount} retained {sessionCount === 1 ? 'session' : 'sessions'}</p>
        </section>
      </div>
    </section>
  )
}
