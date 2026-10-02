import { StrictMode, useEffect } from 'react'
import { act, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import {
  DEFAULT_MONITORING_CONFIGURATION,
  type ConfigurationStorage,
} from '../config/monitoringConfig'
import type { MonitoringConfiguration } from '../domain/monitoring'
import { MockFilamentDataSource } from '../services/MockFilamentDataSource'
import { MonitoringRuntime } from './MonitoringRuntime'
import { MonitoringStore } from './MonitoringStore'
import { useLiveMonitoring } from './useMonitoringStore'

class MemoryConfigurationStorage implements ConfigurationStorage {
  load(): MonitoringConfiguration {
    return { ...DEFAULT_MONITORING_CONFIGURATION }
  }

  save(): void {}

  reset(): MonitoringConfiguration {
    return this.load()
  }
}

describe('MonitoringRuntime under React StrictMode', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.setSystemTime(1_000)
  })

  afterEach(() => {
    vi.clearAllTimers()
    vi.useRealTimers()
  })

  it('keeps one producer and subscription, then cleans everything on unmount', async () => {
    const source = new MockFilamentDataSource()
    const store = new MonitoringStore(source, new MemoryConfigurationStorage())
    const runtime = new MonitoringRuntime(store)

    function Harness() {
      const live = useLiveMonitoring(store)
      useEffect(() => runtime.acquire(), [])
      return <output aria-label="sample count">{live.recentTrend.length}</output>
    }

    const view = render(
      <StrictMode>
        <Harness />
      </StrictMode>,
    )

    expect(source.totalProducerStarts).toBe(1)
    expect(source.activeProducerCount).toBe(1)
    expect(source.subscriberCount).toBe(1)
    expect(source.stateSubscriberCount).toBe(1)
    expect(store.diagnostics.liveSubscriberCount).toBe(1)
    expect(vi.getTimerCount()).toBe(1)

    act(() => vi.advanceTimersByTime(250))
    expect(screen.getByLabelText('sample count')).toHaveTextContent('3')

    view.unmount()
    await act(async () => Promise.resolve())

    expect(source.activeProducerCount).toBe(0)
    expect(source.subscriberCount).toBe(0)
    expect(source.stateSubscriberCount).toBe(0)
    expect(store.diagnostics.liveSubscriberCount).toBe(0)
    expect(vi.getTimerCount()).toBe(0)

    runtime.dispose()
  })
})
