import { reactive } from 'vue'
import { apiRequest, clearAccessToken, getAccessToken, setAccessToken } from './api.js'

export const authState = reactive({
  user: null,
  loaded: false,
})

export async function loadCurrentUser(force = false) {
  // 当前标签页没有访问令牌时直接视为未登录，避免无意义的 /me 请求。
  if (authState.loaded && !force) {
    return authState.user
  }
  if (!getAccessToken()) {
    authState.user = null
    authState.loaded = true
    return null
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
  const response = await apiRequest('/api/auth/login', {
    method: 'POST',
    body: JSON.stringify({ username, password }),
    anonymous: true,
  })
  setAccessToken(response.accessToken)
  authState.user = response.user
  authState.loaded = true
  return authState.user
}

export async function logout() {
  try {
    if (getAccessToken()) {
      await apiRequest('/api/auth/logout', { method: 'POST' })
    }
  } finally {
    clearAuthentication()
  }
}

export function clearAuthentication() {
  authState.user = null
  authState.loaded = true
  clearAccessToken()
}

if (typeof window !== 'undefined') {
  // 任意 API 返回 401 时，清除全局身份状态并交由路由守卫回到登录页。
  window.addEventListener('campusclaw:unauthorized', clearAuthentication)
}
