import { reactive } from 'vue'
import { apiRequest, clearCsrfToken } from './api.js'

export const authState = reactive({
  user: null,
  loaded: false,
})

export async function loadCurrentUser(force = false) {
  if (authState.loaded && !force) {
    return authState.user
  }
  try {
    authState.user = await apiRequest('/api/auth/me')
  } catch (error) {
    if (error.status !== 401) {
      throw error
    }
    authState.user = null
  } finally {
    authState.loaded = true
  }
  return authState.user
}

export async function login(username, password) {
  authState.user = await apiRequest('/api/auth/login', {
    method: 'POST',
    body: JSON.stringify({ username, password }),
  })
  authState.loaded = true
  return authState.user
}

export async function logout() {
  try {
    await apiRequest('/api/auth/logout', { method: 'POST' })
  } finally {
    clearAuthentication()
  }
}

export function clearAuthentication() {
  authState.user = null
  authState.loaded = true
  clearCsrfToken()
}

if (typeof window !== 'undefined') {
  window.addEventListener('campusclaw:unauthorized', clearAuthentication)
}

