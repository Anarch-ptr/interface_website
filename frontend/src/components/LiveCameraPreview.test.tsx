import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { LiveCameraPreview } from './LiveCameraPreview'

describe('LiveCameraPreview', () => {
  it('waits without creating an image when no Backend frame is available', () => {
    render(<LiveCameraPreview preview={null} />)
    expect(screen.getByText('Waiting for Raspberry Pi')).toBeVisible()
    expect(screen.queryByRole('img')).not.toBeInTheDocument()
  })

  it('replaces the one displayed frame without revoking source-owned URLs', () => {
    const revoke = vi.fn()
    vi.stubGlobal('URL', class extends URL { static revokeObjectURL = revoke })
    try {
      const view = render(<LiveCameraPreview preview={{ url: 'blob:first', width: 640, height: 480, timestamp: 1000, sourceSequence: 1 }} />)
      expect(screen.getByRole('img', { name: 'Live camera preview' })).toHaveAttribute('src', 'blob:first')
      view.rerender(<LiveCameraPreview preview={{ url: 'blob:second', width: 1280, height: 720, timestamp: 2000, sourceSequence: 2 }} />)
      expect(screen.getAllByRole('img')).toHaveLength(1)
      expect(screen.getByRole('img')).toHaveAttribute('src', 'blob:second')
      expect(screen.getByLabelText('Preview captured at')).toHaveAttribute('datetime', '1970-01-01T00:00:02.000Z')
      view.unmount()
      expect(revoke).not.toHaveBeenCalled()
    } finally { vi.unstubAllGlobals() }
  })
})
