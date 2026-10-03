import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import App from './App'
import { LocalConfigurationStorage } from './config/monitoringConfig'
import { BackendFilamentDataSource } from './services/BackendFilamentDataSource'
import { MonitoringRuntime } from './state/MonitoringRuntime'
import { MonitoringStore } from './state/MonitoringStore'
import { BackendApiClient } from './services/BackendApiClient'
import './styles.css'

const source = new BackendFilamentDataSource()
const configurationStorage = new LocalConfigurationStorage(window.localStorage)
const store = new MonitoringStore(source, configurationStorage)
const runtime = new MonitoringRuntime(store)
const api = new BackendApiClient()

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App store={store} runtime={runtime} api={api} />
  </StrictMode>,
)

window.addEventListener('pagehide', () => runtime.dispose(), { once: true })
if (import.meta.hot) import.meta.hot.dispose(() => runtime.dispose())
