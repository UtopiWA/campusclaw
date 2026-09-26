let csrfToken = null

export class ApiError extends Error {
  constructor(status, message) {
    super(message)
    this.status = status
  }
}

export async function refreshCsrfToken() {
  const response = await fetch('/api/auth/csrf', { credentials: 'same-origin' })
  if (!response.ok) {
    throw new ApiError(response.status, '无法初始化安全会话')
  }
  const body = await response.json()
  csrfToken = body.token
  return csrfToken
}

export async function apiRequest(path, options = {}) {
  const { responseType = 'json', ...requestOptions } = options
  const method = (requestOptions.method || 'GET').toUpperCase()
  const headers = new Headers(requestOptions.headers || {})
  if (!['GET', 'HEAD', 'OPTIONS'].includes(method)) {
    if (!csrfToken) {
      await refreshCsrfToken()
    }
    headers.set('X-XSRF-TOKEN', csrfToken)
  }
  if (requestOptions.body && !(requestOptions.body instanceof FormData) && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json')
  }

  const response = await fetch(path, {
    ...requestOptions,
    method,
    headers,
    credentials: 'same-origin',
  })

  if (response.status === 401 && typeof window !== 'undefined') {
    window.dispatchEvent(new CustomEvent('campusclaw:unauthorized'))
  }
  if (!response.ok) {
    const body = await response.json().catch(() => ({}))
    throw new ApiError(response.status, body.error || `请求失败（${response.status}）`)
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

export function clearCsrfToken() {
  csrfToken = null
}
