import axios, { AxiosError, CanceledError, type AxiosRequestConfig } from 'axios'
import { registerRequest } from '@/lib/queryClient'
import { useAuthStore } from '@/store/auth'
import { notifyError } from '@/utils/notify'
import type { ApiResponse } from '@/types/api'

declare module 'axios' {
  interface AxiosRequestConfig {
    spotlinkSession?: number
    releaseSessionRequest?: () => void
  }
}

function belongsToCurrentSession(config?: AxiosRequestConfig) {
  return config?.spotlinkSession === useAuthStore.getState().sessionId
}

/** 后端返回非零业务码时抛出。 */
export class ApiError extends Error {
  constructor(
    public readonly code: number,
    message: string,
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

const instance = axios.create({
  baseURL: '/api',
  // 顾问请求包含多轮工具调用，超时需长于普通 CRUD。
  timeout: 180_000,
})

instance.interceptors.request.use((config) => {
  if (!belongsToCurrentSession(config)) throw new CanceledError('会话已切换')
  const controller = new AbortController()
  config.signal = controller.signal
  config.releaseSessionRequest = registerRequest(controller)
  const token = useAuthStore.getState().accessToken
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

instance.interceptors.response.use(
  (response) => {
    response.config.releaseSessionRequest?.()
    if (!belongsToCurrentSession(response.config)) throw new CanceledError('会话已切换')
    return response
  },
  (error: AxiosError<ApiResponse<unknown>>) => {
    error.config?.releaseSessionRequest?.()
    if (axios.isCancel(error) || !belongsToCurrentSession(error.config)) {
      return Promise.reject(new CanceledError('请求已取消或会话已切换'))
    }
    const status = error.response?.status

    if (status === 401) {
      // 登录失败使用业务码；401 仅表示现有令牌失效。
      useAuthStore.getState().clear()
      if (!window.location.pathname.startsWith('/login')) {
        notifyError('登录已过期，请重新登录')
        window.location.replace('/login')
      }
    } else if (status === 403) {
      notifyError('没有访问权限')
    } else if (!error.response) {
      notifyError('无法连接后端服务，请确认服务已启动')
    }

    return Promise.reject(error)
  },
)

/** 解包响应并统一处理业务错误。 */
async function request<T>(config: AxiosRequestConfig): Promise<T> {
  const response = await instance.request<ApiResponse<T>>({ ...config, spotlinkSession: useAuthStore.getState().sessionId })
  // 响应拦截器与调用方 continuation 之间也可能发生账号切换。
  if (!belongsToCurrentSession(response.config)) throw new CanceledError('会话已切换')
  const body = response.data

  if (body.code !== 0) {
    notifyError(body.message)
    throw new ApiError(body.code, body.message)
  }
  return body.data
}

export const api = {
  get: <T>(url: string, params?: Record<string, unknown>) =>
    request<T>({ method: 'GET', url, params }),

  post: <T>(url: string, data?: unknown) => request<T>({ method: 'POST', url, data }),

  put: <T>(url: string, data?: unknown) => request<T>({ method: 'PUT', url, data }),

  delete: <T>(url: string) => request<T>({ method: 'DELETE', url }),
}
