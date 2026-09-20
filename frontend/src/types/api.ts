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

// ---- commodity / inventory ----

export interface CategoryNode {
  id: EntityId
  parentId: EntityId
  code: string
  name: string
  level: number
  unit: string
  sortOrder: number
  children: CategoryNode[]
}

export interface WarehouseView {
  id: EntityId
  code: string
  name: string
  shortName: string | null
  province: string | null
  city: string | null
  address: string | null
  contactName: string | null
  contactPhone: string | null
}

/**
 * An electronic inventory note (电子库存单).
 *
 * All three quantity figures are present, not just the available one: a seller
 * who sees only "available" cannot tell whether goods are gone or merely
 * reserved by an active listing.
 */
export interface InventoryNoteView {
  id: EntityId
  noteNo: string
  categoryId: EntityId
  categoryName: string
  warehouseId: EntityId
  warehouseName: string
  commodityName: string
  brand: string | null
  origin: string | null
  spec: Record<string, unknown>
  totalQuantity: number
  availableQuantity: number
  frozenQuantity: number
  unit: string
  status: number
  statusText: string
  createdAt: string
}

// ---- trading ----

export interface ListingView {
  id: EntityId
  listingNo: string
  side: 'SELL' | 'BUY'
  sideText: string
  enterpriseId: EntityId
  enterpriseName: string
  categoryId: EntityId
  categoryName: string
  commodityName: string
  brand: string | null
  origin: string | null
  spec: Record<string, unknown>
  quantity: number
  remainingQuantity: number
  unit: string
  price: number | null
  priceType: 'FIXED' | 'NEGOTIABLE'
  priceText: string
  warehouseId: EntityId | null
  warehouseName: string
  deliveryMethod: string
  deliveryMethodText: string
  validUntil: string
  status: string
  statusText: string
  /** True when the caller owns this listing — you cannot accept your own offer. */
  mine: boolean
  createdAt: string
}

export interface OrderView {
  id: EntityId
  orderNo: string
  listingId: EntityId | null
  buyerId: EntityId
  buyerName: string
  sellerId: EntityId
  sellerName: string
  myRole: 'BUYER' | 'SELLER' | '—'
  counterpartyName: string
  categoryId: EntityId
  categoryName: string
  commodityName: string
  quantity: number
  unit: string
  price: number
  amount: number
  amountText: string
  warehouseName: string
  deliveryMethodText: string
  status: string
  statusText: string
  /** Transitions this caller may actually perform — the same table the server enforces. */
  allowedActions: string[]
  confirmedAt: string | null
  cancelledAt: string | null
  cancelReason: string | null
  createdAt: string
}

export interface OrderStatusLogEntry {
  fromStatus: string
  fromText: string
  toStatus: string
  toText: string
  operator: string
  reason: string
  createdAt: string
}

export interface ContractView {
  id: EntityId
  contractNo: string
  orderId: EntityId
  title: string
  buyerId: EntityId
  buyerName: string
  sellerId: EntityId
  sellerName: string
  commodityName: string
  quantity: number
  unit: string
  price: number
  amount: number
  weightTolerance: number
  terms: Record<string, unknown>
  status: string
  statusText: string
  mySigned: boolean
  counterpartySigned: boolean
  buyerSignedAt: string | null
  sellerSignedAt: string | null
  createdAt: string
}

// ---- market data ----

export interface QuoteRow {
  categoryId: EntityId
  categoryName: string
  latestPrice: number | null
  previousPrice: number | null
  change: number | null
  changePercent: number | null
  tradeCount: number
  volume: number | null
  unit: string
  lastTradedAt: string | null
}

/**
 * A point on a market series.
 *
 * `tradeCount` accompanies every average on purpose: on a thin spot market an
 * average of one trade and an average of forty look identical on a chart, and
 * that difference is the whole question of how much the number can be trusted.
 * `value` is null for days when nothing traded, so the line breaks rather than
 * drawing straight through a gap.
 */
export interface SeriesPoint {
  time: string
  value: number | null
  tradeCount: number
  volume: number | null
}

export interface SeriesData {
  seriesKey: string
  label: string
  unit: string
  /** 'line' or 'bar' — the backend decides, since it knows the data's shape. */
  kind: string
  points: SeriesPoint[]
}

export interface AdvisorStatus {
  available: boolean
  enabled: boolean
  endpoint: string
  model: string
  maxIterations: number
  registeredTools: string[]
}
