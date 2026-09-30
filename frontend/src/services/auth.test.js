import { beforeEach, describe, expect, it, vi } from 'vitest'
import { authState, clearAuthentication, loadCurrentUser, login, logout } from './auth.js'
import { getAccessToken, setAccessToken } from './api.js'

describe('authentication state', () => {
  beforeEach(() => {
    clearAuthentication()
    vi.restoreAllMocks()
  })

  it('stores a successful login token and user', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({
      tokenType: 'Bearer',
      accessToken: 'new-token',
      user: { id: 1, username: 'teacher-a', role: 'teacher' },
    }), { status: 200, headers: { 'Content-Type': 'application/json' } })))

    await expect(login('teacher-a', 'password')).resolves.toMatchObject({ id: 1, role: 'teacher' })

    expect(getAccessToken()).toBe('new-token')
    expect(authState.loaded).toBe(true)
  })

  it('restores the user from the current tab token', async () => {
    setAccessToken('saved-token')
    authState.loaded = false
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({
      id: 2, username: 'student-a1', role: 'student', classId: 1,
    }), { status: 200, headers: { 'Content-Type': 'application/json' } }))
    vi.stubGlobal('fetch', fetchMock)

    await expect(loadCurrentUser()).resolves.toMatchObject({ id: 2, role: 'student' })

    expect(fetchMock.mock.calls[0][1].headers.get('Authorization')).toBe('Bearer saved-token')
  })

  it('does not call the server when the tab has no token', async () => {
    authState.loaded = false
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)

    await expect(loadCurrentUser()).resolves.toBeNull()

    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('clears the token and user on logout', async () => {
    setAccessToken('saved-token')
    authState.user = { id: 1, role: 'teacher' }
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null, { status: 204 })))

    await logout()

    expect(getAccessToken()).toBeNull()
    expect(authState.user).toBeNull()
  })
})
