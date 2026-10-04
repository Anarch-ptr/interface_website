import type { UdpDiagnostics } from '../domain/raspberryPiUdpInput'

const counters = [
  ['datagramsReceived', 'Packets'], ['bytesReceived', 'Bytes'], ['framesStarted', 'Frames started'],
  ['framesCompleted', 'Frames completed'], ['framesDropped', 'Frames dropped'], ['duplicateChunks', 'Duplicate chunks'],
  ['outOfOrderChunks', 'Out-of-order chunks'], ['missingChunks', 'Missing chunks'], ['lateChunks', 'Late chunks'],
  ['reassemblyTimeouts', 'Reassembly timeouts'], ['invalidTransportHeaders', 'Invalid transport headers'],
  ['oversizedFrames', 'Oversized frames'], ['invalidHeaderCount', 'Header mismatches'], ['invalidFooterCount', 'Footer mismatches'],
  ['invalidPayloadSizeCount', 'Invalid payload size'], ['decodeFailures', 'Decode failures'],
  ['sourceFilteredDatagrams', 'Source mismatches'], ['oversizedOrTruncatedDatagrams', 'Oversized / truncated datagrams'],
  ['preAdmissionDroppedInputs', 'Pre-admission drops'], ['processingBackpressureDrops', 'Processing backpressure drops'],
] as const

export function UdpDiagnosticsPanel({ diagnostics }: { diagnostics?: UdpDiagnostics }) {
  if (!diagnostics) return <p className="udp-muted">Diagnostics unavailable.</p>
  const applied = diagnostics.appliedNetwork
  const actualSender = diagnostics.lastPacketSourceIpv4 === null
    ? 'Not received'
    : `${diagnostics.lastPacketSourceIpv4}:${diagnostics.lastPacketSourcePort ?? 'unknown'}`
  const sourceMismatch = applied && applied.resolvedSourceIpv4s.length > 0 && diagnostics.lastPacketSourceIpv4 !== null &&
    !applied.resolvedSourceIpv4s.includes(diagnostics.lastPacketSourceIpv4)
  return <div aria-label="UDP diagnostics">
    <p role="status">{diagnostics.status}{diagnostics.statusDetail ? ` · ${diagnostics.statusDetail}` : ''}</p>
    <dl className="udp-diagnostics">
      {applied ? <>
        <div><dt>Applied destination</dt><dd>{applied.configuredDestination ?? 'AUTO'} → {applied.resolvedDestinationIpv4 ?? 'Inactive'}</dd></div>
        <div><dt>Applied bind</dt><dd>{applied.actualBindIpv4} · {applied.actualBindInterfaceId ?? 'all IPv4 interfaces'}</dd></div>
        <div><dt>Applied source set</dt><dd>{applied.resolvedSourceIpv4s.join(', ') || 'Any IPv4 sender'}</dd></div>
        <div><dt>Resolution generation</dt><dd>{applied.resolutionGeneration}</dd></div>
        <div><dt>Plan created</dt><dd>{new Date(applied.planCreatedAtEpochMillis).toISOString()}</dd></div>
        <div><dt>Applied at</dt><dd>{applied.appliedAtEpochMillis === null ? 'Not recorded' : new Date(applied.appliedAtEpochMillis).toISOString()}</dd></div>
      </> : <div><dt>Applied network</dt><dd>Inactive</dd></div>}
      <div><dt>Actual sender</dt><dd>{actualSender}{sourceMismatch ? ' · does not match applied source set' : ''}</dd></div>
      <div><dt>Last packet time</dt><dd>{diagnostics.lastPacketEpochMillis === null ? 'Not received' : new Date(diagnostics.lastPacketEpochMillis).toISOString()}</dd></div>
      <div><dt>Last packet (monotonic ns)</dt><dd>{diagnostics.lastPacketMonotonicNanos ?? 'Not received'}</dd></div>
      <div><dt>Last valid frame (monotonic ns)</dt><dd>{diagnostics.lastValidFrameMonotonicNanos ?? 'Not received'}</dd></div>
      {counters.map(([key, label]) => <div key={key}><dt>{label}</dt><dd>{diagnostics[key] ?? 'Unavailable'}</dd></div>)}
    </dl>
  </div>
}
