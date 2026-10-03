import type { CameraPreview } from '../domain/monitoring'

export function LiveCameraPreview({ preview }: { preview: CameraPreview | null }) {
  return <section className="panel preview-panel" aria-labelledby="preview-title">
    <div className="panel-heading"><div><p className="panel-kicker">CAMERA STREAM</p><h3 id="preview-title">Live Camera Preview</h3></div></div>
    {preview ? <figure className="camera-preview">
      <img src={preview.url} width={preview.width} height={preview.height} alt="Live camera preview" />
      <figcaption><span>{preview.width} × {preview.height}</span><time aria-label="Preview captured at" dateTime={new Date(preview.timestamp).toISOString()}>{new Date(preview.timestamp).toLocaleTimeString()}</time></figcaption>
    </figure> : <div className="preview-waiting" role="status">Waiting for Raspberry Pi</div>}
  </section>
}
