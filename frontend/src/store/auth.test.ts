import { beforeEach, expect, it, vi } from 'vitest'
import { identityKey, useAuthStore } from './auth'
import { queryClient, registerRequest } from '@/lib/queryClient'
import type { LoginResponse } from '@/types/api'

const account = (id: string, enterprise: string): LoginResponse => ({
  accessToken: `token-${id}`, refreshToken: `refresh-${id}`, expiresInSeconds: 100,
  user: { userId: id, username: id, realName: null, enterpriseId: enterprise,
    enterpriseName: enterprise, traderCode: null, userType: 1, platformOperator: false },
})

beforeEach(() => { useAuthStore.getState().clear() })

it('换账号清空查询与突变缓存、取消旧请求，查询键绑定新企业与会话', () => {
  useAuthStore.getState().login(account('seller', 'enterprise-a'))
  const oldKey = identityKey('inventory')
  queryClient.setQueryData(oldKey, '企业 A 私有库存')
  queryClient.getMutationCache().build(queryClient, { mutationKey: oldKey })
  const controller = new AbortController()
  registerRequest(controller)
  useAuthStore.getState().login(account('buyer', 'enterprise-b'))
  expect(controller.signal.aborted).toBe(true)
  expect(queryClient.getQueryCache().getAll()).toHaveLength(0)
  expect(queryClient.getMutationCache().getAll()).toHaveLength(0)
  expect(identityKey('inventory')).toContain('enterprise-b')
  expect(identityKey('inventory')).not.toEqual(oldKey)
})

it('同账号重新登录仍是独立会话，资料变更企业需要重新登录', () => {
  const data = account('seller', 'enterprise-a')
  useAuthStore.getState().login(data)
  const before = identityKey('inventory')
  useAuthStore.getState().login(data)
  expect(identityKey('inventory')).not.toEqual(before)
  useAuthStore.getState().setUser(account('seller', 'enterprise-b').user)
  expect(useAuthStore.getState().isAuthenticated()).toBe(false)
})

it('查询取消后，忽略 AbortSignal 的旧查询也不能回填缓存', async () => {
  let finish!: (value: string) => void
  const deferred = new Promise<string>((resolve) => { finish = resolve })
  const old = queryClient.fetchQuery({ queryKey: identityKey('late'), queryFn: () => deferred })
  const rejected = expect(old).rejects.toBeDefined()
  useAuthStore.getState().login(account('buyer', 'enterprise-b'))
  finish('旧账号数据')
  await rejected
  await vi.waitFor(() => expect(queryClient.getQueryCache().getAll()).toHaveLength(0))
})
