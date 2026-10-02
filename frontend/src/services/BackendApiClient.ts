import type { CameraId, FieldError, InspectorLease, InspectorSnapshot, UdpConfigurationState, UdpDiagnostics, UdpInputProfileSet } from '../domain/udpInput'

interface Envelope<T> { correlationId: string; data: T }
interface ErrorEnvelope { code: string; message: string; correlationId: string; details?: { fieldErrors?: FieldError[] } }
export class BackendApiError extends Error {
  readonly status: number; readonly code: string; readonly correlationId: string; readonly fieldErrors: FieldError[]
  constructor(status: number, code: string, message: string, correlationId: string, fieldErrors: FieldError[] = []) {
    super(message); this.status = status; this.code = code; this.correlationId = correlationId; this.fieldErrors = fieldErrors
  }
}

export class BackendApiClient {
  private readonly baseUrl: string; private readonly commandId: () => string
  constructor(baseUrl = '/api/v1', commandId: () => string = () => crypto.randomUUID()) {
    this.baseUrl = baseUrl; this.commandId = commandId
  }
  getUdpConfiguration(device: string, signal?: AbortSignal) { return this.get<UdpConfigurationState>(`${this.device(device)}/udp-input-configuration`, signal) }
  saveUdpConfiguration(device: string, configuration: UdpInputProfileSet, signal?: AbortSignal) {
    return this.json<UdpConfigurationState>(`${this.device(device)}/udp-input-configuration`, 'PUT', { commandId: this.commandId(), configuration }, signal)
  }
  applyUdpConfiguration(device: string, revision: number, expectedSavedRevision: number, signal?: AbortSignal) {
    return this.json<UdpConfigurationState>(`${this.device(device)}/udp-input-configuration/apply`, 'POST',
      { commandId: this.commandId(), revision, expectedSavedRevision }, signal)
  }
  getUdpDiagnostics(device: string, camera: CameraId, signal?: AbortSignal) {
    return this.get<UdpDiagnostics>(`${this.camera(device, camera)}/udp-diagnostics`, signal)
  }
  createInspectorLease(device: string, camera: CameraId, previewBytes: 64 | 128 | 256, signal?: AbortSignal) {
    return this.json<InspectorLease>(`${this.camera(device, camera)}/packet-inspector/lease`, 'POST', { previewBytes }, signal)
  }
  releaseInspectorLease(device: string, camera: CameraId, lease: string, signal?: AbortSignal) {
    return this.json<{ deleted: boolean }>(`${this.camera(device, camera)}/packet-inspector/lease/${encodeURIComponent(lease)}`, 'DELETE', undefined, signal)
  }
  async getInspector(device: string, camera: CameraId, lease: string, previewBytes: 64 | 128 | 256,
    etag?: string, signal?: AbortSignal): Promise<{ unchanged: true; etag: string | null } | { unchanged: false; etag: string | null; snapshot: InspectorSnapshot }> {
    const response = await fetch(`${this.camera(device, camera)}/packet-inspector?leaseId=${encodeURIComponent(lease)}&previewBytes=${previewBytes}`,
      { headers: etag ? { 'If-None-Match': etag } : undefined, signal })
    if (response.status === 304) return { unchanged: true, etag: response.headers.get('ETag') }
    const snapshot = await this.read<InspectorSnapshot>(response)
    return { unchanged: false, etag: response.headers.get('ETag'), snapshot }
  }
  private device(device: string) { return `${this.baseUrl}/devices/${encodeURIComponent(device)}` }
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
