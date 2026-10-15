import { apiRequest } from './api.js'

export const listAssistants = () => apiRequest('/api/assistants')
export const getAssistant = (id) => apiRequest(`/api/assistants/${id}`)
export const createAssistant = (payload) => apiRequest('/api/assistants', {
  method: 'POST', body: JSON.stringify(payload),
})
export const updateAssistant = (id, payload) => apiRequest(`/api/assistants/${id}`, {
  method: 'PUT', body: JSON.stringify(payload),
})
export const deleteAssistant = (id) => apiRequest(`/api/assistants/${id}`, { method: 'DELETE' })

export const listSkills = () => apiRequest('/api/skills')
export const createSkill = (sourceText) => apiRequest('/api/skills', {
  method: 'POST', body: JSON.stringify({ sourceText }),
})
export const updateSkill = (id, sourceText) => apiRequest(`/api/skills/${id}`, {
  method: 'PUT', body: JSON.stringify({ sourceText }),
})
export const deleteSkill = (id) => apiRequest(`/api/skills/${id}`, { method: 'DELETE' })

export const listMaterials = () => apiRequest('/api/materials')
