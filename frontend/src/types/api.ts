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

// ---- advisor ----

export interface ToolCallView {
  name: string
  input: string
  output: string
}

export interface TokenUsage {
  inputTokens: number
  outputTokens: number
  cacheReadTokens: number
  cacheCreationTokens: number
}

export interface MessageView {
  id: number | null
  role: 'user' | 'assistant'
  content: string
  toolCalls: ToolCallView[]
  iterations: number | null
  usage: TokenUsage | null
  createdAt: string
}

export interface ConversationSummary {
  id: number
  title: string
  messageCount: number
  lastMessageAt: string | null
  createdAt: string
}

export interface ConversationDetail {
  id: number
  title: string
  messages: MessageView[]
}

export interface AdvisorStatus {
  available: boolean
  enabled: boolean
  endpoint: string
  model: string
  maxIterations: number
  registeredTools: string[]
}
