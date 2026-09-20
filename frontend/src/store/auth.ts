import { create } from 'zustand'
import { persist } from 'zustand/middleware'
import type { LoginResponse, UserProfile } from '@/types/api'

interface AuthState {
  accessToken: string | null
  refreshToken: string | null
  user: UserProfile | null
  login: (payload: LoginResponse) => void
  setUser: (user: UserProfile) => void
  clear: () => void
  isAuthenticated: () => boolean
}

/**
 * 会话状态。
 *
 * <p>持久化到 localStorage，这样刷新页面不会把用户登出。axios 请求拦截器
 * 就是从这里读取 token 的。
 *
 * <p>写给在意安全的读者：localStorage 可被页面上任意脚本读取，因此一个 XSS
 * 漏洞就等于 token 失窃。生产环境的加固方案是把 refresh token 放进 HttpOnly
 * cookie，并在内存中保留一个短时效的 access token。这个取舍记录在 docs/adr
 * 中，而不是被悄悄做掉。
 */
export const useAuthStore = create<AuthState>()(
  persist(
    (set, get) => ({
      accessToken: null,
      refreshToken: null,
      user: null,

      login: (payload) =>
        set({
          accessToken: payload.accessToken,
          refreshToken: payload.refreshToken,
          user: payload.user,
        }),

      setUser: (user) => set({ user }),

      clear: () => set({ accessToken: null, refreshToken: null, user: null }),

      isAuthenticated: () => Boolean(get().accessToken),
    }),
    {
      name: 'spotlink-auth',
      partialize: (state) => ({
        accessToken: state.accessToken,
        refreshToken: state.refreshToken,
        user: state.user,
      }),
    },
  ),
)
