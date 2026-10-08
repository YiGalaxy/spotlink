import axios, { AxiosError, type AxiosAdapter, type InternalAxiosRequestConfig } from 'axios'
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'

const mocks = vi.hoisted(() => ({ clear: vi.fn(), notify: vi.fn(), redirect: vi.fn() }))
vi.mock('@/store/auth', () => ({ useAuthStore: { getState: () => ({ accessToken: 'test-token', clear: mocks.clear }) } }))
vi.mock('@/utils/notify', () => ({ notifyError: mocks.notify }))

let adapter: AxiosAdapter
let client: typeof import('./client')
let lastRequest: InternalAxiosRequestConfig

beforeAll(async () => {
  axios.defaults.adapter = (config) => {
    lastRequest = config
    return adapter(config)
  }
  client = await import('./client')
})

beforeEach(() => {
  vi.clearAllMocks()
  vi.stubGlobal('window', { location: { pathname: '/inventory', replace: mocks.redirect } })
  adapter = async (config) => ({ status: 200, statusText: 'OK', headers: {}, config, data: { code: 0, data: { id: '2101635756223557634' } } })
})

describe('接口客户端契约', () => {
  it('解包成功响应，保持大 ID 字符串并附带访问令牌', async () => {
    expect(await client.api.get('/inventory')).toEqual({ id: '2101635756223557634' })
    expect(lastRequest.headers.Authorization).toBe('Bearer test-token')
    expect(lastRequest.baseURL).toBe('/api')
  })

  it('HTTP 200 下的业务错误也必须拒绝', async () => {
    adapter = async (config) => ({ status: 200, statusText: 'OK', headers: {}, config, data: { code: 20000, message: '数量不足' } })
    await expect(client.api.post('/orders', {})).rejects.toMatchObject({ name: 'ApiError', code: 20000, message: '数量不足' })
    expect(mocks.notify).toHaveBeenCalledWith('数量不足')
  })

  it('过期令牌清空身份并跳转登录', async () => {
    adapter = async (config) => { throw new AxiosError('expired', 'ERR_BAD_REQUEST', config, undefined, { status: 401, statusText: 'Unauthorized', headers: {}, config, data: {} }) }
    await expect(client.api.get('/inventory')).rejects.toBeInstanceOf(AxiosError)
    expect(mocks.clear).toHaveBeenCalledOnce()
    expect(mocks.redirect).toHaveBeenCalledWith('/login')
  })

  it('无权限保留身份，展示权限错误', async () => {
    adapter = async (config) => { throw new AxiosError('forbidden', 'ERR_BAD_REQUEST', config, undefined, { status: 403, statusText: 'Forbidden', headers: {}, config, data: {} }) }
    await expect(client.api.get('/admin')).rejects.toBeInstanceOf(AxiosError)
    expect(mocks.clear).not.toHaveBeenCalled()
    expect(mocks.notify).toHaveBeenCalledWith('没有访问权限')
  })

  it('网络中断显示服务连接错误，不伪造成功结果', async () => {
    adapter = async (config) => { throw new AxiosError('offline', 'ERR_NETWORK', config) }
    await expect(client.api.get('/inventory')).rejects.toBeInstanceOf(AxiosError)
    expect(mocks.notify).toHaveBeenCalledWith('无法连接后端服务，请确认服务已启动')
  })
})
