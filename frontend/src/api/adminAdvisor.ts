import { api } from './client'

export interface ModelSettings {
  enabled: boolean
  baseUrl: string
  model: string
  hasKey: boolean
  maxTokens: number
  timeoutSeconds: number
  tokenParameter: 'max_tokens' | 'max_completion_tokens'
  source: 'environment' | 'admin'
  encryptionReady: boolean
  canEdit: boolean
  updatedAt: string | null
}
export interface ModelUpdate extends Pick<ModelSettings, 'enabled' | 'baseUrl' | 'model' | 'maxTokens' | 'timeoutSeconds' | 'tokenParameter'> {
  apiKey?: string
  clearApiKey: boolean
}
export interface ConnectionResult {
  connected: boolean
  toolCalling: boolean
  durationMs: number
  message: string
}
export const fetchModelSettings = () => api.get<ModelSettings>('/admin/advisor-model')
export const saveModelSettings = (value: ModelUpdate) => api.put<ModelSettings>('/admin/advisor-model', value)
export const resetModelSettings = () => api.delete<ModelSettings>('/admin/advisor-model')
export const testModelConnection = () => api.post<ConnectionResult>('/admin/advisor-model/test', undefined, { timeout: 660_000 })
