import { beforeEach, describe, expect, it } from 'vitest'
import { authState } from '../services/auth.js'
import { authGuard } from './index.js'

describe('authGuard', () => {
  beforeEach(() => {
    authState.loaded = true
    authState.user = null
  })

  it('redirects an anonymous user away from a protected route', async () => {
    const result = await authGuard({ path: '/materials', fullPath: '/materials', meta: { requiresAuth: true } })
    expect(result).toEqual({ path: '/login', query: { redirect: '/materials' } })
  })

  it('redirects an authenticated user away from the login page', async () => {
    authState.user = { id: 1, role: 'teacher' }
    const result = await authGuard({ path: '/login', fullPath: '/login', meta: {} })
    expect(result).toBe('/materials')
  })

  it.each(['teacher', 'student'])('allows an authenticated %s to enter search', async (role) => {
    authState.user = { id: 1, role }
    const result = await authGuard({ path: '/search', fullPath: '/search', meta: { requiresAuth: true } })
    expect(result).toBe(true)
  })
})
