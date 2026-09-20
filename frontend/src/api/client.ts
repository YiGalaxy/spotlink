import axios, { AxiosError, type AxiosRequestConfig } from 'axios'
import { useAuthStore } from '@/store/auth'
import { notifyError } from '@/utils/notify'
import type { ApiResponse } from '@/types/api'

/** Thrown when the backend answers with a non-zero business code. */
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
  // Advisor replies run a multi-turn tool loop upstream, so they can take far
  // longer than a normal CRUD call.
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
      // A 401 only happens when a token was present and rejected, so clearing
      // the session here is safe — a failed login returns HTTP 200 with a
      // business code instead.
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
 * Unwraps the response envelope and turns a business failure into a rejection.
 *
 * <p>Doing this once here is why no page has to inspect `code` itself.
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
