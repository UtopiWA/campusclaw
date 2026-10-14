import { apiFetch, apiRequest, readApiError } from './api.js'
import { parseEventStream } from './sse.js'

export const listConversations = () => apiRequest('/api/conversations')
export const getConversation = (id) => apiRequest(`/api/conversations/${id}`)
export const createConversation = (assistantId) => apiRequest('/api/conversations', {
  method: 'POST', body: JSON.stringify({ assistantId }),
})

/** 发送一轮对话并逐事件通知界面；首帧前错误仍按普通 JSON 错误处理。 */
export async function streamConversationMessage(conversationId, content, onEvent) {
  const response = await apiFetch(`/api/conversations/${conversationId}/messages`, {
    method: 'POST',
    headers: { Accept: 'text/event-stream' },
    body: JSON.stringify({ content }),
  })
  if (!response.ok) throw await readApiError(response)
  if (!response.body) throw new Error('浏览器无法读取流式响应。')
  return parseEventStream(response.body, onEvent)
}
