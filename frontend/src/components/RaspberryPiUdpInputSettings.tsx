import { cloneElement, useEffect, useRef, useState, type FormEvent, type ReactElement } from 'react'
import { createPayloadDraft, validateRaspberryPiUdpInputProfile } from '../config/raspberryPiUdpInputProfile'
import type { FieldError, ImagePayload, NetworkInterfaces, NetworkResolutionPreview, PayloadFormat, RaspberryPiUdpInputProfile, UdpConfigurationState, UdpDiagnostics, UdpNetworkProfile } from '../domain/raspberryPiUdpInput'
import { BackendApiClient, BackendApiError } from '../services/BackendApiClient'
import { PacketInspector } from './PacketInspector'
import { UdpDiagnosticsPanel } from './UdpDiagnosticsPanel'

const limits = [
  ['maxInFlightFrames', 'Max In-flight Frames'], ['maxFrameBytes', 'Max Frame Bytes'],
  ['frameTimeoutMs', 'Frame Timeout (ms)'], ['maxChunkCount', 'Max Chunk Count'], ['maxChunkPayloadBytes', 'Max Chunk Payload Bytes'],
] as const

export function RaspberryPiUdpInputSettings({ api, deviceId = 'filamentvision-1' }: { api: BackendApiClient; deviceId?: string }) {
  const [state, setState] = useState<UdpConfigurationState | null>(null)
  const [draft, setDraft] = useState<RaspberryPiUdpInputProfile | null>(null)
  const [interfaces, setInterfaces] = useState<NetworkInterfaces | null>(null)
  const [diagnostics, setDiagnostics] = useState<UdpDiagnostics>()
  const [preview, setPreview] = useState<NetworkResolutionPreview>()
  const [serverErrors, setServerErrors] = useState<FieldError[]>([])
  const [message, setMessage] = useState('')
  const [busy, setBusy] = useState(false)
  const lifecycle = useRef<AbortController | null>(null)
  const loadGeneration = useRef(0)

  useEffect(() => {
    const abort = new AbortController(); lifecycle.current = abort
    const generation = ++loadGeneration.current
    let timer = 0
    void Promise.all([api.getRaspberryPiUdpConfiguration(deviceId, abort.signal), api.getNetworkInterfaces(abort.signal)])
      .then(([configuration, network]) => {
        if (abort.signal.aborted || generation !== loadGeneration.current) return
        setState(configuration); setDraft(configuration.configuration); setInterfaces(network)
      }).catch(() => { if (!abort.signal.aborted && generation === loadGeneration.current) setMessage('Unable to load expected-input settings. Reload to retry.') })
    async function poll() {
      try {
        const value = await api.getRaspberryPiUdpDiagnostics(deviceId, abort.signal)
        if (!abort.signal.aborted) setDiagnostics(value)
      } catch { if (!abort.signal.aborted) setDiagnostics(undefined) }
      if (!abort.signal.aborted) timer = window.setTimeout(() => void poll(), 2000)
    }
    void poll()
    return () => { abort.abort(); window.clearTimeout(timer) }
  }, [api, deviceId])

  function handleError(error: unknown) {
    if (error instanceof BackendApiError) {
      setServerErrors(error.fieldErrors)
      setMessage(error.code === 'CONFLICT' ? 'Saved revision changed. Reload expected input and review before retrying.' : error.message)
    } else setMessage('Expected-input operation failed. Please retry.')
  }

  async function operation(kind: 'save' | 'apply' | 'reload' | 'resolve') {
    const abort = lifecycle.current
    if (busy || !abort || abort.signal.aborted) return
    if (kind === 'reload') ++loadGeneration.current
    setBusy(true); setServerErrors([]); setMessage('')
    try {
      let next: UdpConfigurationState
      if (kind === 'resolve') {
        if (!draft) return
        const value = await api.previewRaspberryPiUdpNetwork(deviceId, draft.network, abort.signal)
        if (!abort.signal.aborted) { setPreview(value); setMessage(value.reapplyRequired ? 'Resolution changed. Re-Apply required.' : 'Resolution preview refreshed. Not Applied.') }
        return
      } else if (kind === 'reload') {
        const [configuration, network] = await Promise.all([
          api.getRaspberryPiUdpConfiguration(deviceId, abort.signal), api.getNetworkInterfaces(abort.signal),
        ])
        next = configuration
        if (!abort.signal.aborted) { setInterfaces(network); setDraft(next.configuration) }
      } else {
        if (!state || !draft) return
        next = kind === 'save'
          ? await api.saveRaspberryPiUdpConfiguration(deviceId, draft, state.savedRevision, abort.signal)
          : await api.applyRaspberryPiUdpConfiguration(deviceId, state.savedRevision, state.savedRevision,
            state.appliedRevision, state.resolutionGeneration, state.reapplyRequired || preview?.reapplyRequired === true, abort.signal)
        if (!abort.signal.aborted) {
          if (kind === 'save') setDraft(next.configuration)
          if (kind === 'apply') setPreview(undefined)
          setMessage(kind === 'save' ? 'Expected input saved. Apply the saved revision to change Backend parsing.' : `Backend expected input applied at revision ${next.appliedRevision}.`)
        }
      }
      if (!abort.signal.aborted) setState(next)
      if (kind === 'apply') {
        const network = await api.getNetworkInterfaces(abort.signal)
        if (!abort.signal.aborted) setInterfaces(network)
      }
    } catch (error) { if (!abort.signal.aborted) handleError(error) }
    finally { if (!abort.signal.aborted) setBusy(false) }
  }

  if (!draft || !state) return <section className="panel udp-settings" aria-label="Raspberry Pi UDP Input">
    <p role="status">{message || 'Loading Raspberry Pi expected input…'}</p>
    <button type="button" className="secondary-button" disabled={busy} onClick={() => void operation('reload')}>Reload expected input</button>
  </section>

  const errors = [...validateRaspberryPiUdpInputProfile(draft), ...serverErrors]
  const error = (path: string) => errors.filter((item) => item.path === path).map((item) => item.message).join('; ')
  const set = <K extends keyof RaspberryPiUdpInputProfile>(key: K, value: RaspberryPiUdpInputProfile[K]) => setDraft({ ...draft, [key]: value })
  const setNetwork = (patch: Partial<UdpNetworkProfile>) => set('network', { ...draft.network, ...patch })
  const updatePayload = (patch: object) => set('payload', { ...draft.payload, ...patch } as ImagePayload)
  const eligible = interfaces?.interfaces.filter((item) => item.isUp && !item.isLoopback && item.ipv4 !== '0.0.0.0') ?? []
  const selection = draft.network.bind
  const selected = selection.mode === 'SPECIFIC_INTERFACE' ? eligible.find((item) => item.id === selection.interfaceId && item.ipv4 === selection.ipv4) : undefined
  const appliedTarget = state.appliedEffectiveNetworkPlan?.resolvedDestinationIpv4
  const knownPaths = ['enabled', 'network.listenPort', 'network.destination.hostOrIpv4', 'network.sourceFilter.hostOrIpv4', 'network.bind.interfaceId', 'network.bind.ipv4', 'payload.format',
    ...(draft.payload.format === 'JPEG' ? ['payload.maxFrameBytes', 'payload.expectedWidth', 'payload.expectedHeight'] : ['payload.width', 'payload.height']),
    ...(draft.payload.format === 'BINARY' ? ['payload.packing'] : []), ...(draft.payload.format === 'RGB888' ? ['payload.channelOrder'] : []),
    'envelope.headerHex', 'envelope.footerHex', ...limits.map(([key]) => `transportLimits.${key}`)]
  function submit(event: FormEvent<HTMLFormElement>) { event.preventDefault(); void operation('save') }

  return <form className="panel udp-settings raspberry-pi-settings" aria-labelledby="raspberry-pi-input-title" onSubmit={submit} noValidate>
    <div className="panel-heading"><div><p className="panel-kicker">BACKEND EXPECTED INPUT</p><h2 id="raspberry-pi-input-title">Raspberry Pi UDP Input</h2></div><div className="revision-badges"><span>Saved r{state.savedRevision}</span><span>Applied r{state.appliedRevision}</span></div></div>
    <p className="udp-explanation">These settings define Backend expected-input protocol configuration. Apply does not remotely configure Raspberry Pi output. Match the sender's output to these values. Backend validation is authoritative; field feedback is immediate.</p>
    <div className="udp-revisions"><ProfileSummary title="Saved expected input" profile={state.configuration} /><ProfileSummary title="Applied expected input" profile={state.appliedConfiguration} /></div>
    <fieldset className="udp-section" disabled={busy}><legend><h3>Network</h3></legend>
      <Field label="Enabled" path="enabled" error={error('enabled')}><input type="checkbox" checked={draft.enabled} onChange={(event) => set('enabled', event.target.checked)} /></Field>
      <Field label="Backend Destination Host/IP" path="network.destination.hostOrIpv4" error={error('network.destination.hostOrIpv4')}>
        <input value={draft.network.destination.mode === 'MANUAL' ? draft.network.destination.hostOrIpv4 : ''}
          placeholder="AUTO" onChange={(event) => setNetwork({ destination: event.target.value ? { mode: 'MANUAL', hostOrIpv4: event.target.value } : { mode: 'AUTO' } })} />
      </Field>
      <Field label="Raspberry Pi Source Host/IP" path="network.sourceFilter.hostOrIpv4" error={error('network.sourceFilter.hostOrIpv4')} hint="Empty accepts any IPv4 sender.">
        <input value={draft.network.sourceFilter.mode === 'IP_OR_HOST' ? draft.network.sourceFilter.hostOrIpv4 : ''}
          onChange={(event) => setNetwork({ sourceFilter: event.target.value ? { mode: 'IP_OR_HOST', hostOrIpv4: event.target.value } : { mode: 'ANY' } })} />
      </Field>
      <NumberField label="UDP Listen Port" path="network.listenPort" value={draft.network.listenPort} error={error('network.listenPort')} onChange={(value) => setNetwork({ listenPort: value })} />
      <div className="form-actions"><button type="button" className="secondary-button" onClick={() => void operation('resolve')}>Resolve / Refresh</button></div>
      {preview && <div className="udp-preview" aria-label="Resolution preview"><strong>Not Applied</strong><p>{preview.reapplyRequired ? 'RESOLUTION_CHANGED · REAPPLY_REQUIRED' : 'Resolution unchanged'}</p><p>Resolved destination: {preview.candidatePlan?.resolvedDestinationIpv4 ?? preview.errorCode ?? 'Not required'}</p></div>}
      <p className="udp-muted">Actual Bind Address: <output>{state.appliedEffectiveNetworkPlan?.actualBindIpv4 ?? 'Inactive'}</output></p>
      <p className="udp-muted">Resolved Destination IPv4: <output>{appliedTarget ?? 'Inactive'}</output></p>
      <p className="udp-muted">Resolved Source IPv4(s): <output>{state.appliedEffectiveNetworkPlan?.resolvedSourceIpv4s.join(', ') || 'Any'}</output></p>
      <p className="udp-target">{appliedTarget ? `Raspberry Pi should send UDP to: ${appliedTarget}:${state.appliedConfiguration.network.listenPort}` : 'No applied destination. Resolve, Save and Apply a valid network configuration.'}</p>
      <details className="udp-advanced"><summary>Advanced Network</summary>
        <Field label="Bind Mode" path="network.bind.mode" error={error('network.bind.mode')}><select value={selection.mode} onChange={(event) => {
          const mode = event.target.value
          if (mode === 'AUTO') setNetwork({ bind: { mode: 'AUTO' } })
          else if (mode === 'ALL_INTERFACES') setNetwork({ bind: { mode: 'ALL_INTERFACES' } })
          else if (mode === 'MANUAL_IP') setNetwork({ bind: { mode: 'MANUAL_IP', ipv4: '' } })
          else { const first = eligible[0]; setNetwork({ bind: { mode: 'SPECIFIC_INTERFACE', interfaceId: first?.id ?? '', ipv4: first?.ipv4 ?? '' } }) }
        }}><option>AUTO</option><option>ALL_INTERFACES</option><option>SPECIFIC_INTERFACE</option><option>MANUAL_IP</option></select></Field>
        {selection.mode === 'SPECIFIC_INTERFACE' && <Field label="Specific Interface" path="network.bind.interfaceId" error={[error('network.bind.interfaceId'), error('network.bind.ipv4')].filter(Boolean).join('; ')}><select value={`${selection.interfaceId}|${selection.ipv4}`} onChange={(event) => {
          const item = eligible.find((candidate) => `${candidate.id}|${candidate.ipv4}` === event.target.value)
          if (item) setNetwork({ bind: { mode: 'SPECIFIC_INTERFACE', interfaceId: item.id, ipv4: item.ipv4 } })
        }}>{eligible.map((item) => <option key={`${item.id}|${item.ipv4}`} value={`${item.id}|${item.ipv4}`}>{item.displayName} · {item.ipv4}{item.isVirtual ? ' (virtual)' : ''}</option>)}{!selected && <option value={`${selection.interfaceId}|${selection.ipv4}`}>Selected interface unavailable</option>}</select></Field>}
        {selection.mode === 'MANUAL_IP' && <Field label="Manual Bind IPv4" path="network.bind.ipv4" error={error('network.bind.ipv4')}><input value={selection.ipv4} onChange={(event) => setNetwork({ bind: { mode: 'MANUAL_IP', ipv4: event.target.value } })} /></Field>}
        <Field label="Destination Mode" path="network.destination.mode"><select value={draft.network.destination.mode} onChange={(event) => setNetwork({ destination: event.target.value === 'AUTO' ? { mode: 'AUTO' } : { mode: 'MANUAL', hostOrIpv4: '' } })}><option>AUTO</option><option>MANUAL</option></select></Field>
        <Field label="Source Filter Mode" path="network.sourceFilter.mode"><select value={draft.network.sourceFilter.mode} onChange={(event) => setNetwork({ sourceFilter: event.target.value === 'ANY' ? { mode: 'ANY' } : { mode: 'IP_OR_HOST', hostOrIpv4: '' } })}><option>ANY</option><option>IP_OR_HOST</option></select></Field>
      </details>
    </fieldset>
    <fieldset className="udp-section" disabled={busy}><legend><h3>Camera Stream</h3></legend>
      <Field label="Payload Format" path="payload.format" error={error('payload.format')} hint="JPEG is the recommended default expected image format."><select value={draft.payload.format} onChange={(event) => set('payload', createPayloadDraft(event.target.value as PayloadFormat))}>{['JPEG', 'GRAY8', 'BINARY', 'RGB888'].map((format) => <option key={format}>{format}</option>)}</select></Field>
      {draft.payload.format === 'JPEG' ? <>
        <NumberField label="JPEG Max Frame Bytes" path="payload.maxFrameBytes" value={draft.payload.maxFrameBytes} error={error('payload.maxFrameBytes')} onChange={(value) => updatePayload({ maxFrameBytes: value })} />
        <NumberField label="Expected Width" path="payload.expectedWidth" value={draft.payload.expectedWidth} error={error('payload.expectedWidth')} optional onChange={(value) => updatePayload({ expectedWidth: value })} />
        <NumberField label="Expected Height" path="payload.expectedHeight" value={draft.payload.expectedHeight} error={error('payload.expectedHeight')} optional onChange={(value) => updatePayload({ expectedHeight: value })} />
      </> : <>
        <NumberField label="Width" path="payload.width" value={draft.payload.width} error={error('payload.width')} onChange={(value) => updatePayload({ width: value })} />
        <NumberField label="Height" path="payload.height" value={draft.payload.height} error={error('payload.height')} onChange={(value) => updatePayload({ height: value })} />
      </>}
      {draft.payload.format === 'BINARY' && <Field label="Binary Packing" path="payload.packing" error={error('payload.packing')}><select value={draft.payload.packing} onChange={(event) => updatePayload({ packing: event.target.value })}><option>BYTE_PER_PIXEL</option><option>BIT_PACKED</option></select></Field>}
      {draft.payload.format === 'RGB888' && <Field label="Channel Order" path="payload.channelOrder" error={error('payload.channelOrder')}><select value={draft.payload.channelOrder} onChange={(event) => updatePayload({ channelOrder: event.target.value })}><option>RGB</option><option>BGR</option></select></Field>}
    </fieldset>
    <fieldset className="udp-section" disabled={busy}><legend><h3>Advanced Transport</h3></legend>
      <p>Structured Chunked · protocol v1</p><p className="udp-muted">Datagram layout: [2-byte Header][34-byte Structured Header][Image Chunk Payload][2-byte Footer].</p>
      {limits.map(([key, label]) => <NumberField key={key} label={label} path={`transportLimits.${key}`} value={draft.transportLimits[key]} error={error(`transportLimits.${key}`)} onChange={(value) => set('transportLimits', { ...draft.transportLimits, [key]: value })} />)}
      {(['headerHex', 'footerHex'] as const).map((part) => <Field key={part} label={`Expected ${part === 'headerHex' ? 'Header' : 'Footer'} (2-byte HEX)`} path={`envelope.${part}`} error={error(`envelope.${part}`)} hint="Exactly two HEX bytes, e.g. AA 01. Backend normalizes accepted values."><input value={draft.envelope[part]} onChange={(event) => set('envelope', { ...draft.envelope, [part]: event.target.value })} /></Field>)}
    </fieldset>
    {errors.filter((item) => !knownPaths.includes(item.path)).map((item, index) => <p key={`${item.path}-${index}`} className="field-error" role="alert">{item.path}: {item.message}</p>)}
    <div className="form-actions"><button className="secondary-button" type="button" disabled={busy} onClick={() => void operation('reload')}>Reload expected input</button><button className="secondary-button" type="submit" disabled={busy}>Save expected input</button><button className="primary-button" type="button" disabled={busy || state.savedRevision === state.appliedRevision && !state.reapplyRequired && !preview?.reapplyRequired} onClick={() => void operation('apply')}>{state.savedRevision === state.appliedRevision ? 'Re-Apply network resolution' : 'Apply saved revision'}</button></div>
    <p className="udp-muted">Save persists a draft without altering the receiver. Apply uses only the saved revision; unsaved edits are excluded.</p>
    {message && <p className="save-confirmation" role="status">{message}</p>}
    <section className="udp-section"><h3>Diagnostics</h3><UdpDiagnosticsPanel diagnostics={diagnostics} /></section>
    <section className="udp-section"><h3>Packet Inspector</h3><PacketInspector api={api} deviceId={deviceId} /></section>
  </form>
}

function ProfileSummary({ title, profile }: { title: string; profile: RaspberryPiUdpInputProfile }) {
  const payload = profile.payload
  const values: [string, string | number][] = [
    ['Bind mode', profile.network.bind.mode],
    ['Backend destination', profile.network.destination.mode === 'MANUAL' ? profile.network.destination.hostOrIpv4 : 'AUTO'],
    ['Expected Raspberry Pi source', profile.network.sourceFilter.mode === 'IP_OR_HOST' ? profile.network.sourceFilter.hostOrIpv4 : 'Any IPv4 sender'],
    ...limits.map(([key, label]): [string, number] => [label, profile.transportLimits[key]]),
  ]
  if (payload.format === 'JPEG') values.push(['JPEG Max Frame Bytes', payload.maxFrameBytes], ['Expected Width', payload.expectedWidth ?? 'Any'], ['Expected Height', payload.expectedHeight ?? 'Any'])
  else values.push(['Width', payload.width], ['Height', payload.height])
  if (payload.format === 'BINARY') values.push(['Binary Packing', payload.packing])
  if (payload.format === 'RGB888') values.push(['Channel Order', payload.channelOrder])
  return <section aria-label={title} className="udp-profile-summary"><h3>{title}</h3><p>{profile.enabled ? 'Enabled' : 'Disabled'} · {payload.format} · port {profile.network.listenPort} · Header {profile.envelope.headerHex} · Footer {profile.envelope.footerHex}</p><details><summary>All expected-input values</summary><dl className="udp-diagnostics">{values.map(([label, value]) => <div key={label}><dt>{label}</dt><dd>{value}</dd></div>)}</dl></details></section>
}
function Field({ label, path, error, hint, children }: { label: string; path: string; error?: string; hint?: string; children: ReactElement<{ id?: string; 'aria-invalid'?: boolean; 'aria-describedby'?: string }> }) {
  return <div className="udp-field"><label htmlFor={`udp-${path}`}>{label}</label><div className="udp-control">
    {cloneElement(children, { id: `udp-${path}`, 'aria-invalid': Boolean(error), 'aria-describedby': error || hint ? `udp-${path}-feedback` : undefined })}
  </div>{(error || hint) && <small id={`udp-${path}-feedback`} className={error ? 'field-error' : ''}>{error || hint}</small>}</div>
}
function NumberField({ label, path, value, error, ...props }: { label: string; path: string; value: number | null | undefined; error?: string } & ({ optional: true; onChange: (value: number | null) => void } | { optional?: false; onChange: (value: number) => void })) {
  return <Field label={label} path={path} error={error}><input type="number" step="1" value={value ?? ''} onChange={(event) => {
    if (props.optional && event.target.value === '') props.onChange(null)
    else props.onChange(Number(event.target.value))
  }} /></Field>
}
