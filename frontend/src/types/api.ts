/** 与后端的 ApiResponse 信封一致。 */
export interface ApiResponse<T> {
  code: number
  message: string
  data: T
  timestamp: number
}

/**
 * 实体 id 是字符串，不是数字。
 *
 * 雪花 id 有 19 位；而 JavaScript 的数字只能精确到 16 位。把
 * `2101635756223557634` 当数字解析会得到 `2101635756223557600`，于是客户端
 * 回传的 id 就对不上它来自的那一行。后端把每个 id 都序列化成字符串，类型
 * 系统则让它保持如此。
 */
export type EntityId = string

/**
 * 展示给未登录访客的平台整体数据。
 *
 * 全部是整个交易场所的聚合量。`tradedAmountText` 由服务端预先格式化，这样
 * 首页和将来任何报表都会以同一种方式取整，而不是各自发明自己对 "3.2 亿元"
 * 的理解。
 */
export interface PublicStats {
  enterpriseCount: number
  openListingCount: number
  tradeCount: number
  tradedQuantity: number
  tradedAmount: number
  tradedAmountText: string
  inventoryQuantity: number
}

/** 与身份模块中的 LoginResponse 一致。 */
export interface UserProfile {
  userId: EntityId
  username: string
  realName: string | null
  enterpriseId: EntityId | null
  enterpriseName: string | null
  traderCode: string | null
  userType: number
  platformOperator: boolean
  permissions?: string[]
  roles?: string[]
}

export interface LoginResponse {
  accessToken: string
  refreshToken: string
  expiresInSeconds: number
  user: UserProfile
}

// ---- 顾问 ----

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
  products: AdvisorProductReference[]
  toolCalls: ToolCallView[]
  iterations: number | null
  usage: TokenUsage | null
  createdAt: string
}

export interface AdvisorProductReference {
  id: EntityId
  listingNo: string | null
  title: string
  seller: string
  quantity: string
  price: string
  warehouse: string
  delivery: string
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
  contextNote: string | null
  messages: MessageView[]
}

// ---- 商品 / 库存 ----

export interface SpecField {
  key: string
  label: string
  type: 'number' | 'string'
  unit?: string
  required?: boolean
}

export interface CategoryNode {
  id: EntityId
  parentId: EntityId
  code: string
  name: string
  level: number
  unit: string
  sortOrder: number
  specSchema: SpecField[]
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
 * 电子库存单。
 *
 * 三个数量都给出，而不只是可用量那一个：只看到 "available" 的卖方，分不清
 * 货是没了，还是仅仅被某个生效中的挂牌占住了。
 */
export interface InventoryNoteView {
  version: number
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
  totalQuantity: string
  availableQuantity: string
  frozenQuantity: string
  unit: string
  status: number
  statusText: string
  createdAt: string
  remark: string | null
}

// ---- 交易 ----

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
  quantity: string
  remainingQuantity: string
  unit: string
  price: string | null
  priceType: 'FIXED' | 'NEGOTIABLE'
  priceText: string
  /** AUTO 摘牌即成交；MANUAL 摘牌后需挂牌方确认，确认前货权不转移。 */
  confirmMode: 'AUTO' | 'MANUAL'
  confirmModeText: string
  warehouseId: EntityId | null
  warehouseName: string
  deliveryMethod: string
  deliveryMethodText: string
  validUntil: string
  status: string
  statusText: string
  /** 当调用方就是该挂牌的发布方时为 true——你不能摘自己的牌。 */
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
  spec: Record<string, unknown>
  quantity: string
  unit: string
  price: string
  amount: string
  amountText: string
  warehouseId: EntityId | null
  warehouseName: string
  deliveryMethodText: string
  status: string
  statusText: string
  /**
   * 从本调用方这一侧看，该谁走下一步——即同一个订单上的 "待我签署" 相对
   * 于 "等对方签署"。当没有任何一方有待办时为空。
   */
  statusHint: string | null
  /** 当下一步该由本调用方走时为 true。 */
  statusHintMine: boolean
  /**
   * 执行下一步动作的按钮文案，不属于本调用方时为空。它与 statusHint 有别
   * ——后者描述一种处境（"待我发货"），前者执行一个动作（"确认发货"）。
   */
  nextAction: string | null
  /** 本调用方实际可以执行的流转——与服务端强制执行的是同一张表。 */
  allowedActions: string[]
  /** 挂牌方的答复截止时间；只有「待挂牌方确认」的订单有值。 */
  confirmDeadline: string | null
  confirmedAt: string | null
  cancelledAt: string | null
  cancelReason: string | null
  createdAt: string
}

/**
 * 当前企业需要着手处理的一件事。
 *
 * `kind` 决定这一行显示哪些按钮——一份待确认的摘牌需要确认/拒绝这一对，
 * 一份草稿只需要单个 "起草"——而 `action` 是已经写好的文案。两者都留着，
 * 文案和行为就无法各自漂移。后端一次性从各个模块把这些汇总起来，所以控制台
 * 和 AI 顾问对「什么在待办」不会产生分歧。
 */
export interface TaskView {
  kind:
    | 'ACCEPTANCE_PENDING'
    | 'CONTRACT_TO_SIGN'
    | 'CONTRACT_TO_DRAFT'
    | 'DELIVERY_TO_START'
    | 'DELIVERY_TO_COMPLETE'
  action: string
  targetType: 'ORDER' | 'CONTRACT'
  targetId: EntityId
  targetNo: string
  commodityName: string
  counterparty: string
  quantity: number | null
  unit: string | null
  amount: number | null
  detail: string
  /** 用户动作慢也不会有什么失效时为空。 */
  deadline: string | null
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

// ---- 行情数据 ----

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
 * 行情序列上的一个点。
 *
 * 每个均值都刻意配上 `tradeCount`：在清淡的即期市场上，一笔成交算出的均值和
 * 四十笔成交算出的均值在图上长得一模一样，而这个差别恰恰就是「这个数字能信
 * 几分」的全部问题所在。没有成交的那些天 `value` 为空，于是折线会断开，而不是
 * 直直地穿过一个空缺画过去。
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
  /** 'line' 或 'bar'——由后端决定，因为它知道数据的形状。 */
  kind: string
  points: SeriesPoint[]
}

export interface AdvisorStatus {
  available: boolean
  enabled: boolean
  configured: boolean
  model: string
  provider: string
  framework: string
  registeredTools: string[]
}
