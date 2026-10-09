import { api } from './client'
import type {
  AdvisorStatus,
  AdvisorEngine,
  ConversationDetail,
  ConversationSummary,
  EntityId,
  MessageView,
} from '@/types/api'

export function fetchAdvisorStatus() {
  return api.get<AdvisorStatus>('/advisor/status')
}

export function listConversations() {
  return api.get<ConversationSummary[]>('/advisor/conversations')
}

export function createConversation(title?: string, engine?: AdvisorEngine) {
  return api.post<ConversationDetail>('/advisor/conversations', { title, engine })
}

export function cancelAdvisorRun(id: EntityId) {
  return api.post<void>(`/advisor/conversations/${id}/cancel`)
}

// ID 全程保持字符串，避免精度丢失。
export function getConversation(id: EntityId) {
  return api.get<ConversationDetail>(`/advisor/conversations/${id}`)
}

export function deleteConversation(id: EntityId) {
  return api.delete<void>(`/advisor/conversations/${id}`)
}

export function updateConversationContext(id: EntityId, note: string) {
  return api.put<void>(`/advisor/conversations/${id}/context`, { note })
}

export function sendMessage(id: EntityId, message: string) {
  return api.post<MessageView>(`/advisor/conversations/${id}/messages`, { message }, { timeout: 660_000 })
}
