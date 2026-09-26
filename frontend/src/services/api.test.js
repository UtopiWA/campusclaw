import { beforeEach, describe, expect, it, vi } from 'vitest'
import { apiRequest, clearCsrfToken } from './api.js'

describe('apiRequest', () => {
  beforeEach(() => {
    clearCsrfToken()
    vi.restoreAllMocks()
  })

  it('fetches a CSRF token before a write request', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({ token: 'csrf-token' }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ ok: true }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }))
    vi.stubGlobal('fetch', fetchMock)

    await apiRequest('/api/example', { method: 'POST', body: JSON.stringify({ value: 1 }) })

    expect(fetchMock).toHaveBeenCalledTimes(2)
    const requestOptions = fetchMock.mock.calls[1][1]
    expect(requestOptions.headers.get('X-XSRF-TOKEN')).toBe('csrf-token')
    expect(requestOptions.credentials).toBe('same-origin')
  })

  it('emits an unauthorized event for a 401 response', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ error: 'no' }), {
      status: 401,
      headers: { 'Content-Type': 'application/json' },
    })))
    const listener = vi.fn()
    window.addEventListener('campusclaw:unauthorized', listener, { once: true })

    await expect(apiRequest('/api/materials')).rejects.toMatchObject({ status: 401 })
    expect(listener).toHaveBeenCalledOnce()
  })

  it('returns text and blob bodies for file endpoints', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response('讲义正文', { status: 200, headers: { 'Content-Type': 'text/plain' } }))
      .mockResolvedValueOnce(new Response(new Blob(['download']), { status: 200 }))
    vi.stubGlobal('fetch', fetchMock)

    await expect(apiRequest('/api/materials/1/content', { responseType: 'text' })).resolves.toBe('讲义正文')
    const blob = await apiRequest('/api/materials/1/download', { responseType: 'blob' })

    expect(blob.size).toBeGreaterThan(0)
    expect(typeof blob.arrayBuffer).toBe('function')
    expect(fetchMock.mock.calls[0][1]).not.toHaveProperty('responseType')
    expect(fetchMock.mock.calls[1][1]).not.toHaveProperty('responseType')
  })
})
