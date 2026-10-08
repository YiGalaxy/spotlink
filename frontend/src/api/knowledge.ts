import { api } from './client'

export interface KnowledgeStats {
  chunks: number
  embedded: number
  pending: number
  model: string
  embeddingEnabled: boolean
}

export interface KnowledgeHit {
  docCode: string
  title: string
  score: string
  content: string
}

export function fetchKnowledgeStats() {
  return api.get<KnowledgeStats>('/admin/knowledge/stats')
}

export function embedPending(limit = 200) {
  return api.post<{ embedded: number; stats: KnowledgeStats }>(
    `/admin/knowledge/embed-pending?limit=${limit}`,
  )
}

export function searchKnowledge(question: string, topK = 5) {
  return api.get<KnowledgeHit[]>('/admin/knowledge/search', { question, topK })
}
