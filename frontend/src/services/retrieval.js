import { apiRequest } from './api.js'

export function searchKnowledge(query, mode = 'hybrid', limit = 10) {
  return apiRequest('/api/retrieval/search', {
    method: 'POST',
    body: JSON.stringify({ query, mode, limit }),
  })
}

export function askKnowledge(question, history = []) {
  return apiRequest('/api/ask', {
    method: 'POST',
    body: JSON.stringify({ question, history }),
  })
}
