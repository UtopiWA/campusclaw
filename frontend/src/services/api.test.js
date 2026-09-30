import { beforeEach, describe, expect, it, vi } from 'vitest'
import { apiRequest, clearAccessToken, getAccessToken, setAccessToken } from './api.js'

describe('apiRequest', () => {
  beforeEach(() => {
    clearAccessToken()
    vi.restoreAllMocks()
  })

  it('attaches the bearer token to JSON and multipart requests', async () => {
    setAccessToken('access-token')
    const fetchMock = vi.fn().mockImplementation(() => Promise.resolve(
      new Response(JSON.stringify({ ok: true }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    ))
    vi.stubGlobal('fetch', fetchMock)

    await apiRequest('/api/example', { method: 'POST', body: JSON.stringify({ value: 1 }) })
    await apiRequest('/api/materials/upload', { method: 'POST', body: new FormData() })

    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(fetchMock.mock.calls[0][1].headers.get('Authorization')).toBe('Bearer access-token')
    expect(fetchMock.mock.calls[0][1].headers.get('Content-Type')).toBe('application/json')
    expect(fetchMock.mock.calls[1][1].headers.get('Authorization')).toBe('Bearer access-token')
    expect(fetchMock.mock.calls[1][1].headers.has('Content-Type')).toBe(false)
    expect(fetchMock.mock.calls[0][1]).not.toHaveProperty('credentials')
  })

  it('does not send an old token on an anonymous request', async () => {
    setAccessToken('old-token')
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({ accessToken: 'new-token' }), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    }))
    vi.stubGlobal('fetch', fetchMock)

    await apiRequest('/api/auth/login', { method: 'POST', body: '{}', anonymous: true })

    expect(fetchMock.mock.calls[0][1].headers.has('Authorization')).toBe(false)
    expect(fetchMock.mock.calls[0][1]).not.toHaveProperty('anonymous')
  })

  it('clears the token and emits an unauthorized event for a 401 response', async () => {
    setAccessToken('expired-token')
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ error: 'no' }), {
      status: 401,
      headers: { 'Content-Type': 'application/json' },
    })))
    const listener = vi.fn()
    window.addEventListener('campusclaw:unauthorized', listener, { once: true })

    await expect(apiRequest('/api/materials')).rejects.toMatchObject({ status: 401 })
    expect(getAccessToken()).toBeNull()
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
