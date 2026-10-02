import { MeasurementStatus, type AlarmEvent } from '../domain/monitoring'
import type { MonitoringStore } from '../state/MonitoringStore'
import { useAlarmHistory } from '../state/useMonitoringStore'

export function AlarmHistory({ store }: { store: MonitoringStore }) {
  const { events, activeEventId } = useAlarmHistory(store)
  return (
    <section className="page" aria-labelledby="alarms-title">
      <div className="page-heading"><div><p className="eyebrow">BOUNDED EVENT LOG</p><h2 id="alarms-title">Alarm History</h2><p>State-entry episodes with room for duration and recovery analysis.</p></div><div className="history-count">{events.length} / 300 events</div></div>
      <section className="panel history-panel">
        {events.length === 0 ? <div className="empty-state"><span className="healthy-check">✓</span><h3>No alarms recorded</h3><p>Warning and out-of-tolerance episodes will appear here.</p></div> : (
          <div className="alarm-table" role="list">
            <div className="alarm-table-head" aria-hidden="true"><span>Severity</span><span>Event</span><span>Started</span><span>Peak</span><span>Deviation</span><span>State</span></div>
            {events.map((event) => <AlarmRow key={event.id} event={event} active={event.id === activeEventId} />)}
          </div>
        )}
      </section>
    </section>
  )
}

function AlarmRow({ event, active }: { event: AlarmEvent; active: boolean }) {
  const isCritical = event.status === MeasurementStatus.OUT_OF_TOLERANCE
  const deviation = event.peakReading.deviationMm
  return (
    <article className="alarm-row" role="listitem">
      <span className={`severity-pill ${isCritical ? 'critical' : 'warning'}`}>{isCritical ? 'Critical' : 'Warning'}</span>
      <div><strong>{event.direction === 'HIGH' ? 'High diameter' : 'Low diameter'}</strong><span>{event.status.replaceAll('_', ' ')}</span></div>
      <time dateTime={new Date(event.startedAt).toISOString()}>{formatTimestamp(event.startedAt)}</time>
      <strong>{event.peakReading.fusedDiameterMm.toFixed(3)} mm</strong>
      <div><strong>{`${deviation >= 0 ? '+' : ''}${deviation.toFixed(3)} mm`}</strong><span>{`${event.peakReading.deviationPercent >= 0 ? '+' : ''}${event.peakReading.deviationPercent.toFixed(2)}%`}</span></div>
      <span className={active ? 'event-state active' : 'event-state'}>{active ? 'Active' : `Recovered ${formatTimestamp(event.recoveredAt ?? event.startedAt)}`}</span>
    </article>
  )
}

function formatTimestamp(timestamp: number): string {
  return new Intl.DateTimeFormat(undefined, { hour: '2-digit', minute: '2-digit', second: '2-digit' }).format(timestamp)
}
