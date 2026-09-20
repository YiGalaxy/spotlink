import { api } from './client'
import type {
  AdvisorStatus,
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

export function createConversation(title?: string) {
  return api.post<ConversationDetail>('/advisor/conversations', { title })
}

// Id 从头到尾都保持字符串。在这里把某个 Id 转成数字会悄悄改变它的值，
// 于是每一次查找都会落空。
export function getConversation(id: EntityId) {
  return api.get<ConversationDetail>(`/advisor/conversations/${id}`)
}

export function deleteConversation(id: EntityId) {
  return api.delete<void>(`/advisor/conversations/${id}`)
}

export function sendMessage(id: EntityId, message: string) {
  return api.post<MessageView>(`/advisor/conversations/${id}/messages`, { message })
}
