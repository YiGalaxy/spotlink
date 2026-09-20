import axios, { AxiosError, type AxiosRequestConfig } from 'axios'
import { useAuthStore } from '@/store/auth'
import { notifyError } from '@/utils/notify'
import type { ApiResponse } from '@/types/api'

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
  // 顾问回复在上游要跑一个多轮工具循环，所以耗时可能远超过普通的 CRUD 调用。
  timeout: 180_000,
})

instance.interceptors.request.use((config) => {
  const token = useAuthStore.getState().accessToken
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

instance.interceptors.response.use(
  (response) => response,
  (error: AxiosError<ApiResponse<unknown>>) => {
    const status = error.response?.status

    if (status === 401) {
      // 只有 token 存在且被拒绝时才会出现 401，所以在这里清理会话是安全的
      // ——登录失败返回的是 HTTP 200 加一个业务码，而不是 401。
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

/**
 * 拆开响应信封，并把业务失败转成 rejection。
 *
 * <p>正因为在这里统一做了一次，才没有任何一个页面需要自己去检查 `code`。
 */
async function request<T>(config: AxiosRequestConfig): Promise<T> {
  const response = await instance.request<ApiResponse<T>>(config)
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
