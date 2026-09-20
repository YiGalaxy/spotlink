import { api } from './client'
import type {
  ContractView,
  EntityId,
  ListingView,
  OrderStatusLogEntry,
  OrderView,
} from '@/types/api'

export interface PublishListingPayload {
  side: 'SELL' | 'BUY'
  inventoryNoteId?: EntityId
  categoryId: EntityId
  commodityName: string
  brand?: string
  origin?: string
  spec?: Record<string, unknown>
  quantity: number
  unit?: string
  price?: number
  priceType: 'FIXED' | 'NEGOTIABLE'
  /** AUTO（默认）摘牌即成交；MANUAL 摘牌后等挂牌方确认。仅卖方挂牌可用 MANUAL。 */
  confirmMode?: 'AUTO' | 'MANUAL'
  warehouseId?: EntityId
  deliveryMethod?: string
  validUntil: string
  remark?: string
}

export function fetchMarket(categoryId?: EntityId, side?: string, keyword?: string) {
  return api.get<ListingView[]>('/listings/market', {
    ...(categoryId ? { categoryId } : {}),
    ...(side ? { side } : {}),
    ...(keyword ? { keyword } : {}),
  })
}

export function fetchMyListings() {
  return api.get<ListingView[]>('/listings/mine')
}

export function publishListing(payload: PublishListingPayload) {
  return api.post<ListingView>('/listings', payload)
}

export function closeListing(id: EntityId) {
  return api.post<void>(`/listings/${id}/close`)
}

/** 摘牌：接受对方的挂牌，即作出承诺。 */
export function acceptListing(id: EntityId, quantity: number, remark?: string) {
  return api.post<OrderView>(`/listings/${id}/accept`, { quantity, remark })
}

// ---- orders ----

export function fetchMyOrders(status?: string) {
  return api.get<OrderView[]>('/orders', status ? { status } : undefined)
}

export function fetchOrder(id: EntityId) {
  return api.get<OrderView>(`/orders/${id}`)
}

export function fetchOrderHistory(id: EntityId) {
  return api.get<OrderStatusLogEntry[]>(`/orders/${id}/history`)
}

/** 确认成交：仅挂牌方本人可操作，货权在此刻转移。 */
export function confirmOrder(id: EntityId) {
  return api.post<OrderView>(`/orders/${id}/confirm`)
}

/** 拒绝摘牌：仅挂牌方本人可操作，货权尚未转移，挂牌数量原样恢复。 */
export function rejectOrder(id: EntityId, reason?: string) {
  return api.post<OrderView>(`/orders/${id}/reject`, { reason })
}

export function cancelOrder(id: EntityId, reason?: string) {
  return api.post<OrderView>(`/orders/${id}/cancel`, { reason })
}

export function startDelivery(id: EntityId) {
  return api.post<OrderView>(`/orders/${id}/deliver`)
}

export function completeOrder(id: EntityId) {
  return api.post<OrderView>(`/orders/${id}/complete`)
}

// ---- contracts ----

export function fetchOrderContract(orderId: EntityId) {
  return api.get<ContractView>(`/orders/${orderId}/contract`)
}

export function draftContract(orderId: EntityId) {
  return api.post<ContractView>(`/orders/${orderId}/contract`)
}

export function signContract(contractId: EntityId) {
  return api.post<ContractView>(`/contracts/${contractId}/sign`)
}
