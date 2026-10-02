import { useEffect, useState } from 'react'

import { AlarmHistory } from './components/AlarmHistory'
import { AppShell, type AppView } from './components/AppShell'
import { MonitoringDashboard } from './components/MonitoringDashboard'
import { SettingsPanel } from './components/SettingsPanel'
import type { MonitoringRuntime } from './state/MonitoringRuntime'
import type { MonitoringStore } from './state/MonitoringStore'

interface AppProps {
  store: MonitoringStore
  runtime: MonitoringRuntime
}

function App({ store, runtime }: AppProps) {
  const [activeView, setActiveView] = useState<AppView>('monitor')

  useEffect(() => runtime.acquire(), [runtime])

  return (
    <AppShell activeView={activeView} onViewChange={setActiveView}>
      {activeView === 'monitor' && <MonitoringDashboard store={store} />}
      {activeView === 'alarms' && <AlarmHistory store={store} />}
      {activeView === 'settings' && <SettingsPanel store={store} />}
    </AppShell>
  )
}

export default App
