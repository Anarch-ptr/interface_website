import { useEffect, useState } from 'react'
import type { UdpDiagnostics, UdpOperationalStatus } from '../domain/raspberryPiUdpInput'
import { BackendApiClient } from '../services/BackendApiClient'
import type { LiveMonitoringSnapshot } from '../state/MonitoringStore'

const defaultApi = new BackendApiClient()
const statusLabels: Record<UdpOperationalStatus, string> = {
  DISABLED: 'UDP disabled', UDP_READY: 'UDP ready', WAITING_FOR_RASPBERRY_PI: 'Waiting for Raspberry Pi',
  RECEIVING: 'Receiving UDP packets', NO_PACKETS_RECENTLY: 'No UDP packets recently',
  SOURCE_IP_MISMATCH: 'Unexpected Raspberry Pi source IP', BIND_FAILED: 'UDP bind failed',
}

export function SystemHealth({ live, api = defaultApi }: { live: LiveMonitoringSnapshot; api?: BackendApiClient }) {
  const [diagnostics, setDiagnostics] = useState<UdpDiagnostics | null>(null)
  useEffect(() => {
    const abort = new AbortController()
    let timer: ReturnType<typeof setTimeout> | undefined
    async function poll() {
      try {
        const value = await api.getRaspberryPiUdpDiagnostics('filamentvision-1', abort.signal)
        if (!abort.signal.aborted) setDiagnostics(value)
      } catch { if (!abort.signal.aborted) setDiagnostics(null) }
      if (!abort.signal.aborted) timer = setTimeout(() => { void poll() }, 1000)
    }
    void poll()
    return () => { abort.abort(); clearTimeout(timer) }
  }, [api])
  const monitoring = live.backendState?.monitoring ?? 'Awaiting Backend state'
  const connection = live.visionSourceState === 'ERROR' ? 'Backend observer unavailable' :
    live.backendState?.connection ?? 'Awaiting Backend state'

  return <section className="health-strip" aria-label="System health">
    <div className="health-item udp-status" role="status"><div><span>Raspberry Pi UDP</span><strong>{diagnostics ? statusLabels[diagnostics.status] ?? 'UDP status unavailable' : 'UDP status unavailable'}</strong>{diagnostics?.statusDetail && <small>{diagnostics.statusDetail}</small>}</div></div>
    <HealthItem label="Backend connection" value={connection} healthy="CONNECTED" />
    <HealthItem label="Monitoring" value={monitoring} healthy="RUNNING" />
    <HealthItem label="Measurement quality" value={live.latestBackendReading?.status.replaceAll('_', ' ') ?? 'Waiting for Raspberry Pi'} healthy="NORMAL" />
  </section>
}

function HealthItem({ label, value, healthy }: { label: string; value: string; healthy: string }) {
  return <div className="health-item"><span className={value === healthy ? 'state-indicator healthy' : 'state-indicator'} /><div><span>{label}</span><strong>{value}</strong></div></div>
}
