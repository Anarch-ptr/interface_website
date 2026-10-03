import { VisionSourceState, type BackendMeasurementRecord, type BackendEvent, type BackendState,
  type BackendRecovery, type BackendSession, type CameraPreview, type MonitoringConfiguration } from '../domain/monitoring'
import type { FilamentDataSource, ReadingListener, SourceStateListener } from './FilamentDataSource'

type Listener<T> = (value: T) => void
type PreviewMetadata = Omit<CameraPreview, 'url'> & { byteLength: number; mediaType: string }
type MonitoringCommand = { action: string; id: string; promise: Promise<void> | null; done: boolean }

/** Browser observers never own the Backend producer. Only explicit REST commands do. */
export class BackendFilamentDataSource implements FilamentDataSource {
  private readonly deviceId: string
  private readonly baseUrl: string
  private readonly stateListeners = new Set<SourceStateListener>()
  private readonly backendListeners = new Set<Listener<BackendState>>()
  private readonly measurementListeners = new Set<Listener<BackendMeasurementRecord>>()
  private readonly eventListeners = new Set<Listener<BackendEvent>>()
  private readonly recoveryListeners = new Set<Listener<BackendRecovery>>()
  private readonly previewListeners = new Set<Listener<CameraPreview | null>>()
  private mutableState: VisionSourceState = VisionSourceState.STOPPED
  private observing = false
  private disposed = false
  private generation = 0
  private cursor: number | null = null
  private backendMonitoring = 'IDLE'
  private authoritativeVersion = 0
  private lastRunningVersion = 0
  private lastStoppedVersion = 0
  private observerAbort: AbortController | null = null
  private eventSocket: WebSocket | null = null
  private eventRefreshAbort: AbortController | null = null
  private previewSocket: WebSocket | null = null
  private eventRetry: ReturnType<typeof setTimeout> | null = null
  private previewRetry: ReturnType<typeof setTimeout> | null = null
  private previewMetadata: PreviewMetadata | null = null
  private preview: CameraPreview | null = null
  private lastCommand: MonitoringCommand | null = null
  private readonly commandAborts = new Set<AbortController>()
  private commandTail: Promise<void> = Promise.resolve()

  constructor(baseUrl = '/api/v1', deviceId = 'filamentvision-1') {
    this.baseUrl = baseUrl; this.deviceId = deviceId
  }
  get state() { return this.mutableState }
  // The legacy fixture channel is deliberately silent for neutral production data.
  subscribe(listener: ReadingListener) { void listener; return () => {} }
  subscribeState(listener: SourceStateListener) { return add(this.stateListeners, listener) }
  subscribeBackendState(listener: Listener<BackendState>) { return add(this.backendListeners, listener) }
  subscribeMeasurement(listener: Listener<BackendMeasurementRecord>) { return add(this.measurementListeners, listener) }
  subscribeEvents(listener: Listener<BackendEvent>) { return add(this.eventListeners, listener) }
  subscribeRecovery(listener: Listener<BackendRecovery>) { return add(this.recoveryListeners, listener) }
  subscribePreview(listener: Listener<CameraPreview | null>) {
    const unsubscribe = add(this.previewListeners, listener)
    if (this.preview) publishLossy(new Set([listener]), this.preview)
    if (this.observing) this.openPreview()
    return () => { unsubscribe(); if (!this.previewListeners.size) this.closePreview() }
  }
  observe(configuration?: MonitoringConfiguration): void {
    void configuration
    if (this.disposed) throw new Error('A disposed data source cannot be observed.')
    if (this.observing) return
    this.observing = true; this.observerAbort = new AbortController()
    const generation = ++this.generation
    this.setState(VisionSourceState.STARTING)
    void this.bootstrap(generation); this.openPreview()
  }
  unobserve(): void {
    if (!this.observing) return
    this.observing = false; ++this.generation
    this.observerAbort?.abort(); this.observerAbort = null
    this.closeEvents(); this.closePreview(); this.setState(VisionSourceState.STOPPED)
  }
  start(configuration: MonitoringConfiguration): Promise<void> {
    return this.command('start', { targetDiameter: configuration.targetDiameterMm })
  }
  stop(): Promise<void> { return this.command('stop', {}) }
  updateConfiguration(configuration: MonitoringConfiguration): void {
    void configuration
    // Local display preferences cannot implicitly Save/Apply Backend configuration.
  }
  dispose(): void {
    if (this.disposed) return
    this.unobserve(); this.disposed = true
    for (const abort of this.commandAborts) abort.abort()
    this.commandAborts.clear()
    this.stateListeners.clear(); this.backendListeners.clear(); this.measurementListeners.clear()
    this.eventListeners.clear(); this.recoveryListeners.clear(); this.previewListeners.clear()
  }
  private current(generation: number) { return this.observing && generation === this.generation }
  private device() { return `${this.baseUrl}/devices/${encodeURIComponent(this.deviceId)}` }
  private ws(path: string) {
    const url = new URL(`${this.baseUrl}/${path}`, window.location.href)
    url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:'
    return url.href
  }
  private async read<T>(url: string, signal: AbortSignal, options: RequestInit = {}): Promise<T> {
    const response = await fetch(url, { ...options, signal })
    const body: unknown = await response.json()
    signal.throwIfAborted()
    if (!response.ok || !record(body) || typeof body.correlationId !== 'string' || !Object.hasOwn(body, 'data')) throw new Error('Backend request failed.')
    return body.data as T
  }
  private async bootstrap(generation: number) {
    try {
      const state = await this.read<BackendState>(`${this.device()}/state`, this.observerAbort!.signal)
      if (!this.current(generation)) return
      this.validateState(state); publish(this.backendListeners, state)
      if (this.cursor === null) this.cursor = state.eventSequence
      this.applyState(state.monitoring); this.openEvents(generation)
    } catch { if (this.current(generation)) this.retryEvents(generation) }
  }
  private openEvents(generation: number, refresh = false) {
    if (!this.current(generation) || this.eventSocket) return
    const socket = new WebSocket(this.ws(`events?deviceId=${encodeURIComponent(this.deviceId)}&afterSequence=${this.cursor}`))
    this.eventSocket = socket
    socket.onopen = () => { if (refresh && this.eventSocket === socket) void this.refreshEventState(socket, generation) }
    socket.onmessage = ({ data }) => {
      if (this.eventSocket !== socket || !this.current(generation)) return
      try {
        const value: unknown = JSON.parse(data)
        if (record(value) && value.type === 'protocol.error' && value.code === 'FUTURE_CURSOR') {
          this.closeEventSocket(); void this.recoverRestart(generation); return
        }
        if (!record(value) || !sequence(value.sequence) || value.deviceId !== this.deviceId || typeof value.type !== 'string' ||
          !Number.isFinite(value.occurredAt) || !Object.hasOwn(value, 'payload')) throw new Error('Invalid reliable event.')
        const event = value as unknown as BackendEvent
        if (event.type === 'state.snapshot') {
          if (!record(event.payload) || !['INITIAL', 'REPLAY_EXPIRED', 'SLOW_CONSUMER'].includes(String(event.payload.reason))) throw new Error('Invalid snapshot.')
          const state = event.payload.state as BackendState
          this.validateState(state)
          if (state.eventSequence !== event.sequence || event.sequence < this.cursor!) throw new Error('Invalid snapshot cursor.')
          this.closeEventSocket(); void this.recover(event, state, generation); return
        }
        if (event.sequence <= this.cursor!) return
        if (event.sequence !== this.cursor! + 1) throw new Error('Reliable sequence gap.')
        if (event.type === 'measurement.sample') {
          if (!measurementRecord(event.payload)) throw new Error('Invalid measurement.')
          publish(this.measurementListeners, event.payload)
        }
        if (event.type === 'state.changed') {
          if (!record(event.payload) || typeof event.payload.monitoring !== 'string') throw new Error('Invalid state.')
          this.applyState(event.payload.monitoring)
        }
        publish(this.eventListeners, event)
        this.applyState(this.backendMonitoring, false)
        // Advance only after every consumer successfully processes the event.
        this.cursor = event.sequence
      } catch { this.retryEvents(generation) }
    }
    socket.onclose = socket.onerror = () => { if (this.eventSocket === socket) this.retryEvents(generation) }
  }
  private retryEvents(generation: number) {
    this.closeEventSocket(); this.setState(VisionSourceState.ERROR)
    if (!this.current(generation) || this.eventRetry !== null) return
    this.eventRetry = setTimeout(() => {
      this.eventRetry = null
      if (this.cursor === null) void this.bootstrap(generation)
      else this.openEvents(generation, true)
    }, 1000)
  }
  private async refreshEventState(socket: WebSocket, generation: number) {
    const abort = new AbortController()
    this.eventRefreshAbort = abort
    try {
      const state = await this.read<BackendState>(`${this.device()}/state`, abort.signal)
      if (!this.current(generation) || this.eventSocket !== socket) return
      this.validateState(state)
      // A REST refresh may restore quiet transport health only at our delivered
      // checkpoint. It cannot acknowledge events that replay has not delivered.
      if (state.eventSequence === this.cursor) {
        publish(this.backendListeners, state); this.applyState(state.monitoring)
      }
    } catch {
      if (!abort.signal.aborted && this.current(generation) && this.eventSocket === socket) this.retryEvents(generation)
    } finally { if (this.eventRefreshAbort === abort) this.eventRefreshAbort = null }
  }
  private async recoverRestart(generation: number) {
    try {
      const state = await this.read<BackendState>(`${this.device()}/state`, this.observerAbort!.signal)
      this.validateState(state)
      if (!this.current(generation)) return
      await this.recover({ type: 'state.snapshot', sequence: state.eventSequence, deviceId: this.deviceId,
        occurredAt: Date.now(), payload: { state, reason: 'BACKEND_RESTART' } }, state, generation)
    } catch { if (this.current(generation)) this.retryEvents(generation) }
  }
  private async recover(event: BackendEvent, state: BackendState, generation: number) {
    try {
      const signal = this.observerAbort!.signal
      // At-least-once durable recovery streams one REST page at a time. A failed
      // recovery retains the old cursor; consumers deduplicate records by ID.
      const deliver = (values: Partial<BackendRecovery>) => {
        if (!this.current(generation)) throw new Error('Observation ended.')
        publish(this.recoveryListeners, { sequence: event.sequence, complete: false,
          sessions: [], measurements: [], alarms: [], errors: [], ...values })
      }
      let activeSessionSeen = false
      const recoverSession = async (session: BackendSession) => {
        if (typeof session.id !== 'string') throw new Error('Invalid session.')
        if (session.id === state.session?.id) activeSessionSeen = true
        const path = `${this.baseUrl}/sessions/${encodeURIComponent(session.id)}`
        await this.pages<BackendMeasurementRecord>(`${path}/measurements`, signal, measurements => {
          if (!measurements.every(measurementRecord)) throw new Error('Invalid history.')
          if (measurements.length) deliver({ measurements })
        })
        const sessionAlarms = await this.read<unknown[]>(`${path}/alarms`, signal)
        if (!Array.isArray(sessionAlarms)) throw new Error('Invalid alarms.')
        if (sessionAlarms.length) deliver({ alarms: sessionAlarms })
      }
      await this.pages<BackendSession>(`${this.baseUrl}/sessions`, signal, async sessions => {
        if (sessions.length) deliver({ sessions })
        for (const session of sessions) await recoverSession(session)
      })
      if (state.session && !activeSessionSeen) {
        deliver({ sessions: [state.session] }); await recoverSession(state.session)
      }
      const errors = await this.read<unknown[]>(`${this.baseUrl}/errors`, signal)
      if (!Array.isArray(errors)) throw new Error('Invalid errors.')
      if (!this.current(generation)) return
      deliver({ errors, complete: true })
      publish(this.backendListeners, state); publish(this.eventListeners, event)
      this.cursor = event.sequence; this.applyState(state.monitoring); this.openEvents(generation)
    } catch { if (this.current(generation)) this.retryEvents(generation) }
  }
  private async pages<T>(path: string, signal: AbortSignal, consume: (items: T[]) => void | Promise<void>): Promise<void> {
    const seen = new Set<string>(); let cursor: string | null = null
    do {
      const page: { items: T[]; nextCursor: string | null } = await this.read(`${path}?limit=500${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}`, signal)
      if (!Array.isArray(page.items) || !(page.nextCursor === null || typeof page.nextCursor === 'string')) throw new Error('Invalid history page.')
      await consume(page.items); signal.throwIfAborted(); cursor = page.nextCursor
      if (cursor !== null) { if (!cursor || seen.has(cursor)) throw new Error('Repeated history cursor.'); seen.add(cursor) }
    } while (cursor !== null)
  }
  private validateState(state: BackendState) {
    if (!record(state) || state.deviceId !== this.deviceId || !sequence(state.eventSequence) || typeof state.monitoring !== 'string' ||
      typeof state.connection !== 'string' || !(state.session === null || record(state.session) && typeof state.session.id === 'string') ||
      !(state.latestMeasurement === null || measurement(state.latestMeasurement))) throw new Error('Invalid Backend state.')
  }
  private applyState(monitoring: string, authoritative = true) {
    const running = monitoring === 'RUNNING' || monitoring === 'STARTING'
    if (authoritative) {
      this.authoritativeVersion++
      if (running) this.lastRunningVersion = this.authoritativeVersion
      else this.lastStoppedVersion = this.authoritativeVersion
    }
    if (authoritative && this.lastCommand?.done && (this.lastCommand.action === 'start') !== running) {
      this.lastCommand = null
    }
    this.backendMonitoring = monitoring
    this.setState(monitoring === 'RUNNING' ? VisionSourceState.LIVE : monitoring === 'STARTING' ? VisionSourceState.STARTING :
      monitoring === 'INTERRUPTED' ? VisionSourceState.ERROR : VisionSourceState.STOPPED)
  }
  private setState(state: VisionSourceState) { this.mutableState = state; publish(this.stateListeners, state) }
  private closeEventSocket() {
    this.eventRefreshAbort?.abort(); this.eventRefreshAbort = null
    if (this.eventSocket) close(this.eventSocket)
    this.eventSocket = null
  }
  private closeEvents() {
    if (this.eventRetry !== null) clearTimeout(this.eventRetry)
    this.eventRetry = null; this.closeEventSocket()
  }
  private openPreview() {
    if (!this.observing || !this.previewListeners.size || this.previewSocket) return
    const socket = new WebSocket(this.ws(`previews?deviceId=${encodeURIComponent(this.deviceId)}&stage=RAW&maxFps=5`))
    this.previewSocket = socket; socket.binaryType = 'arraybuffer'
    socket.onmessage = ({ data }) => {
      if (this.previewSocket !== socket) return
      if (typeof data === 'string') {
        this.previewMetadata = null
        try {
          const value = JSON.parse(data)
          if (value.type === 'preview.frame' && value.deviceId === this.deviceId && value.cameraId === 'camera' && value.stage === 'RAW' &&
            Number.isInteger(value.width) && value.width > 0 && value.width <= 1280 && Number.isInteger(value.height) && value.height > 0 && value.height <= 720 &&
            Number.isFinite(value.timestamp) && sequence(value.sourceSequence) && ['image/png', 'image/jpeg'].includes(value.mediaType) &&
            Number.isInteger(value.byteLength) && value.byteLength > 0 && value.byteLength <= 4 * 1024 * 1024) this.previewMetadata = value
        } catch { /* Lossy preview errors never affect the reliable event socket. */ }
        return
      }
      const metadata = this.previewMetadata; this.previewMetadata = null
      if (!metadata || !(data instanceof ArrayBuffer) || data.byteLength !== metadata.byteLength) return
      const url = URL.createObjectURL(new Blob([data], { type: metadata.mediaType }))
      const previous = this.preview
      this.preview = { url, width: metadata.width, height: metadata.height, timestamp: metadata.timestamp, sourceSequence: metadata.sourceSequence }
      try { publishLossy(this.previewListeners, this.preview) }
      finally { if (previous) URL.revokeObjectURL(previous.url) }
    }
    socket.onclose = socket.onerror = () => {
      if (this.previewSocket !== socket) return
      this.closePreview()
      if (this.observing && this.previewListeners.size) this.previewRetry = setTimeout(() => {
        this.previewRetry = null; this.openPreview()
      }, 1000)
    }
  }
  private closePreview() {
    if (this.previewRetry !== null) clearTimeout(this.previewRetry)
    this.previewRetry = null
    if (this.previewSocket) close(this.previewSocket)
    this.previewSocket = null; this.previewMetadata = null
    if (this.preview) {
      const previous = this.preview; this.preview = null
      try { publishLossy(this.previewListeners, null) }
      finally { URL.revokeObjectURL(previous.url) }
    }
  }
  private command(action: string, body: object): Promise<void> {
    if (this.disposed) return Promise.reject(new Error('Data source disposed.'))
    // Only consecutive equivalent intents may coalesce. An intervening opposite
    // intent must preserve a new command in the serialized Backend order.
    const existing = this.lastCommand?.action === action ? this.lastCommand : null
    if (existing?.promise) return existing.promise
    if (existing?.done) return Promise.resolve()
    const command: MonitoringCommand = existing ?? { action, id: crypto.randomUUID(), promise: null, done: false }
    this.lastCommand = command
    const abort = new AbortController(); this.commandAborts.add(abort)
    command.promise = this.commandTail.catch(() => {}).then(async () => {
      abort.signal.throwIfAborted()
      const issuedVersion = this.authoritativeVersion
      const result = await this.read<unknown>(`${this.device()}/monitoring/${action}`, abort.signal, { method: 'POST',
        headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ commandId: command.id, ...body }) })
      if (!record(result) || !(action === 'start' ? ['STARTED', 'ALREADY_RUNNING'].includes(String(result.outcome)) : result.outcome === 'STOPPED')) {
        throw new Error('Invalid Backend command result.')
      }
      // A late ACK cannot reinstate a no-op cache invalidated by authoritative
      // contrary state observed after this request began executing.
      const contraryVersion = action === 'start' ? this.lastStoppedVersion : this.lastRunningVersion
      command.done = contraryVersion <= issuedVersion
      if (!command.done && this.lastCommand === command) this.lastCommand = null
    }).finally(() => { command.promise = null; this.commandAborts.delete(abort) })
    this.commandTail = command.promise
    return command.promise
  }
}

function add<T>(listeners: Set<Listener<T>>, listener: Listener<T>) {
  listeners.add(listener); return () => { listeners.delete(listener) }
}
function publish<T>(listeners: Set<Listener<T>>, value: T) { for (const listener of listeners) listener(value) }
function publishLossy<T>(listeners: Set<Listener<T>>, value: T) {
  for (const listener of listeners) {
    try { listener(value) } catch { /* A lossy observer cannot prevent socket/URL cleanup. */ }
  }
}
function close(socket: WebSocket) { socket.onopen = null; socket.onmessage = null; socket.onclose = null; socket.onerror = null; socket.close() }
function record(value: unknown): value is Record<string, unknown> { return !!value && typeof value === 'object' && !Array.isArray(value) }
function sequence(value: unknown): value is number { return Number.isSafeInteger(value) && (value as number) >= 0 }
function measurementRecord(value: unknown): value is BackendMeasurementRecord {
  return record(value) && typeof value.id === 'string' && typeof value.sessionId === 'string' && measurement(value.measurement)
}
function nullableNumber(value: unknown) { return value === null || typeof value === 'number' && Number.isFinite(value) }
function measurement(value: unknown): boolean {
  return record(value) && Number.isFinite(value.timestamp) && typeof value.status === 'string' && nullableNumber(value.diameterMm) &&
    nullableNumber(value.confidence) && Array.isArray(value.evidence) && value.evidence.every(evidence =>
      record(evidence) && typeof evidence.cameraId === 'string' && Number.isFinite(evidence.timestamp) && sequence(evidence.sequence) &&
      nullableNumber(evidence.pixelWidth) && nullableNumber(evidence.diameterMm) && nullableNumber(evidence.confidence) &&
      typeof evidence.valid === 'boolean' && typeof evidence.source === 'string')
}
