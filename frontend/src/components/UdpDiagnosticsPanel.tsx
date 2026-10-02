import type { UdpDiagnostics } from '../domain/udpInput'

export function UdpDiagnosticsPanel({ diagnostics }: { diagnostics?: UdpDiagnostics }) {
  if (!diagnostics) return <p className="udp-muted">Diagnostics unavailable.</p>
  const keys = ['datagramsReceived', 'bytesReceived', 'framesCompleted', 'framesDropped', 'invalidHeader',
    'invalidFooter', 'invalidPayloadSize', 'decodeFailures', 'sourceFilteredDatagrams']
  return <dl className="udp-diagnostics">{keys.map((key) => <div key={key}><dt>{key}</dt><dd>{String(diagnostics[key] ?? 0)}</dd></div>)}</dl>
}
