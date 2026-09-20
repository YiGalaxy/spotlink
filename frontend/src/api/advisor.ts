import { api } from './client'
import type { AdvisorStatus, ChatResponse } from '@/types/api'

export function fetchAdvisorStatus() {
  return api.get<AdvisorStatus>('/advisor/status')
}

export function askAdvisor(message: string) {
  return api.post<ChatResponse>('/advisor/chat', { message })
}
