import { useEffect, useRef, useState } from 'react'
import type { InspectorLease, InspectorPreviewBytes, InspectorSnapshot } from '../domain/raspberryPiUdpInput'
import { BackendApiClient, BackendApiError } from '../services/BackendApiClient'

export function PacketInspector({ api, deviceId }: { api: BackendApiClient; deviceId: string }) {
  const [open, setOpen] = useState(false)
  const [lease, setLease] = useState<InspectorLease | null>(null)
  const [previewBytes, setPreviewBytes] = useState<InspectorPreviewBytes>(128)
  const [display, setDisplay] = useState<InspectorSnapshot | null>(null)
  const [frozen, setFrozen] = useState(false)
  const [error, setError] = useState('')
  const latest = useRef<InspectorSnapshot | null>(null)
  const frozenRef = useRef(false)

  useEffect(() => {
    if (!open) return
    const abort = new AbortController()
    let current: InspectorLease | null = null; let timer = 0; let etag: string | undefined
    const release = (value: InspectorLease) => api.releaseRaspberryPiInspectorLease(deviceId, value.leaseId).catch(() => undefined)
    async function acquire() {
      const value = await api.createRaspberryPiInspectorLease(deviceId, previewBytes, abort.signal)
      if (abort.signal.aborted) { await release(value); return false }
      current = value; etag = undefined; setLease(value)
      return true
    }
    async function poll() {
      if (abort.signal.aborted || !current) return
      try {
        const result = await api.getRaspberryPiInspector(deviceId, current.leaseId, previewBytes, etag, abort.signal)
        if (abort.signal.aborted) return
        etag = result.etag ?? etag
        setError('')
        if (!result.unchanged) {
          latest.current = result.snapshot
          if (!frozenRef.current) setDisplay(result.snapshot)
        }
      } catch (reason) {
        if (abort.signal.aborted) return
        if (reason instanceof BackendApiError && reason.status === 404) {
          if (current.expiresAtMillis > Date.now()) {
            // Backend also returns 404 for a valid lease that has no captured Datagram yet.
            setError(''); latest.current = null
            if (!frozenRef.current) setDisplay(null)
          } else {
            const expired = current; current = null
            await release(expired)
            if (abort.signal.aborted) return
            try { if (!await acquire()) return }
            catch { if (!abort.signal.aborted) setError('Unable to renew Inspector lease. Close and reopen to retry.'); return }
          }
        } else setError('Inspector polling failed. Retrying…')
      }
      if (!abort.signal.aborted) timer = window.setTimeout(() => void poll(), 500)
    }
    void acquire().then((active) => { if (active) void poll() })
      .catch(() => { if (!abort.signal.aborted) setError('Unable to start Inspector. Close and reopen to retry.') })
    return () => {
      abort.abort(); window.clearTimeout(timer)
      if (current) void release(current)
    }
  }, [api, deviceId, open, previewBytes])

  function resetDisplay() { latest.current = null; setDisplay(null); setLease(null); setError(''); frozenRef.current = false; setFrozen(false) }
  function toggleFreeze() {
    frozenRef.current = !frozenRef.current; setFrozen(frozenRef.current)
    if (!frozenRef.current) setDisplay(latest.current)
  }
  async function copy(debug: boolean) {
    if (!display) return
    try { await navigator.clipboard.writeText(debug ? JSON.stringify(display, null, 2) : display.rawDatagram.hexPreview) }
    catch { setError('Unable to copy Inspector data.') }
  }
  if (!open) return <button type="button" className="secondary-button inspector-open" onClick={() => { resetDisplay(); setOpen(true) }}>Inspect Packet</button>
  const raw = display?.rawDatagram; const transport = raw?.transport; const frame = display?.parsedFrame
  const match = (value: boolean | null) => value === null ? 'Not checked' : value ? 'Matched' : 'Mismatch'
  return <section className="packet-inspector" aria-label="Packet Inspector">
    <div className="inspector-toolbar"><strong>Packet Inspector</strong><label>Preview size <select aria-label="Inspector preview bytes" value={previewBytes} onChange={(event) => { resetDisplay(); setPreviewBytes(Number(event.target.value) as InspectorPreviewBytes) }}><option value={64}>64 bytes</option><option value={128}>128 bytes</option><option value={256}>256 bytes</option></select></label><button type="button" onClick={toggleFreeze}>{frozen ? 'Resume' : 'Freeze'}</button><button type="button" onClick={() => setOpen(false)}>Close Inspector</button></div>
    <p className="udp-muted">Lease: {lease?.leaseId ?? 'starting'} · Polling up to 2 Hz {frozen ? '· display frozen' : ''}</p>
    {raw ? <>
      <InspectorRegion title="Raw Datagram" status={raw.status} hex={raw.hexPreview}>
        <p>Source: {raw.sourceIpv4}:{raw.sourcePort} · Backend port: {raw.backendListenPort} · Datagram bytes: {raw.datagramSize} · Received (monotonic ns): {raw.receivedAtMonotonicNanos}</p>
        <p>Actual header: {raw.headerHex ?? 'Unavailable'} · Expected header: {raw.expectedHeaderHex} · {match(raw.headerMatched)}</p>
        <p>Actual footer: {raw.footerHex ?? 'Unavailable'} · Expected footer: {raw.expectedFooterHex} · {match(raw.footerMatched)}</p>
        <p>Bounded preview of the complete datagram, including envelope bytes when within the preview limit.</p>
      </InspectorRegion>
      <AppliedNetworkRegion network={raw.appliedNetwork} sourceIpv4={raw.sourceIpv4} sourcePort={raw.sourcePort} />
      <InspectorRegion title="Structured Transport" status={transport?.status ?? 'Not parsed'} hex={transport?.hexPreview ?? ''}>
        {transport ? <dl className="udp-diagnostics">{Object.entries(transport).filter(([key]) => key !== 'hexPreview' && key !== 'status').map(([key, value]) => <div key={key}><dt>{key}</dt><dd>{value}</dd></div>)}</dl> : <p>No parsed structured header; boundary or source mismatch can prevent parsing.</p>}
      </InspectorRegion>
      <InspectorRegion title="Parsed Frame" status={frame?.decodeResult ?? 'Pending'} hex={frame?.hexPreview ?? ''}>
        {frame ? <p>{frame.payloadFormat} · {frame.width ?? '?'} × {frame.height ?? '?'} · Completed frame bytes: {frame.completedFrameBytes} · Received (monotonic ns): {frame.receivedAtMonotonicNanos}</p> : <p>No completed parsed image frame.</p>}
      </InspectorRegion>
      <div className="inspector-actions"><button type="button" onClick={() => void copy(false)}>Copy Hex</button><button type="button" onClick={() => void copy(true)}>Copy Debug Info</button></div>
    </> : <p className="udp-muted">Waiting for UDP Datagram…</p>}
    {error && <p className="field-error" role="alert">{error}</p>}
  </section>
}

function AppliedNetworkRegion({ network, sourceIpv4, sourcePort }: {
  network: InspectorSnapshot['rawDatagram']['appliedNetwork']; sourceIpv4: string; sourcePort: number
}) {
  return <section className="inspector-region" aria-label="Applied Network"><h4>Applied Network</h4>
    {network ? <>
      <p>Destination: {network.configuredDestination ?? 'AUTO'} → {network.resolvedDestinationIpv4 ?? 'Inactive'}</p>
      <p>Bind: {network.actualBindIpv4} · {network.actualBindInterfaceId ?? 'all IPv4 interfaces'}</p>
      <p>Source: {network.configuredSource ?? 'ANY'} → {network.resolvedSourceIpv4s.join(', ') || 'Any IPv4 sender'}</p>
      <p>Generation {network.resolutionGeneration} · Applied {network.appliedAtEpochMillis === null ? 'time unavailable' : new Date(network.appliedAtEpochMillis).toISOString()}</p>
    </> : <p>No applied network provenance captured.</p>}
    <p>Actual sender {sourceIpv4}:{sourcePort}</p>
  </section>
}

function InspectorRegion({ title, status, hex, children }: { title: string; status: string; hex: string; children: React.ReactNode }) {
  return <section className="inspector-region" aria-label={title}><h4>{title}</h4><span>{status}</span>{children}<pre>{hex || 'No bounded preview yet'}</pre></section>
}
