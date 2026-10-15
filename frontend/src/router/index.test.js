import { beforeEach, describe, expect, it } from 'vitest'
import { authState } from '../services/auth.js'
import router, { authGuard } from './index.js'

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

  it('keeps the old search URL as a redirect to materials', () => {
    const route = router.getRoutes().find((item) => item.path === '/search')
    expect(route.redirect).toBe('/materials')
  })

  it('allows only teachers to enter assistant configuration', async () => {
    authState.user = { id: 1, role: 'student' }
    await expect(authGuard({ path: '/assistants', fullPath: '/assistants', meta: {
      requiresAuth: true, role: 'teacher',
    } })).resolves.toBe('/conversations')

    authState.user = { id: 2, role: 'teacher' }
    await expect(authGuard({ path: '/assistants', fullPath: '/assistants', meta: {
      requiresAuth: true, role: 'teacher',
    } })).resolves.toBe(true)
  })
})
