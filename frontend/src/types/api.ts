/** Mirrors the backend's ApiResponse envelope. */
export interface ApiResponse<T> {
  code: number
  message: string
  data: T
  timestamp: number
}

/** Mirrors LoginResponse in the identity module. */
export interface UserProfile {
  userId: number
  username: string
  realName: string | null
  enterpriseId: number | null
  enterpriseName: string | null
  traderCode: string | null
  userType: number
  platformOperator: boolean
}

export interface LoginResponse {
  accessToken: string
  refreshToken: string
  expiresInSeconds: number
  user: UserProfile
}

export interface ToolCall {
  name: string
  input: string
  output: string
}

export interface ChatUsage {
  inputTokens: number
  outputTokens: number
  cacheReadTokens: number
  cacheCreationTokens: number
}

export interface ChatResponse {
  answer: string
  toolCalls: ToolCall[]
  iterations: number
  usage: ChatUsage
}

export interface AdvisorStatus {
  available: boolean
  enabled: boolean
  endpoint: string
  model: string
  maxIterations: number
  registeredTools: string[]
}
