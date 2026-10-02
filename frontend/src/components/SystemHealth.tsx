import type { LiveMonitoringSnapshot } from '../state/MonitoringStore'

export function SystemHealth({ live }: { live: LiveMonitoringSnapshot }) {
  return <section className="health-strip" aria-label="System health"><HealthItem label="Connection" value={live.connectionState} healthy="CONNECTED" /><HealthItem label="Vision source" value={live.visionSourceState} healthy="LIVE" /><HealthItem label="Monitoring" value={live.monitoringState} healthy="MONITORING" /><HealthItem label="Overall health" value={healthLabel(live)} healthy="NOMINAL" /></section>
}

function HealthItem({ label, value, healthy }: { label: string; value: string; healthy: string }) {
  return <div className="health-item"><span className={value === healthy ? 'state-indicator healthy' : 'state-indicator'} /><div><span>{label}</span><strong>{value}</strong></div></div>
}

function healthLabel(live: LiveMonitoringSnapshot): string {
  if (live.latestReading?.status === 'OUT_OF_TOLERANCE') return 'ATTENTION'
  if (live.latestReading?.status === 'WARNING') return 'WARNING'
  if (live.connectionState !== 'CONNECTED') return 'OFFLINE'
  return 'NOMINAL'
}
