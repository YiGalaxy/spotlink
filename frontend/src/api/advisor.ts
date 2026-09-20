import { api } from './client'
import type {
  AdvisorStatus,
  ConversationDetail,
  ConversationSummary,
  MessageView,
} from '@/types/api'

export function fetchAdvisorStatus() {
  return api.get<AdvisorStatus>('/advisor/status')
}

export function listConversations() {
  return api.get<ConversationSummary[]>('/advisor/conversations')
}

export function createConversation(title?: string) {
  return api.post<ConversationDetail>('/advisor/conversations', { title })
}

export function getConversation(id: number) {
  return api.get<ConversationDetail>(`/advisor/conversations/${id}`)
}

export function deleteConversation(id: number) {
  return api.delete<void>(`/advisor/conversations/${id}`)
}

export function sendMessage(id: number, message: string) {
  return api.post<MessageView>(`/advisor/conversations/${id}/messages`, { message })
}
