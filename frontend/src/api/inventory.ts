import { api } from './client'
import type { CategoryNode, EntityId, InventoryNoteView, WarehouseView } from '@/types/api'

export interface InventoryRegisterPayload {
  categoryId: EntityId
  warehouseId: EntityId
  commodityName: string
  brand?: string
  origin?: string
  spec?: Record<string, unknown>
  quantity: number
  unit?: string
  remark?: string
}

export function listInventoryNotes(status?: number) {
  return api.get<InventoryNoteView[]>('/inventory-notes', status === undefined ? undefined : { status })
}

export function getInventoryNote(id: EntityId) {
  return api.get<InventoryNoteView>(`/inventory-notes/${id}`)
}

export function registerInventory(payload: InventoryRegisterPayload) {
  return api.post<InventoryNoteView>('/inventory-notes', payload)
}

/**
 * Edits descriptive fields only.
 *
 * Quantity, warehouse and unit are absent by design: those are physical facts
 * about goods in a named place, and changing them would make the platform
 * disagree with the warehouse rather than change what is actually stored.
 */
export interface InventoryUpdatePayload {
  categoryId: EntityId
  commodityName: string
  brand?: string
  origin?: string
  spec?: Record<string, unknown>
  remark?: string
}

export function updateInventoryNote(id: EntityId, payload: InventoryUpdatePayload) {
  return api.put<InventoryNoteView>(`/inventory-notes/${id}`, payload)
}

export function cancelInventoryNote(id: EntityId) {
  return api.delete<void>(`/inventory-notes/${id}`)
}

export function fetchCategoryTree() {
  return api.get<CategoryNode[]>('/categories/tree')
}

export function fetchWarehouses() {
  return api.get<WarehouseView[]>('/warehouses')
}
