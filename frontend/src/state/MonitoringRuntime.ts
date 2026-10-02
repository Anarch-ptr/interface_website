import type { MonitoringStore } from './MonitoringStore'

export class MonitoringRuntime {
  private readonly store: MonitoringStore
  private leaseCount = 0
  private lifecycleGeneration = 0
  private disposed = false

  constructor(store: MonitoringStore) {
    this.store = store
  }

  acquire(): () => void {
    if (this.disposed) throw new Error('A disposed monitoring runtime cannot be acquired.')

    this.lifecycleGeneration += 1
    this.leaseCount += 1
    if (this.leaseCount === 1) this.store.start()

    let released = false
    return () => {
      if (released || this.disposed) return
      released = true
      this.leaseCount = Math.max(0, this.leaseCount - 1)
      if (this.leaseCount !== 0) return

      const releaseGeneration = ++this.lifecycleGeneration
      queueMicrotask(() => {
        if (
          !this.disposed &&
          this.leaseCount === 0 &&
          this.lifecycleGeneration === releaseGeneration
        ) {
          this.store.stop()
        }
      })
    }
  }

  dispose(): void {
    if (this.disposed) return
    this.disposed = true
    this.leaseCount = 0
    this.lifecycleGeneration += 1
    this.store.dispose()
  }
}
