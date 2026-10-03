import { useEffect, useState } from 'react'

import { AlarmHistory } from './components/AlarmHistory'
import { AppShell, type AppView } from './components/AppShell'
import { MonitoringDashboard } from './components/MonitoringDashboard'
import { SettingsPanel } from './components/SettingsPanel'
import type { MonitoringRuntime } from './state/MonitoringRuntime'
import type { MonitoringStore } from './state/MonitoringStore'
import type { BackendApiClient } from './services/BackendApiClient'

interface AppProps {
  store: MonitoringStore
  runtime: MonitoringRuntime
  api?: BackendApiClient
}

function App({ store, runtime, api }: AppProps) {
  const [activeView, setActiveView] = useState<AppView>('monitor')

  useEffect(() => runtime.acquire(), [runtime])

  return (
    <AppShell activeView={activeView} onViewChange={setActiveView}>
      {activeView === 'monitor' && <MonitoringDashboard store={store} api={api} />}
      {activeView === 'alarms' && <AlarmHistory store={store} />}
      {activeView === 'settings' && <SettingsPanel store={store} api={api} />}
    </AppShell>
  )
}

export default App
