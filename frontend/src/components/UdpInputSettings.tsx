import { useEffect, useState } from 'react'
import { validateUdpProfiles } from '../config/udpInputProfile'
import type { FieldError, UdpDiagnostics, UdpInputProfileSet } from '../domain/udpInput'
import { BackendApiClient, BackendApiError } from '../services/BackendApiClient'
import { UdpCameraSettingsCard } from './UdpCameraSettingsCard'

export function UdpInputSettings({ api, deviceId = 'simulator-1' }: { api: BackendApiClient; deviceId?: string }) {
  const [draft, setDraft] = useState<UdpInputProfileSet | null>(null)
  const [savedRevision, setSavedRevision] = useState(0); const [appliedRevision, setAppliedRevision] = useState(0)
  const [serverErrors, setServerErrors] = useState<FieldError[]>([]); const [message, setMessage] = useState('')
  const [diagnostics, setDiagnostics] = useState<Partial<Record<'A' | 'B', UdpDiagnostics>>>({})
  useEffect(() => { const abort = new AbortController(); void api.getUdpConfiguration(deviceId, abort.signal).then((state) => {
    setDraft(state.configuration); setSavedRevision(state.savedRevision); setAppliedRevision(state.appliedRevision)
  }).catch((error: unknown) => { if (!(error instanceof DOMException && error.name === 'AbortError')) setMessage('Unable to load UDP settings.') })
    const timer = window.setInterval(() => { for (const camera of ['A','B'] as const) void api.getUdpDiagnostics(deviceId, camera, abort.signal).then((value) => setDiagnostics((old) => ({ ...old, [camera]: value }))).catch(() => undefined) }, 2000)
    return () => { abort.abort(); window.clearInterval(timer) }
  }, [api, deviceId])
  if (!draft) return <section className="panel udp-settings"><p>{message || 'Loading UDP input settings…'}</p></section>
  const errors = [...validateUdpProfiles(draft), ...serverErrors]
  async function save() { if (!draft || validateUdpProfiles(draft).length) return; setServerErrors([]); try { const state = await api.saveUdpConfiguration(deviceId, draft); setSavedRevision(state.savedRevision); setAppliedRevision(state.appliedRevision); setMessage('UDP settings saved. Apply is still required.') } catch (error) { handle(error) } }
  async function apply() { try { const state = await api.applyUdpConfiguration(deviceId, savedRevision, savedRevision); setAppliedRevision(state.appliedRevision); setMessage(`Applied revision ${state.appliedRevision}.`) } catch (error) { handle(error) } }
  function handle(error: unknown) { if (error instanceof BackendApiError) { setServerErrors(error.fieldErrors); setMessage(error.code === 'CONFLICT' ? 'Saved revision changed. Reload and try again.' : error.message) } else setMessage('UDP operation failed.') }
  return <section className="udp-settings" aria-labelledby="udp-input-title"><div className="page-heading"><div><p className="eyebrow">NETWORK INPUT</p><h2 id="udp-input-title">UDP Input Settings</h2><p>Backend validation is authoritative. Save does not apply.</p></div><div className="revision-badges"><span>Saved r{savedRevision}</span><span>Applied r{appliedRevision}</span></div></div><div className="udp-grid"><UdpCameraSettingsCard camera="A" profile={draft.cameraA} errors={errors} diagnostics={diagnostics.A} onChange={(cameraA) => { setDraft({ ...draft, cameraA }); setServerErrors([]) }} /><UdpCameraSettingsCard camera="B" profile={draft.cameraB} errors={errors} diagnostics={diagnostics.B} onChange={(cameraB) => { setDraft({ ...draft, cameraB }); setServerErrors([]) }} /></div><div className="form-actions"><button className="secondary-button" type="button" onClick={() => void save()} disabled={validateUdpProfiles(draft).length > 0}>Save UDP settings</button><button className="primary-button" type="button" onClick={() => void apply()} disabled={savedRevision === appliedRevision}>Apply revision</button></div>{message && <p className="save-confirmation" role="status">{message}</p>}</section>
}
