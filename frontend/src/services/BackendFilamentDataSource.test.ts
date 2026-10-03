import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { BackendFilamentDataSource } from './BackendFilamentDataSource'
import { DEFAULT_MONITORING_CONFIGURATION as configuration } from '../config/monitoringConfig'
import { VisionSourceState, type BackendRecovery } from '../domain/monitoring'
import { LocalConfigurationStorage } from '../config/monitoringConfig'
import { MonitoringStore } from '../state/MonitoringStore'
import { MonitoringRuntime } from '../state/MonitoringRuntime'

class Socket {
  static all: Socket[] = []
  readonly url: string
  binaryType = ''
  onmessage: ((event: { data: unknown }) => void) | null = null
  onclose: (() => void) | null = null
  onerror: (() => void) | null = null
  onopen: (() => void) | null = null
  closed = false
  constructor(url: string) { this.url = url; Socket.all.push(this) }
  close() { this.closed = true }
  message(data: unknown) { this.onmessage?.({ data: typeof data === 'object' && !(data instanceof ArrayBuffer) ? JSON.stringify(data) : data }) }
  disconnect() { this.onclose?.() }
  open() { this.onopen?.() }
}

const measurement = { evidence: [{ cameraId: 'camera', timestamp: 1000, sequence: 1,
  pixelWidth: 175, diameterMm: 1.75, confidence: 0.98, valid: true, source: 'VISION' }],
diameterMm: 1.75, confidence: 0.98, status: 'NORMAL', timestamp: 1000 }
const state = (sequence = 4) => ({ deviceId: 'filamentvision-1', connection: 'CONNECTED', monitoring: 'RUNNING',
  cameras: [{ cameraId: 'camera', state: 'LIVE', previewSupported: true, calibrationSupported: true }],
  session: { id: 'session-1', startedAt: 10, endedAt: null, finalizationReason: null, statistics: null },
  latestMeasurement: null, alarm: { level: 'NORMAL', direction: 'NONE', deviationPercent: 0 },
  statistics: {}, savedRevision: 1, appliedRevision: 1, activeProducerCount: 1, eventSequence: sequence })
const event = (sequence: number, type = 'measurement.sample', payload: unknown = {
  id: `reading-${sequence}`, sessionId: 'session-1', appliedRevision: 1, measurement,
}) => ({ sequence, type, deviceId: 'filamentvision-1', occurredAt: 1000, payload })
const flush = async () => { for (let i = 0; i < 12; i++) await Promise.resolve() }
const events = () => Socket.all.filter(s => s.url.includes('/events')).at(-1)!
const previews = () => Socket.all.filter(s => s.url.includes('/previews')).at(-1)!

describe('BackendFilamentDataSource', () => {
  beforeEach(() => {
    vi.useFakeTimers(); Socket.all = []
    vi.stubGlobal('WebSocket', Socket)
    vi.stubGlobal('fetch', vi.fn(async () => Response.json({ correlationId: 'test', data: state() })))
    vi.stubGlobal('URL', URL)
    URL.createObjectURL = vi.fn(() => `blob:frame-${vi.mocked(URL.createObjectURL).mock.calls.length}`)
    URL.revokeObjectURL = vi.fn()
  })
  afterEach(() => { vi.clearAllTimers(); vi.useRealTimers(); vi.unstubAllGlobals() })

  it('observes a silent Backend without samples, images, commands or producer timers', async () => {
    const source = new BackendFilamentDataSource()
    const readings: unknown[] = []; const images: unknown[] = []
    source.subscribeMeasurement(value => readings.push(value))
    source.subscribePreview(value => images.push(value))
    source.observe(configuration); await flush()
    vi.advanceTimersByTime(60_000)
    expect(readings).toEqual([]); expect(images).toEqual([])
    expect(Socket.all).toHaveLength(2)
    expect(events().url).toContain('afterSequence=4')
    expect(previews().url).toContain('stage=RAW&maxFps=5')
    expect(previews().url).not.toContain('cameraId')
    expect(vi.getTimerCount()).toBe(0)
    expect(vi.mocked(fetch).mock.calls.every(([, options]) => options?.method !== 'POST')).toBe(true)
    source.dispose()
  })

  it('delivers neutral measurements and every reliable event once, resuming from the last delivered sequence', async () => {
    const source = new BackendFilamentDataSource()
    const readings: unknown[] = []; const received: string[] = []
    source.subscribeMeasurement(value => readings.push(value))
    source.subscribeEvents(value => received.push(value.type))
    source.observe(configuration); await flush()
    events().message(event(5)); events().message(event(5))
    events().message(event(6, 'alarm.transition', { id: 'alarm-1' }))
    events().message(event(7, 'session.finalized', { id: 'session-1' }))
    events().message(event(8, 'error.raised', { id: 'error-1' }))
    expect(readings).toEqual([{ id: 'reading-5', sessionId: 'session-1', appliedRevision: 1, measurement }])
    expect(received).toEqual(['measurement.sample', 'alarm.transition', 'session.finalized', 'error.raised'])
    events().disconnect(); vi.advanceTimersByTime(1000); await flush()
    expect(events().url).toContain('afterSequence=8')
    source.dispose(); expect(vi.getTimerCount()).toBe(0)
  })

  it('reconnects a sequence gap without advancing the reliable cursor', async () => {
    const source = new BackendFilamentDataSource()
    const received: number[] = []
    source.subscribeEvents(value => received.push(value.sequence))
    source.observe(configuration); await flush()
    events().message(event(6))
    expect(received).toEqual([]); expect(source.state).toBe(VisionSourceState.ERROR)
    expect(events().closed).toBe(true)
    vi.advanceTimersByTime(1000); await flush()
    expect(events().url).toContain('afterSequence=4')
    events().message(event(5)); events().message(event(6))
    expect(received).toEqual([5, 6])
    expect(source.state).toBe(VisionSourceState.LIVE)
    source.dispose()
  })

  it('recovers expired replay through paged durable REST history before accepting the snapshot cursor', async () => {
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
      let data: unknown = state()
      if (url.includes('/sessions?')) data = { items: [{ id: 'session-1' }], nextCursor: null }
      if (url.includes('/measurements?')) data = { items: [{ id: 'old', sessionId: 'session-1', measurement }], nextCursor: 'page-2' }
      if (url.includes('cursor=page-2')) data = { items: [{ id: 'new', sessionId: 'session-1', measurement }], nextCursor: null }
      if (url.endsWith('/alarms')) data = [{ id: 'alarm-1' }]
      if (url.endsWith('/errors')) data = [{ id: 'error-1' }]
      return Response.json({ correlationId: 'test', data })
    }))
    const source = new BackendFilamentDataSource()
    const recovered: BackendRecovery[] = []
    source.subscribeRecovery(value => recovered.push(value))
    source.observe(configuration); await flush()
    events().message(event(10, 'state.snapshot', { state: state(10), reason: 'REPLAY_EXPIRED' }))
    await vi.waitFor(() => expect(recovered.at(-1)?.complete).toBe(true))
    expect(recovered.flatMap(value => value.sessions)).toEqual([{ id: 'session-1' }])
    expect(recovered.flatMap(value => value.measurements)).toEqual([
      { id: 'old', sessionId: 'session-1', measurement }, { id: 'new', sessionId: 'session-1', measurement }])
    expect(recovered.flatMap(value => value.alarms)).toEqual([{ id: 'alarm-1' }])
    expect(recovered.flatMap(value => value.errors)).toEqual([{ id: 'error-1' }])
    expect(events().url).toContain('afterSequence=10')
    source.dispose()
  })

  it('fails closed on unsuccessful durable recovery and on malformed reliable messages', async () => {
    const source = new BackendFilamentDataSource()
    source.observe(configuration); await flush()
    vi.mocked(fetch).mockRejectedValue(new Error('offline'))
    events().message(event(12, 'state.snapshot', { state: state(12), reason: 'REPLAY_EXPIRED' }))
    await flush()
    expect(source.state).toBe(VisionSourceState.ERROR)
    vi.advanceTimersByTime(1000); await flush()
    expect(events().url).toContain('afterSequence=4')
    events().message('{invalid')
    expect(events().closed).toBe(true)
    source.dispose()
  })

  it('owns one lossy preview socket and revokes replaced and unmounted URLs', async () => {
    const source = new BackendFilamentDataSource()
    const images: Array<{ url: string } | null> = []
    const first = source.subscribePreview(value => images.push(value))
    const second = source.subscribePreview(() => {})
    source.observe(configuration); await flush()
    const metadata = { type: 'preview.frame', deviceId: 'filamentvision-1', cameraId: 'camera', stage: 'RAW',
      width: 2, height: 2, timestamp: 1000, sourceSequence: 1, mediaType: 'image/png', byteLength: 4 }
    previews().message(metadata); previews().message(new ArrayBuffer(4))
    previews().message({ ...metadata, sourceSequence: 2 }); previews().message(new ArrayBuffer(4))
    expect(images.map(value => value?.url)).toEqual(['blob:frame-1', 'blob:frame-2'])
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:frame-1')
    expect(Socket.all.filter(s => s.url.includes('/previews'))).toHaveLength(1)
    first(); expect(previews().closed).toBe(false)
    second(); expect(previews().closed).toBe(true)
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:frame-2')
    source.dispose()
  })

  it('rejects malformed preview pairs without turning bytes into an image', async () => {
    const source = new BackendFilamentDataSource()
    source.subscribePreview(() => {}); source.observe(configuration); await flush()
    previews().message(new ArrayBuffer(4))
    expect(URL.createObjectURL).not.toHaveBeenCalled()
    source.dispose()
  })

  it('sends explicit idempotent commands with stable retry IDs, independent of observation', async () => {
    const source = new BackendFilamentDataSource()
    vi.mocked(fetch).mockResolvedValue(Response.json({ correlationId: 'test', data: { outcome: 'STARTED', session: null } }))
    await Promise.all([source.start(configuration), source.start(configuration)])
    expect(vi.mocked(fetch).mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(1)
    const [url, init] = vi.mocked(fetch).mock.calls[0]!
    expect(url).toBe('/api/v1/devices/filamentvision-1/monitoring/start')
    expect(JSON.parse(init!.body as string)).toEqual({ commandId: expect.any(String), targetDiameter: 1.75 })
    vi.mocked(fetch).mockImplementation(async () => Response.json({ correlationId: 'test', data: { outcome: 'STOPPED', session: null } }))
    await Promise.all([source.stop(), source.stop()])
    const count = vi.mocked(fetch).mock.calls.length
    source.dispose()
    expect(vi.mocked(fetch).mock.calls).toHaveLength(count)
  })

  it('aborts pending REST and suppresses late results when observers detach', async () => {
    let resolve!: (response: Response) => void
    vi.mocked(fetch).mockImplementation(() => new Promise<Response>(done => { resolve = done }))
    const source = new BackendFilamentDataSource()
    source.observe(configuration)
    const signal = vi.mocked(fetch).mock.calls[0]![1]!.signal!
    source.unobserve(); expect(signal.aborted).toBe(true)
    resolve(Response.json({ correlationId: 'test', data: state() })); await flush()
    expect(Socket.all).toHaveLength(0); expect(vi.getTimerCount()).toBe(0)
    source.dispose()
  })

  it('keeps failed explicit commands retryable with the same Backend idempotency key', async () => {
    const source = new BackendFilamentDataSource()
    vi.mocked(fetch).mockRejectedValueOnce(new Error('response lost'))
    await expect(source.start(configuration)).rejects.toThrow('response lost')
    vi.mocked(fetch).mockImplementation(async () => Response.json({ correlationId: 'test', data: { outcome: 'ALREADY_RUNNING', session: null } }))
    await source.start(configuration)
    const bodies = vi.mocked(fetch).mock.calls.map(([, init]) => JSON.parse(init!.body as string))
    expect(bodies[0].commandId).toBe(bodies[1].commandId)
    source.dispose()
  })

  it('keeps silent and neutral Store state through repeated browser observation without producer commands', async () => {
    const source = new BackendFilamentDataSource()
    const store = new MonitoringStore(source, new LocalConfigurationStorage(window.localStorage))
    const runtime = new MonitoringRuntime(store)
    let release = runtime.acquire(); await flush()
    expect(store.getLiveSnapshot().latestReading).toBeNull()
    expect(store.getLiveSnapshot().latestBackendReading).toBeNull()
    events().message(event(5, 'alarm.transition', { id: 'alarm-1' }))
    events().message(event(6, 'error.raised', { id: 'error-1' }))
    events().message(event(7, 'session.finalized', { id: 'session-1' }))
    for (let index = 8; index <= 308; index++) events().message(event(index))
    expect(store.getLiveSnapshot().recentBackendTrend).toHaveLength(300)
    expect(store.getLiveSnapshot().latestBackendReading).toEqual(measurement)
    expect(store.getLiveSnapshot().recentTrend).toEqual([])
    expect(store.getLiveSnapshot().recoveredAlarms).toEqual([{ id: 'alarm-1' }])
    expect(store.getLiveSnapshot().recoveredErrors).toEqual([{ id: 'error-1' }])
    expect(store.getLiveSnapshot().recoveredSessions).toEqual([{ id: 'session-1' }])
    release(); await flush()
    expect(Socket.all.every(socket => socket.closed)).toBe(true)
    release = runtime.acquire(); await flush()
    expect(events().url).toContain('afterSequence=308')
    expect(vi.mocked(fetch).mock.calls.every(([, options]) => options?.method !== 'POST')).toBe(true)
    release(); await flush(); runtime.dispose()
    expect(vi.getTimerCount()).toBe(0)
  })

  it('replays an event when a durable consumer fails instead of acknowledging it', async () => {
    const source = new BackendFilamentDataSource()
    source.subscribeEvents(() => { throw new Error('consumer failure') })
    source.observe(configuration); await flush()
    events().message(event(5))
    expect(source.state).toBe(VisionSourceState.ERROR)
    vi.advanceTimersByTime(1000); await flush()
    expect(events().url).toContain('afterSequence=4')
    source.dispose()
  })

  it('disconnects preview independently and cancels pending reconnects on unmount', async () => {
    const source = new BackendFilamentDataSource()
    source.subscribePreview(() => {}); source.observe(configuration); await flush()
    const reliable = events()
    previews().disconnect()
    expect(reliable.closed).toBe(false)
    source.unobserve()
    vi.advanceTimersByTime(1000)
    expect(Socket.all).toHaveLength(2); expect(vi.getTimerCount()).toBe(0)
    source.dispose()
  })

  it('resynchronizes durable data after a Backend restart invalidates the old cursor', async () => {
    const source = new BackendFilamentDataSource()
    const recovered: unknown[] = []
    source.subscribeRecovery(value => recovered.push(value))
    source.observe(configuration); await flush()
    vi.mocked(fetch).mockImplementation(async (url) => {
      const path = String(url)
      const data = path.endsWith('/state') ? { ...state(0), session: null } : path.includes('/sessions?') ?
        { items: [], nextCursor: null } : []
      return Response.json({ correlationId: 'test', data })
    })
    events().message({ type: 'protocol.error', code: 'FUTURE_CURSOR', message: 'Subscription unavailable' })
    await vi.waitFor(() => expect(recovered).toHaveLength(1))
    expect(events().url).toContain('afterSequence=0')
    source.dispose()
  })

  it('delivers durable recovery pages while reading, rather than retaining the entire archive', async () => {
    let releasePage!: (response: Response) => void
    vi.mocked(fetch).mockImplementation(async (url) => {
      const path = String(url)
      if (path.includes('cursor=next')) return new Promise<Response>(resolve => { releasePage = resolve })
      let data: unknown = state()
      if (path.includes('/sessions?')) data = { items: [{ id: 'session-1' }], nextCursor: null }
      if (path.includes('/measurements?')) data = { items: [{ id: 'old', sessionId: 'session-1', measurement }], nextCursor: 'next' }
      if (path.endsWith('/alarms') || path.endsWith('/errors')) data = []
      return Response.json({ correlationId: 'test', data })
    })
    const source = new BackendFilamentDataSource()
    const records: string[] = []
    source.subscribeRecovery(value => records.push(...value.measurements.map(value => value.id)))
    source.observe(configuration); await flush()
    events().message(event(10, 'state.snapshot', { state: state(10), reason: 'REPLAY_EXPIRED' }))
    await vi.waitFor(() => expect(releasePage).toBeTypeOf('function'))
    expect(records).toEqual(['old'])
    releasePage(Response.json({ correlationId: 'test', data: { items: [], nextCursor: null } }))
    await vi.waitFor(() => expect(events().url).toContain('afterSequence=10'))
    source.dispose()
  })

  it('cleans preview and observer resources even if a lossy image observer throws', async () => {
    const source = new BackendFilamentDataSource()
    source.subscribePreview(() => { throw new Error('image consumer failed') })
    source.observe(configuration); await flush()
    previews().message({ type: 'preview.frame', deviceId: 'filamentvision-1', cameraId: 'camera', stage: 'RAW',
      width: 2, height: 2, timestamp: 1, sourceSequence: 1, mediaType: 'image/jpeg', byteLength: 4 })
    expect(() => previews().message(new ArrayBuffer(4))).not.toThrow()
    expect(() => source.dispose()).not.toThrow()
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:frame-1')
    expect(Socket.all.every(socket => socket.closed)).toBe(true)
  })

  it('rejects malformed neutral REST data before publishing a measurement or acknowledging a cursor', async () => {
    vi.mocked(fetch).mockImplementation(async () => Response.json({ correlationId: 'test',
      data: { ...state(), latestMeasurement: { ...measurement, diameterMm: 'invalid' } } }))
    const source = new BackendFilamentDataSource()
    const snapshots: unknown[] = []
    source.subscribeBackendState(value => snapshots.push(value))
    source.observe(configuration); await flush()
    expect(snapshots).toEqual([])
    expect(source.state).toBe(VisionSourceState.ERROR)
    expect(Socket.all).toHaveLength(0)
    source.dispose()
  })

  it('rejects a malformed command response without marking the command successful', async () => {
    const source = new BackendFilamentDataSource()
    await expect(source.start(configuration)).rejects.toThrow()
    source.dispose()
  })

  it('keeps an in-flight stop idempotent when an earlier start completes', async () => {
    let completeStart!: (response: Response) => void
    let completeStop!: (response: Response) => void
    vi.mocked(fetch).mockImplementationOnce(() => new Promise(resolve => { completeStart = resolve }))
      .mockImplementationOnce(() => new Promise(resolve => { completeStop = resolve }))
      .mockImplementation(async () => Response.json({ correlationId: 'test', data: { outcome: 'STOPPED', session: null } }))
    const source = new BackendFilamentDataSource()
    const starting = source.start(configuration)
    const stopping = source.stop()
    await flush()
    completeStart(Response.json({ correlationId: 'test', data: { outcome: 'STARTED', session: null } }))
    await starting; await flush()
    const repeated = source.stop()
    completeStop(Response.json({ correlationId: 'test', data: { outcome: 'STOPPED', session: null } }))
    await Promise.all([stopping, repeated])
    expect(vi.mocked(fetch).mock.calls).toHaveLength(2)
    source.dispose()
  })

  it.each([['start', 'stop', 'start'], ['stop', 'start', 'stop']] as const)(
    'preserves the final intent of %s-%s-%s before any response', async (first, middle, last) => {
      let completeFirst!: (response: Response) => void
      const actions: string[] = []
      vi.mocked(fetch).mockImplementation(async (url) => {
        const action = String(url).split('/').at(-1)!
        actions.push(action)
        const response = Response.json({ correlationId: 'test', data: {
          outcome: action === 'start' ? 'STARTED' : 'STOPPED', session: null,
        } })
        if (actions.length === 1) return new Promise(resolve => { completeFirst = resolve })
        return response
      })
      const source = new BackendFilamentDataSource()
      const issue = (action: 'start' | 'stop') => action === 'start' ? source.start(configuration) : source.stop()
      const commands = [issue(first), issue(middle), issue(last)]
      await flush()
      completeFirst(Response.json({ correlationId: 'test', data: { outcome: first === 'start' ? 'STARTED' : 'STOPPED', session: null } }))
      await Promise.all(commands)
      expect(actions).toEqual([first, middle, last])
      const ids = vi.mocked(fetch).mock.calls.map(([, options]) => JSON.parse(options!.body as string).commandId)
      expect(new Set(ids).size).toBe(3)
      source.dispose()
    },
  )

  it.each([['start', 'COMPLETED'], ['stop', 'RUNNING']] as const)(
    'invalidates a completed %s command after contrary state.changed %s', async (action, monitoring) => {
      vi.mocked(fetch).mockImplementation(async (url) => Response.json({ correlationId: 'test', data:
        String(url).endsWith('/state') ? state() : { outcome: action === 'start' ? 'STARTED' : 'STOPPED', session: null } }))
      const source = new BackendFilamentDataSource()
      const issue = () => action === 'start' ? source.start(configuration) : source.stop()
      source.observe(configuration); await flush()
      await issue(); await issue()
      expect(vi.mocked(fetch).mock.calls.filter(([, options]) => options?.method === 'POST')).toHaveLength(1)
      events().message(event(5, 'state.changed', { monitoring, session: monitoring === 'RUNNING' ? state().session : null, activeProducerCount: monitoring === 'RUNNING' ? 1 : 0 }))
      await issue()
      const requests = vi.mocked(fetch).mock.calls.filter(([, options]) => options?.method === 'POST')
      expect(requests).toHaveLength(2)
      expect(JSON.parse(requests[0][1]!.body as string).commandId).not.toBe(JSON.parse(requests[1][1]!.body as string).commandId)
      source.dispose()
    },
  )

  it('invalidates completed commands after an authoritative REST observation refresh', async () => {
    let monitoring = 'RUNNING'
    vi.mocked(fetch).mockImplementation(async (url) => Response.json({ correlationId: 'test', data:
      String(url).endsWith('/state') ? { ...state(), monitoring } : { outcome: 'STARTED', session: null } }))
    const source = new BackendFilamentDataSource()
    source.observe(configuration); await flush()
    await source.start(configuration)
    source.unobserve(); monitoring = 'COMPLETED'
    source.observe(configuration); await flush()
    await source.start(configuration)
    expect(vi.mocked(fetch).mock.calls.filter(([, options]) => options?.method === 'POST')).toHaveLength(2)
    source.dispose()
  })

  it('restores health on a quiet reliable reconnect by refreshing authoritative state without advancing the cursor', async () => {
    const source = new BackendFilamentDataSource()
    const snapshots: unknown[] = []
    source.subscribeBackendState(value => snapshots.push(value))
    source.observe(configuration); await flush()
    events().disconnect()
    expect(source.state).toBe(VisionSourceState.ERROR)
    vi.advanceTimersByTime(1000); events().open(); await flush()
    expect(source.state).toBe(VisionSourceState.LIVE)
    expect(snapshots).toHaveLength(2)
    expect(events().url).toContain('afterSequence=4')
    events().disconnect()
    vi.advanceTimersByTime(1000); events().open(); await flush()
    expect(events().url).toContain('afterSequence=4')
    source.dispose()
  })

  it('keeps cursor and error state if a quiet reconnect REST checkpoint is ahead of replay', async () => {
    const source = new BackendFilamentDataSource()
    source.observe(configuration); await flush()
    events().disconnect()
    vi.mocked(fetch).mockImplementation(async () => Response.json({ correlationId: 'test', data: state(9) }))
    vi.advanceTimersByTime(1000); events().open(); await flush()
    expect(source.state).toBe(VisionSourceState.ERROR)
    events().disconnect(); vi.advanceTimersByTime(1000)
    expect(events().url).toContain('afterSequence=4')
    source.dispose()
  })

  it('keeps Backend Store observation live before and after stop acknowledgement until runtime release', async () => {
    let completeStop!: (response: Response) => void
    vi.mocked(fetch).mockImplementation(async (url) => String(url).endsWith('/monitoring/stop') ?
      new Promise<Response>(resolve => { completeStop = resolve }) : Response.json({ correlationId: 'test', data: state() }))
    const source = new BackendFilamentDataSource()
    const store = new MonitoringStore(source, new LocalConfigurationStorage(window.localStorage))
    const runtime = new MonitoringRuntime(store)
    const release = runtime.acquire(); await flush()
    const stopped = store.stop(); await flush()
    expect(store.getLiveSnapshot().monitoringState).toBe('MONITORING')
    expect(store.diagnostics.sourceSubscriptionActive).toBe(true)
    expect(Socket.all.some(socket => socket.closed)).toBe(false)
    completeStop(Response.json({ correlationId: 'test', data: { outcome: 'STOPPED', session: null } }))
    await stopped
    expect(store.getLiveSnapshot().monitoringState).toBe('MONITORING')
    expect(Socket.all.some(socket => socket.closed)).toBe(false)
    events().message(event(5, 'state.changed', { monitoring: 'COMPLETED', session: null, activeProducerCount: 0 }))
    expect(store.getLiveSnapshot().monitoringState).toBe('IDLE')
    expect(store.diagnostics.sourceSubscriptionActive).toBe(true)
    release(); await flush(); runtime.dispose()
    expect(Socket.all.every(socket => socket.closed)).toBe(true)
  })

  it('retains authoritative running state and observers when an explicit Backend stop fails', async () => {
    vi.mocked(fetch).mockImplementation(async (url) => {
      if (String(url).endsWith('/monitoring/stop')) throw new Error('stop rejected')
      return Response.json({ correlationId: 'test', data: state() })
    })
    const source = new BackendFilamentDataSource()
    const store = new MonitoringStore(source, new LocalConfigurationStorage(window.localStorage))
    const runtime = new MonitoringRuntime(store)
    const release = runtime.acquire(); await flush()
    await expect(store.stop()).rejects.toThrow('stop rejected')
    expect(store.getLiveSnapshot().monitoringState).toBe('MONITORING')
    expect(store.diagnostics.sourceSubscriptionActive).toBe(true)
    expect(Socket.all.some(socket => socket.closed)).toBe(false)
    release(); await flush(); runtime.dispose()
  })

  it.each([['start', 'RUNNING', 'COMPLETED'], ['stop', 'COMPLETED', 'RUNNING']] as const)(
    'does not cache a delayed %s response after authoritative %s then %s', async (action, intermediate, contrary) => {
      let completeCommand!: (response: Response) => void
      let commandRequests = 0
      vi.mocked(fetch).mockImplementation(async (url) => {
        if (String(url).endsWith('/state')) return Response.json({ correlationId: 'test', data: state() })
        commandRequests++
        if (commandRequests === 1) return new Promise<Response>(resolve => { completeCommand = resolve })
        return Response.json({ correlationId: 'test', data: { outcome: action === 'start' ? 'STARTED' : 'STOPPED', session: null } })
      })
      const source = new BackendFilamentDataSource()
      const issue = () => action === 'start' ? source.start(configuration) : source.stop()
      source.observe(configuration); await flush()
      const pending = issue(); await flush()
      expect(issue()).toBe(pending)
      events().message(event(5, 'state.changed', { monitoring: intermediate,
        session: intermediate === 'RUNNING' ? state().session : null, activeProducerCount: intermediate === 'RUNNING' ? 1 : 0 }))
      events().message(event(6, 'state.changed', { monitoring: contrary,
        session: contrary === 'RUNNING' ? state().session : null, activeProducerCount: contrary === 'RUNNING' ? 1 : 0 }))
      expect(issue()).toBe(pending)
      completeCommand(Response.json({ correlationId: 'test', data: { outcome: action === 'start' ? 'STARTED' : 'STOPPED', session: null } }))
      await pending; await issue()
      expect(commandRequests).toBe(2)
      const requests = vi.mocked(fetch).mock.calls.filter(([, options]) => options?.method === 'POST')
      expect(JSON.parse(requests[0][1]!.body as string).commandId).not.toBe(JSON.parse(requests[1][1]!.body as string).commandId)
      source.dispose()
    },
  )

  it.each([['start', 'RUNNING'], ['stop', 'COMPLETED']] as const)(
    'keeps the completed %s cache reusable when newer authoritative %s agrees', async (action, monitoring) => {
      let completeCommand!: (response: Response) => void
      vi.mocked(fetch).mockImplementation(async (url) => String(url).endsWith('/state') ?
        Response.json({ correlationId: 'test', data: state() }) : new Promise<Response>(resolve => { completeCommand = resolve }))
      const source = new BackendFilamentDataSource()
      source.observe(configuration); await flush()
      const issue = () => action === 'start' ? source.start(configuration) : source.stop()
      const pending = issue(); await flush()
      events().message(event(5, 'state.changed', { monitoring,
        session: monitoring === 'RUNNING' ? state().session : null, activeProducerCount: monitoring === 'RUNNING' ? 1 : 0 }))
      completeCommand(Response.json({ correlationId: 'test', data: { outcome: action === 'start' ? 'STARTED' : 'STOPPED', session: null } }))
      await pending; await issue()
      expect(vi.mocked(fetch).mock.calls.filter(([, options]) => options?.method === 'POST')).toHaveLength(1)
      source.dispose()
    },
  )
})
