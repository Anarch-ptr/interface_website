import { MeasurementStatus, backendAlarms, type AlarmEvent, type BackendAlarmTransition } from '../domain/monitoring'
import type { MonitoringStore } from '../state/MonitoringStore'
import { useAlarmHistory, useLiveMonitoring } from '../state/useMonitoringStore'

export function AlarmHistory({ store }: { store: MonitoringStore }) {
  const { events, activeEventId } = useAlarmHistory(store)
  const live = useLiveMonitoring(store)
  const transitions = backendAlarms(live.recoveredAlarms)
  return (
    <section className="page" aria-labelledby="alarms-title">
      <div className="page-heading"><div><p className="eyebrow">BOUNDED EVENT LOG</p><h2 id="alarms-title">Alarm History</h2><p>Reliable alarm transitions with recovery and session context.</p></div><div className="history-count">{transitions.length || events.length} / 300 events</div></div>
      <section className="panel history-panel">
        {transitions.length === 0 && events.length === 0 ? <div className="empty-state"><h3>No alarms recorded</h3><p>Warning and out-of-tolerance transitions will appear here.</p></div> : (
          <div className="alarm-table" role="list">
            <div className="alarm-table-head" aria-hidden="true"><span>Severity</span><span>Event</span><span>{transitions.length ? 'Timestamp' : 'Started'}</span><span>{transitions.length ? 'Diameter' : 'Peak'}</span><span>Deviation</span><span>State</span></div>
            {transitions.length ? transitions.map(event => {
              const latest = transitions.find(value => value.sessionId === event.sessionId)
              const active = event.to.level !== 'NORMAL' && latest?.id === event.id &&
                live.backendState?.session?.id === event.sessionId && live.backendState.monitoring === 'RUNNING' &&
                (!live.backendState.alarm || live.backendState.alarm.level === event.to.level)
              const ended = latest?.id !== event.id || !!live.backendState &&
                (live.backendState.session?.id !== event.sessionId || live.backendState.monitoring !== 'RUNNING')
              return <BackendAlarmRow key={event.id} event={event} active={active} ended={ended} />
            }) : events.map((event) => <AlarmRow key={event.id} event={event} active={event.id === activeEventId} />)}
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
      <strong>{event.peakReading.diameterMm.toFixed(3)} mm</strong>
      <div><strong>{`${deviation >= 0 ? '+' : ''}${deviation.toFixed(3)} mm`}</strong><span>{`${event.peakReading.deviationPercent >= 0 ? '+' : ''}${event.peakReading.deviationPercent.toFixed(2)}%`}</span></div>
      <span className={active ? 'event-state active' : 'event-state'}>{active ? 'Active' : `Recovered ${formatTimestamp(event.recoveredAt ?? event.startedAt)}`}</span>
    </article>
  )
}

function BackendAlarmRow({ event, active, ended }: { event: BackendAlarmTransition; active: boolean; ended: boolean }) {
  const recovered = event.to.level === 'NORMAL'
  const critical = event.to.level === 'CRITICAL'
  return <article className="alarm-row" role="listitem">
    <span className={`severity-pill ${critical ? 'critical' : recovered ? 'normal' : 'warning'}`}>{critical ? 'Critical' : recovered ? 'Recovered' : 'Warning'}</span>
    <div><strong>{event.to.direction === 'HIGH' ? 'High diameter' : event.to.direction === 'LOW' ? 'Low diameter' : 'Within limits'}</strong><span>Session {event.sessionId}</span></div>
    <time dateTime={new Date(event.timestamp).toISOString()}>{formatTimestamp(event.timestamp)}</time>
    <strong>{event.diameterMm === null ? 'Unavailable' : `${event.diameterMm.toFixed(3)} mm`}</strong>
    <div><strong>{event.to.deviationPercent === null ? 'Unavailable' : `${event.to.deviationPercent >= 0 ? '+' : ''}${event.to.deviationPercent.toFixed(2)}%`}</strong></div>
    <span className={active ? 'event-state active' : 'event-state'}>{active ? 'Active' : recovered ? 'Recovery' : ended ? 'Ended' : 'Recorded'}</span>
  </article>
}

function formatTimestamp(timestamp: number): string {
  return new Intl.DateTimeFormat(undefined, { hour: '2-digit', minute: '2-digit', second: '2-digit' }).format(timestamp)
}
