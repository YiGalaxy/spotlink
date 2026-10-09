import type { UserProfile } from '@/types/api'

export const can = (user: UserProfile | null, permission: string) => Boolean(user?.permissions?.includes(permission))
export const adminEntries = [
  { path: '/admin/overview', label: '平台概览', permission: 'admin:overview' },
  { path: '/admin/enterprises', label: '企业审核', permission: 'admin:enterprise' },
  { path: '/admin/users', label: '账号与角色', permission: 'admin:user' },
  { path: '/admin/orders', label: '订单查询', permission: 'admin:order' },
  { path: '/admin/audit', label: '审计日志', permission: 'admin:audit' },
  { path: '/admin/model', label: '模型配置', permission: 'admin:advisor' },
  { path: '/knowledge', label: '知识库管理', permission: 'admin:knowledge' },
]
