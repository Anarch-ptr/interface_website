import type { MonitoringStore } from '../state/MonitoringStore'
import { useAlarmHistory, useLiveMonitoring, useMonitoringConfiguration } from '../state/useMonitoringStore'
import { DiameterReadingPanel } from './DiameterReadingPanel'
import { SystemHealth } from './SystemHealth'
import { TrendChart } from './TrendChart'

export function MonitoringDashboard({ store }: { store: MonitoringStore }) {
  const live = useLiveMonitoring(store)
  const { events } = useAlarmHistory(store)
  const { configuration } = useMonitoringConfiguration(store)
  const latestAlarm = events[0]
  const reading = live.latestReading

  return (
    <section className="page" aria-labelledby="monitor-title">
      <div className="page-heading">
        <div><p className="eyebrow">REAL-TIME MONITORING</p><h2 id="monitor-title">System overview</h2><p>Fused dual-camera measurement with live deviation analysis.</p></div>
        <div className="monitoring-badge"><span className="live-dot" />8 Hz live feed</div>
      </div>
      <SystemHealth live={live} />
      <div className="dashboard-grid">
        <DiameterReadingPanel reading={reading} configuration={configuration} />
        <section className="panel trend-panel" aria-labelledby="trend-title">
          <div className="panel-heading"><div><p className="panel-kicker">RECENT TREND</p><h3 id="trend-title">Diameter stability</h3></div><span>{live.recentTrend.length} / 300 samples</span></div>
          <TrendChart readings={live.recentTrend} configuration={configuration} />
        </section>
        <section className="panel metrics-panel" aria-labelledby="quality-title">
          <div className="panel-heading"><div><p className="panel-kicker">DUAL-VIEW QUALITY</p><h3 id="quality-title">Measurement confidence</h3></div></div>
          <div className="metric-list">
            <Metric label="Camera A" value={reading ? `${(reading.cameraAConfidence * 100).toFixed(0)}%` : '—'} />
            <Metric label="Camera B" value={reading ? `${(reading.cameraBConfidence * 100).toFixed(0)}%` : '—'} />
            <Metric label="View difference" value={reading ? `${reading.shapeDifferenceMm.toFixed(3)} mm` : '—'} />
            <Metric label="Buffer" value={`${live.recentTrend.length} / 300`} />
          </div>
        </section>
        <section className="panel alarm-summary" aria-labelledby="latest-alarm-title">
          <div className="panel-heading"><div><p className="panel-kicker">LATEST ALARM</p><h3 id="latest-alarm-title">Event context</h3></div></div>
          {latestAlarm ? (
            <div className={`alarm-callout ${latestAlarm.status.toLowerCase()}`}>
              <strong>{latestAlarm.status === 'OUT_OF_TOLERANCE' ? 'Critical' : 'Warning'} · {latestAlarm.direction === 'HIGH' ? 'High diameter' : 'Low diameter'}</strong>
              <span>Peak {latestAlarm.peakReading.fusedDiameterMm.toFixed(3)} mm · {latestAlarm.recoveredAt ? 'Recovered' : 'Active'}</span>
            </div>
          ) : (
            <div className="empty-compact"><span className="healthy-check">✓</span><div><strong>No abnormal events</strong><span>The simulated stream is inside configured limits.</span></div></div>
          )}
        </section>
      </div>
    </section>
  )
}

function Metric({ label, value }: { label: string; value: string }) {
  return <div><span>{label}</span><strong>{value}</strong></div>
}
