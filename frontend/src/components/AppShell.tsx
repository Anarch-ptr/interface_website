import type { ReactNode } from 'react'

export type AppView = 'monitor' | 'alarms' | 'settings'

interface AppShellProps {
  activeView: AppView
  onViewChange: (view: AppView) => void
  children: ReactNode
}

const navigationItems: ReadonlyArray<{ view: AppView; label: string; glyph: string }> = [
  { view: 'monitor', label: 'Monitor', glyph: '⌁' },
  { view: 'alarms', label: 'Alarm History', glyph: '!' },
  { view: 'settings', label: 'Settings', glyph: '◫' },
]

export function AppShell({ activeView, onViewChange, children }: AppShellProps) {
  return (
    <div className="app-shell">
      <aside className="navigation-rail">
        <div className="brand-mark" aria-hidden="true"><span /></div>
        <div className="brand-copy">
          <p className="eyebrow">FILAMENT VISION</p>
          <h1>FilamentVision</h1>
          <p>Camera diameter monitor</p>
        </div>
        <nav aria-label="Primary navigation">
          {navigationItems.map((item) => (
            <button
              key={item.view}
              type="button"
              className={activeView === item.view ? 'nav-item active' : 'nav-item'}
              aria-current={activeView === item.view ? 'page' : undefined}
              onClick={() => onViewChange(item.view)}
            >
              <span className="nav-glyph" aria-hidden="true">{item.glyph}</span>
              {item.label}
            </button>
          ))}
        </nav>
        <div className="rail-foot">
          <div><strong>RASPBERRY PI</strong><span>Backend monitoring source</span></div>
        </div>
      </aside>
      <div className="workspace">
        <header className="topbar">
          <div><p className="eyebrow">ENG5105 · DEVICE 01</p><p className="topbar-title">Filament Vision Sensor</p></div>
          <div className="topbar-clock" aria-label="Monitoring source mode">Raspberry Pi UDP</div>
        </header>
        <main>{children}</main>
      </div>
    </div>
  )
}
