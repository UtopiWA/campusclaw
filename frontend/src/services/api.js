const ACCESS_TOKEN_KEY = 'campusclaw.access-token'

export class ApiError extends Error {
  constructor(status, message) {
    super(message)
    this.status = status
  }
}

export function getAccessToken() {
  return typeof window === 'undefined' ? null : window.sessionStorage.getItem(ACCESS_TOKEN_KEY)
}

export function setAccessToken(token) {
  if (typeof window !== 'undefined') {
    window.sessionStorage.setItem(ACCESS_TOKEN_KEY, token)
  }
}

export function clearAccessToken() {
  if (typeof window !== 'undefined') {
    window.sessionStorage.removeItem(ACCESS_TOKEN_KEY)
  }
}

/** 发起统一认证请求；流式接口可直接消费返回的 Response。 */
export async function apiFetch(path, options = {}) {
  const { anonymous = false, ...requestOptions } = options
  const method = (requestOptions.method || 'GET').toUpperCase()
  const headers = new Headers(requestOptions.headers || {})
  const token = anonymous ? null : getAccessToken()
  if (token) {
    headers.set('Authorization', `Bearer ${token}`)
  }
  if (requestOptions.body && !(requestOptions.body instanceof FormData) && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json')
  }

  const response = await fetch(path, {
    ...requestOptions,
    method,
    headers,
  })

  if (response.status === 401) {
    clearAccessToken()
    if (typeof window !== 'undefined') {
      // 让共享身份状态和路由逻辑独立于底层传输实现。
      window.dispatchEvent(new CustomEvent('campusclaw:unauthorized'))
    }
  }
  return response
}

/** 从失败响应提取稳定、可展示的服务端错误。 */
export async function readApiError(response) {
  const body = await response.json().catch(() => ({}))
  return new ApiError(response.status, body.error || `请求失败（${response.status}）`)
}

export async function apiRequest(path, options = {}) {
  // 统一附加 Bearer 令牌并处理各类响应，登录等公开请求可通过 anonymous 跳过令牌。
  const { responseType = 'json', ...requestOptions } = options
  const response = await apiFetch(path, requestOptions)
  if (!response.ok) {
    throw await readApiError(response)
  }
  if (response.status === 204) {
    return null
  }
  if (responseType === 'text') {
    return response.text()
  }
  if (responseType === 'blob') {
    return response.blob()
  }
  return response.json()
}
