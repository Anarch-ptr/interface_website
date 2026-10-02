import { useState, type FormEvent } from 'react'

import { DEFAULT_MONITORING_CONFIGURATION, validateMonitoringConfiguration } from '../config/monitoringConfig'
import type { MonitoringConfiguration } from '../domain/monitoring'
import type { MonitoringStore } from '../state/MonitoringStore'
import { useMonitoringConfiguration } from '../state/useMonitoringStore'
import type { BackendApiClient } from '../services/BackendApiClient'
import { UdpInputSettings } from './UdpInputSettings'

export function SettingsPanel({ store, api }: { store: MonitoringStore; api?: BackendApiClient }) {
  const { configuration } = useMonitoringConfiguration(store)
  const [target, setTarget] = useState(configuration.targetDiameterMm.toString())
  const [warning, setWarning] = useState(configuration.warningDeviationMm.toString())
  const [outOfTolerance, setOutOfTolerance] = useState(configuration.outOfToleranceDeviationMm.toString())
  const [saved, setSaved] = useState(false)
  const draft: MonitoringConfiguration = { ...configuration, targetDiameterMm: Number(target), warningDeviationMm: Number(warning), outOfToleranceDeviationMm: Number(outOfTolerance) }
  const validation = validateMonitoringConfiguration(draft)

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!validation.valid) return
    store.updateConfiguration(draft)
    setSaved(true)
  }

  function handleReset() {
    store.resetConfiguration()
    setTarget(DEFAULT_MONITORING_CONFIGURATION.targetDiameterMm.toString())
    setWarning(DEFAULT_MONITORING_CONFIGURATION.warningDeviationMm.toString())
    setOutOfTolerance(DEFAULT_MONITORING_CONFIGURATION.outOfToleranceDeviationMm.toString())
    setSaved(false)
  }

  return (
    <section className="page" aria-labelledby="settings-title">
      <div className="page-heading"><div><p className="eyebrow">LOCAL CONFIGURATION</p><h2 id="settings-title">Settings</h2><p>Absolute thresholds match Android behaviour and can later move behind a server-backed configuration adapter.</p></div></div>
      <div className="settings-layout">
        <form className="panel settings-form" onSubmit={handleSubmit}>
          <div className="panel-heading"><div><p className="panel-kicker">DIAMETER LIMITS</p><h3>Monitoring configuration</h3></div></div>
          <SettingField label="Target diameter (mm)" value={target} onChange={(value) => { setTarget(value); setSaved(false) }} error={validation.errors.targetDiameterMm} hint="Nominal filament diameter used as the centre line." />
          <SettingField label="Warning deviation (mm)" value={warning} onChange={(value) => { setWarning(value); setSaved(false) }} error={validation.errors.warningDeviationMm} hint={percentHint(draft.warningDeviationMm, draft.targetDiameterMm)} />
          <SettingField label="Out-of-tolerance deviation (mm)" value={outOfTolerance} onChange={(value) => { setOutOfTolerance(value); setSaved(false) }} error={validation.errors.outOfToleranceDeviationMm} hint={percentHint(draft.outOfToleranceDeviationMm, draft.targetDiameterMm)} />
          <div className="form-actions"><button type="button" className="secondary-button" onClick={handleReset}>Restore Android defaults</button><button type="submit" className="primary-button" disabled={!validation.valid}>Save settings</button></div>
          {saved && <p className="save-confirmation">Settings saved locally.</p>}
        </form>
        <aside className="panel settings-reference">
          <p className="panel-kicker">ANDROID REFERENCE</p><h3>Default alarm behaviour</h3>
          <div className="reference-threshold warning"><span>Warning</span><strong>±0.012 mm</strong><small>≈ 0.69% at 1.750 mm</small></div>
          <div className="reference-threshold critical"><span>OUT OF TOLERANCE</span><strong>±0.025 mm</strong><small>≈ 1.43% at 1.750 mm</small></div>
          <p>“Critical” is presentation copy only. The shared domain and future transport contract use <code>OUT_OF_TOLERANCE</code>.</p>
        </aside>
      </div>
      {api && <UdpInputSettings api={api} />}
    </section>
  )
}

function SettingField({ label, value, onChange, error, hint }: { label: string; value: string; onChange: (value: string) => void; error?: string; hint: string }) {
  return <label className="setting-field"><span>{label}</span><input aria-label={label} type="number" step="0.001" min="0" value={value} aria-invalid={error ? 'true' : 'false'} onChange={(event) => onChange(event.target.value)} /><small className={error ? 'field-error' : ''}>{error ?? hint}</small></label>
}

function percentHint(deviation: number, target: number): string {
  if (!Number.isFinite(deviation) || !Number.isFinite(target) || target <= 0) return 'Enter a valid target and deviation.'
  return `Equivalent to ±${((deviation / target) * 100).toFixed(2)}% of target.`
}
