/** Mirrors the backend's ApiResponse envelope. */
export interface ApiResponse<T> {
  code: number
  message: string
  data: T
  timestamp: number
}

/**
 * Entity ids are strings, not numbers.
 *
 * A snowflake id is 19 digits; JavaScript numbers are only exact to 16. Parsing
 * `2101635756223557634` as a number yields `2101635756223557600`, so an id the
 * client echoes back would not match the row it came from. The backend
 * serialises every id as a string and the type system keeps it that way.
 */
export type EntityId = string

/** Mirrors LoginResponse in the identity module. */
export interface UserProfile {
  userId: EntityId
  username: string
  realName: string | null
  enterpriseId: EntityId | null
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
  id: EntityId | null
  role: 'user' | 'assistant'
  content: string
  toolCalls: ToolCallView[]
  iterations: number | null
  usage: TokenUsage | null
  createdAt: string
}

export interface ConversationSummary {
  id: EntityId
  title: string
  messageCount: number
  lastMessageAt: string | null
  createdAt: string
}

export interface ConversationDetail {
  id: EntityId
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
