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
  return <div aria-label="UDP diagnostics">
    <p role="status">{diagnostics.status}{diagnostics.statusDetail ? ` · ${diagnostics.statusDetail}` : ''}</p>
    <dl className="udp-diagnostics">
      <div><dt>Last packet source</dt><dd>{diagnostics.lastPacketSourceIpv4 === null ? 'Not received' : `${diagnostics.lastPacketSourceIpv4}:${diagnostics.lastPacketSourcePort ?? 'unknown'}`}</dd></div>
      <div><dt>Last packet time</dt><dd>{diagnostics.lastPacketEpochMillis === null ? 'Not received' : new Date(diagnostics.lastPacketEpochMillis).toISOString()}</dd></div>
      <div><dt>Last packet (monotonic ns)</dt><dd>{diagnostics.lastPacketMonotonicNanos ?? 'Not received'}</dd></div>
      <div><dt>Last valid frame (monotonic ns)</dt><dd>{diagnostics.lastValidFrameMonotonicNanos ?? 'Not received'}</dd></div>
      {counters.map(([key, label]) => <div key={key}><dt>{label}</dt><dd>{diagnostics[key] ?? 'Unavailable'}</dd></div>)}
    </dl>
  </div>
}
