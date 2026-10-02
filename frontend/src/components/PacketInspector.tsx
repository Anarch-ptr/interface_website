import { useEffect, useRef, useState } from 'react'
import type { CameraId, InspectorLease, InspectorSnapshot } from '../domain/udpInput'
import { BackendApiClient, BackendApiError } from '../services/BackendApiClient'

export function PacketInspector({ api, deviceId, camera }: { api: BackendApiClient; deviceId: string; camera: CameraId }) {
  const [open, setOpen] = useState(false); const [lease, setLease] = useState<InspectorLease | null>(null)
  const [previewBytes, setPreviewBytes] = useState<64 | 128 | 256>(128); const [snapshot, setSnapshot] = useState<InspectorSnapshot | null>(null)
  const [display, setDisplay] = useState<InspectorSnapshot | null>(null); const [frozen, setFrozen] = useState(false)
  const [error, setError] = useState(''); const frozenRef = useRef(false); const etag = useRef<string | undefined>(undefined)
  useEffect(() => { frozenRef.current = frozen }, [frozen])

  useEffect(() => {
    if (!open) return
    const abort = new AbortController(); let current: InspectorLease | null = null; let timer = 0
    const poll = async () => {
      if (!current) return
      try {
        const result = await api.getInspector(deviceId, camera, current.leaseId, previewBytes, etag.current, abort.signal)
        if (!result.unchanged) { etag.current = result.etag ?? undefined; setSnapshot(result.snapshot); if (!frozenRef.current) setDisplay(result.snapshot) }
      } catch (reason) {
        if (reason instanceof BackendApiError && reason.status === 404 && !abort.signal.aborted) {
          current = await api.createInspectorLease(deviceId, camera, previewBytes, abort.signal); setLease(current); etag.current = undefined
        } else if (!(reason instanceof DOMException && reason.name === 'AbortError')) setError('Inspector polling failed.')
      }
    }
    void api.createInspectorLease(deviceId, camera, previewBytes, abort.signal).then((value) => {
      current = value; setLease(value); void poll(); timer = window.setInterval(() => void poll(), 500)
    }).catch(() => setError('Unable to start Inspector.'))
    return () => { abort.abort(); if (timer) window.clearInterval(timer); if (current) void api.releaseInspectorLease(deviceId, camera, current.leaseId) }
  }, [api, camera, deviceId, open, previewBytes])

  function toggleFreeze() { if (frozen) { setDisplay(snapshot); setFrozen(false) } else setFrozen(true) }
  async function copyHex() { if (display) await navigator.clipboard.writeText(display.rawDatagram.hexPreview) }
  async function copyDebug() { if (display) await navigator.clipboard.writeText(JSON.stringify(display, null, 2)) }
  if (!open) return <button type="button" className="secondary-button inspector-open" onClick={() => setOpen(true)}>Inspect Packet</button>
  return <section className="packet-inspector" aria-label={`Camera ${camera} Packet Inspector`}>
    <div className="inspector-toolbar"><strong>Packet Inspector</strong><select aria-label={`Camera ${camera} Inspector preview bytes`} value={previewBytes} onChange={(event) => setPreviewBytes(Number(event.target.value) as 64 | 128 | 256)}><option value={64}>64 bytes</option><option value={128}>128 bytes</option><option value={256}>256 bytes</option></select><button type="button" onClick={toggleFreeze}>{frozen ? 'Resume' : 'Freeze'}</button><button type="button" onClick={() => setOpen(false)}>Close Inspector</button></div>
    <p className="udp-muted">Lease: {lease?.leaseId ?? 'starting'} · Live polling 2 Hz {frozen ? '· display frozen' : ''}</p>
    {display ? <><InspectorRegion title="Raw Datagram" status={display.rawDatagram.status} hex={display.rawDatagram.hexPreview} />
      <InspectorRegion title="Parsed Frame" status={display.parsedFrame?.payloadFormat ?? 'Pending'} hex={display.parsedFrame?.hexPreview ?? ''} />
      <div className="inspector-actions"><button type="button" onClick={() => void copyHex()}>Copy Hex</button><button type="button" onClick={() => void copyDebug()}>Copy Debug Info</button></div></> : <p className="udp-muted">Waiting for UDP Datagram…</p>}
    {error && <p className="field-error">{error}</p>}
  </section>
}

function InspectorRegion({ title, status, hex }: { title: string; status: string; hex: string }) {
  return <div className="inspector-region"><h5>{title}</h5><span>{status}</span><pre>{hex || 'No bounded preview yet'}</pre>{title === 'Raw Datagram' && <small>Transport Header · Chunk Payload</small>}{title === 'Parsed Frame' && <small>Application Header · Actual Payload · Application Footer</small>}</div>
}
