import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { DEFAULT_MONITORING_CONFIGURATION } from '../config/monitoringConfig'
import { MeasurementStatus, VisionSourceState } from '../domain/monitoring'
import { MockFilamentDataSource } from './MockFilamentDataSource'

describe('MockFilamentDataSource', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.setSystemTime(1_000)
  })

  afterEach(() => {
    vi.clearAllTimers()
    vi.useRealTimers()
  })

  it('keeps exactly one producer when start is called repeatedly', () => {
    const source = new MockFilamentDataSource()
    const firstSubscriber = vi.fn()
    const secondSubscriber = vi.fn()
    source.subscribe(firstSubscriber)
    source.subscribe(secondSubscriber)

    source.start(DEFAULT_MONITORING_CONFIGURATION)
    source.start(DEFAULT_MONITORING_CONFIGURATION)
    vi.advanceTimersByTime(250)

    expect(source.state).toBe(VisionSourceState.LIVE)
    expect(source.activeProducerCount).toBe(1)
    expect(source.totalProducerStarts).toBe(1)
    expect(firstSubscriber).toHaveBeenCalledTimes(3)
    expect(secondSubscriber).toHaveBeenCalledTimes(3)
    expect(vi.getTimerCount()).toBe(1)

    source.dispose()
  })

  it('unsubscribes independently and clears production on stop and dispose', () => {
    const source = new MockFilamentDataSource()
    const firstSubscriber = vi.fn()
    const secondSubscriber = vi.fn()
    const unsubscribeFirst = source.subscribe(firstSubscriber)
    source.subscribe(secondSubscriber)
    source.start(DEFAULT_MONITORING_CONFIGURATION)

    unsubscribeFirst()
    vi.advanceTimersByTime(125)

    expect(firstSubscriber).toHaveBeenCalledTimes(1)
    expect(secondSubscriber).toHaveBeenCalledTimes(2)
    expect(source.subscriberCount).toBe(1)

    source.stop()
    vi.advanceTimersByTime(500)
    expect(secondSubscriber).toHaveBeenCalledTimes(2)
    expect(source.state).toBe(VisionSourceState.STOPPED)
    expect(source.activeProducerCount).toBe(0)
    expect(vi.getTimerCount()).toBe(0)

    source.dispose()
    expect(source.subscriberCount).toBe(0)
  })

  it('cycles through Android-compatible warning and out-of-tolerance states', () => {
    const source = new MockFilamentDataSource()
    const statuses: string[] = []
    source.subscribe((reading) => statuses.push(reading.status))
    source.start(DEFAULT_MONITORING_CONFIGURATION)

    vi.advanceTimersByTime(16_000)

    expect(statuses).toContain(MeasurementStatus.NORMAL)
    expect(statuses).toContain(MeasurementStatus.WARNING)
    expect(statuses).toContain(MeasurementStatus.OUT_OF_TOLERANCE)

    source.dispose()
  })
})
