import type { CameraId, InspectorLease, InspectorSnapshot, UdpConfigurationState, UdpDiagnostics, UdpInputProfileSet } from '../domain/udpInput'
import type * as Single from '../domain/raspberryPiUdpInput'

interface Envelope<T> { correlationId: string; data: T }
interface ErrorEnvelope { code: string; message: string; correlationId: string; details?: { fieldErrors?: Single.FieldError[] } }
export class BackendApiError extends Error {
  readonly status: number; readonly code: string; readonly correlationId: string; readonly fieldErrors: Single.FieldError[]
  constructor(status: number, code: string, message: string, correlationId: string, fieldErrors: Single.FieldError[] = []) {
    super(message); this.status = status; this.code = code; this.correlationId = correlationId; this.fieldErrors = fieldErrors
  }
}

export class BackendApiClient {
  private readonly baseUrl: string; private readonly commandId: () => string
  constructor(baseUrl = '/api/v1', commandId: () => string = () => crypto.randomUUID()) {
    this.baseUrl = baseUrl; this.commandId = commandId
  }
  getRaspberryPiUdpConfiguration(device: string, signal?: AbortSignal): Promise<Single.UdpConfigurationState> {
    return this.singleGet(`${this.device(device)}/udp-input-configuration`, signal)
  }
  saveRaspberryPiUdpConfiguration(device: string, configuration: Single.RaspberryPiUdpInputProfile,
    expectedSavedRevision: number, signal?: AbortSignal): Promise<Single.UdpConfigurationState> {
    const body: Single.SaveUdpConfigurationRequest = { commandId: this.commandId(), expectedSavedRevision, configuration }
    return this.singleJson(`${this.device(device)}/udp-input-configuration`, 'PUT', body, signal)
  }
  applyRaspberryPiUdpConfiguration(device: string, revision: number, expectedSavedRevision: number,
    signal?: AbortSignal): Promise<Single.UdpConfigurationState> {
    const body: Single.ApplyUdpConfigurationRequest = { commandId: this.commandId(), revision, expectedSavedRevision }
    return this.singleJson(`${this.device(device)}/udp-input-configuration/apply`, 'POST', body, signal)
  }
  getNetworkInterfaces(signal?: AbortSignal): Promise<Single.NetworkInterfaces> {
    return this.singleGet(`${this.baseUrl}/network-interfaces`, signal)
  }
  getRaspberryPiUdpDiagnostics(device: string, signal?: AbortSignal): Promise<Single.UdpDiagnostics> {
    return this.singleGet(`${this.device(device)}/udp-diagnostics`, signal)
  }
  createRaspberryPiInspectorLease(device: string, previewBytes: Single.InspectorPreviewBytes,
    signal?: AbortSignal): Promise<Single.InspectorLease> {
    return this.singleJson(`${this.device(device)}/packet-inspector/lease`, 'POST', { previewBytes }, signal)
  }
  releaseRaspberryPiInspectorLease(device: string, lease: string, signal?: AbortSignal): Promise<{ deleted: boolean }> {
    return this.singleJson(`${this.device(device)}/packet-inspector/lease/${encodeURIComponent(lease)}`, 'DELETE', undefined, signal)
  }
  async getRaspberryPiInspector(device: string, lease: string, previewBytes: Single.InspectorPreviewBytes,
    etag?: string, signal?: AbortSignal): Promise<Single.InspectorPollResult> {
    const response = await this.singleFetch(`${this.device(device)}/packet-inspector?leaseId=${encodeURIComponent(lease)}&previewBytes=${previewBytes}`,
      { headers: etag ? { 'If-None-Match': etag } : undefined, signal })
    if (response.status === 304) return { unchanged: true, etag: response.headers.get('ETag') }
    const snapshot = await this.singleRead<Single.InspectorSnapshot>(response)
    return { unchanged: false, etag: response.headers.get('ETag'), snapshot }
  }

  // Temporary compatibility for the unmigrated Settings UI. Task 21 removes these methods and their old DTOs together.
  /** @deprecated Use getRaspberryPiUdpConfiguration. */
  getUdpConfiguration(device: string, signal?: AbortSignal) { return this.get<UdpConfigurationState>(`${this.device(device)}/udp-input-configuration`, signal) }
  /** @deprecated Use saveRaspberryPiUdpConfiguration with expectedSavedRevision. */
  saveUdpConfiguration(device: string, configuration: UdpInputProfileSet, signal?: AbortSignal) {
    return this.json<UdpConfigurationState>(`${this.device(device)}/udp-input-configuration`, 'PUT', { commandId: this.commandId(), configuration }, signal)
  }
  /** @deprecated Use applyRaspberryPiUdpConfiguration. */
  applyUdpConfiguration(device: string, revision: number, expectedSavedRevision: number, signal?: AbortSignal) {
    return this.json<UdpConfigurationState>(`${this.device(device)}/udp-input-configuration/apply`, 'POST',
      { commandId: this.commandId(), revision, expectedSavedRevision }, signal)
  }
  /** @deprecated Use getRaspberryPiUdpDiagnostics. */
  getUdpDiagnostics(device: string, camera: CameraId, signal?: AbortSignal) {
    return this.get<UdpDiagnostics>(`${this.camera(device, camera)}/udp-diagnostics`, signal)
  }
  /** @deprecated Use createRaspberryPiInspectorLease. */
  createInspectorLease(device: string, camera: CameraId, previewBytes: 64 | 128 | 256, signal?: AbortSignal) {
    return this.json<InspectorLease>(`${this.camera(device, camera)}/packet-inspector/lease`, 'POST', { previewBytes }, signal)
  }
  /** @deprecated Use releaseRaspberryPiInspectorLease. */
  releaseInspectorLease(device: string, camera: CameraId, lease: string, signal?: AbortSignal) {
    return this.json<{ deleted: boolean }>(`${this.camera(device, camera)}/packet-inspector/lease/${encodeURIComponent(lease)}`, 'DELETE', undefined, signal)
  }
  /** @deprecated Use getRaspberryPiInspector. */
  async getInspector(device: string, camera: CameraId, lease: string, previewBytes: 64 | 128 | 256,
    etag?: string, signal?: AbortSignal): Promise<{ unchanged: true; etag: string | null } | { unchanged: false; etag: string | null; snapshot: InspectorSnapshot }> {
    const response = await fetch(`${this.camera(device, camera)}/packet-inspector?leaseId=${encodeURIComponent(lease)}&previewBytes=${previewBytes}`,
      { headers: etag ? { 'If-None-Match': etag } : undefined, signal })
    if (response.status === 304) return { unchanged: true, etag: response.headers.get('ETag') }
    const snapshot = await this.read<InspectorSnapshot>(response)
    return { unchanged: false, etag: response.headers.get('ETag'), snapshot }
  }
  private device(device: string) { return `${this.baseUrl}/devices/${encodeURIComponent(device)}` }
  private async singleFetch(url: string, options: RequestInit): Promise<Response> {
    try { return await fetch(url, options) }
    catch (reason) {
      if (options.signal?.aborted || (record(reason) && reason.name === 'AbortError')) throw reason
      throw new BackendApiError(0, 'NETWORK_ERROR', 'Unable to reach Backend.', '')
    }
  }
  private async singleGet<T>(url: string, signal?: AbortSignal): Promise<T> {
    return this.singleRead<T>(await this.singleFetch(url, { signal }))
  }
  private async singleJson<T>(url: string, method: string, body: unknown, signal?: AbortSignal): Promise<T> {
    return this.singleRead<T>(await this.singleFetch(url, { method, headers: { 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body), signal }))
  }
  private async singleRead<T>(response: Response): Promise<T> {
    const invalid = () => new BackendApiError(response.status, 'INVALID_RESPONSE', 'Invalid Backend response.', '')
    let payload: unknown
    try { payload = await response.json() }
    catch (reason) {
      if (record(reason) && reason.name === 'AbortError') throw reason
      throw invalid()
    }
    if (!record(payload)) throw invalid()
    if (!response.ok) {
      if (typeof payload.code !== 'string' || typeof payload.message !== 'string' || typeof payload.correlationId !== 'string') throw invalid()
      const details = record(payload.details) ? payload.details : undefined
      const fieldErrors = details?.fieldErrors ?? []
      if (!Array.isArray(fieldErrors) || !fieldErrors.every((error) => record(error) && typeof error.path === 'string' && typeof error.message === 'string')) throw invalid()
      throw new BackendApiError(response.status, payload.code, payload.message, payload.correlationId, fieldErrors)
    }
    if (typeof payload.correlationId !== 'string' || !Object.hasOwn(payload, 'data')) throw invalid()
    return payload.data as T
  }
  private camera(device: string, camera: CameraId) { return `${this.device(device)}/cameras/${camera}` }
  private async get<T>(url: string, signal?: AbortSignal) { return this.read<T>(await fetch(url, { signal })) }
  private async json<T>(url: string, method: string, body: unknown, signal?: AbortSignal) {
    return this.read<T>(await fetch(url, { method, headers: { 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body), signal }))
  }
  private async read<T>(response: Response): Promise<T> {
    const payload = await response.json() as Envelope<T> | ErrorEnvelope
    if (!response.ok) { const failure = payload as ErrorEnvelope; throw new BackendApiError(response.status, failure.code,
      failure.message, failure.correlationId, failure.details?.fieldErrors ?? []) }
    return (payload as Envelope<T>).data
  }
}

function record(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}
