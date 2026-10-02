import { useSyncExternalStore } from 'react'

import type { MonitoringStore } from './MonitoringStore'

export function useLiveMonitoring(store: MonitoringStore) {
  return useSyncExternalStore(
    store.subscribeLive,
    store.getLiveSnapshot,
    store.getLiveSnapshot,
  )
}

export function useAlarmHistory(store: MonitoringStore) {
  return useSyncExternalStore(
    store.subscribeAlarms,
    store.getAlarmSnapshot,
    store.getAlarmSnapshot,
  )
}

export function useMonitoringConfiguration(store: MonitoringStore) {
  return useSyncExternalStore(
    store.subscribeConfiguration,
    store.getConfigurationSnapshot,
    store.getConfigurationSnapshot,
  )
}
