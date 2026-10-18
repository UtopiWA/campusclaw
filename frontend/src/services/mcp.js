import { apiRequest } from './api.js'

export const listMcpServers = () => apiRequest('/api/mcp/servers')
export const createMcpServer = (payload) => apiRequest('/api/mcp/servers', {
  method: 'POST', body: JSON.stringify(payload),
})
export const updateMcpServer = (id, payload) => apiRequest(`/api/mcp/servers/${id}`, {
  method: 'PUT', body: JSON.stringify(payload),
})
export const deleteMcpServer = (id) => apiRequest(`/api/mcp/servers/${id}`, { method: 'DELETE' })
export const connectMcpServer = (id) => apiRequest(`/api/mcp/servers/${id}/connect`, { method: 'POST' })
export const disconnectMcpServer = (id) => apiRequest(`/api/mcp/servers/${id}/disconnect`, { method: 'POST' })
export const listMcpTools = (id) => apiRequest(`/api/mcp/servers/${id}/tools`)
export const listMcpToolCalls = (limit = 50) => apiRequest(`/api/mcp/tool-calls?limit=${limit}`)
