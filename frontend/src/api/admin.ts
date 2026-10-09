import { api } from './client'
import type { EntityId } from '@/types/api'

export interface AdminEnterprise {
  id: EntityId; enterpriseCode: string; name: string; unifiedSocialCreditCode: string
  legalPerson: string | null; contactName: string | null; contactPhone: string | null
  province: string | null; city: string | null; address: string | null
  traderCode: string | null; status: number; statusText: string; rejectReason: string | null
}
export interface AdminUser {
  id: EntityId; username: string; realName: string | null; userTypeText: string
  status: number; statusText: string; enterpriseId: EntityId | null; enterpriseName: string | null; roles: string[]
}
export interface AdminRole { id: EntityId; code: string; name: string; description: string; permissionCodes: string[] }
export interface AdminOrder { id: EntityId; orderNo: string; buyerName: string; sellerName: string; commodityName: string; amountText: string; statusText: string }
export interface AdminAudit { id: EntityId; username: string; module: string; action: string; targetId: EntityId | null; beforeData: string; afterData: string; success: boolean; createdAt: string }
export interface AdminOverview { enterpriseCount: number; pendingEnterpriseCount: number; frozenEnterpriseCount: number; userCount: number; orderCount: number; activeOrderCount: number; openListingCount: number; tradedAmountText: string }

export const fetchEnterprises = (status?: number, keyword?: string) => api.get<AdminEnterprise[]>('/admin/enterprises', { status, keyword })
export const reviewEnterprise = (id: EntityId, action: string, reason?: string) => api.post<AdminEnterprise>(`/admin/enterprises/${id}/${action}`, { reason })
export const fetchUsers = (keyword?: string, status?: number) => api.get<AdminUser[]>('/admin/users', { keyword, status })
export const fetchRoles = () => api.get<AdminRole[]>('/admin/roles')
export const changeUserStatus = (id: EntityId, status: number, reason: string) => api.post<AdminUser>(`/admin/users/${id}/status`, { status, reason })
export const assignUserRoles = (id: EntityId, roleIds: EntityId[]) => api.post<AdminUser>(`/admin/users/${id}/roles`, { roleIds })
export const fetchAdminOrders = (orderNo?: string) => api.get<AdminOrder[]>('/admin/orders', { orderNo, limit: 100 })
export const fetchAdminAudit = (username?: string) => api.get<AdminAudit[]>('/admin/audit-logs', { username, limit: 100 })
export const fetchAdminOverview = () => api.get<AdminOverview>('/admin/overview')
