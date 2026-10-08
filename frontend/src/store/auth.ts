import { create } from 'zustand'
import { persist } from 'zustand/middleware'
import type { LoginResponse, UserProfile } from '@/types/api'
import { clearSessionWork } from '@/lib/queryClient'

interface AuthState {
  sessionId: number
  accessToken: string | null
  refreshToken: string | null
  user: UserProfile | null
  login: (payload: LoginResponse) => void
  setUser: (user: UserProfile) => void
  clear: () => void
  isAuthenticated: () => boolean
}

/** 持久化会话状态；当前 token 存于 localStorage，生产环境应改用 HttpOnly Cookie。 */
export const useAuthStore = create<AuthState>()(
  persist(
    (set, get) => ({
      sessionId: 0,
      accessToken: null,
      refreshToken: null,
      user: null,

      login: (payload) => {
        clearSessionWork()
        set({
          sessionId: get().sessionId + 1,
          accessToken: payload.accessToken,
          refreshToken: payload.refreshToken,
          user: payload.user,
        })
      },

      setUser: (user) => {
        const previous = get().user
        if (previous && (previous.userId !== user.userId || previous.enterpriseId !== user.enterpriseId
          || previous.userType !== user.userType)) {
          get().clear()
          return
        }
        set({ user })
      },

      clear: () => {
        clearSessionWork()
        set({ sessionId: get().sessionId + 1, accessToken: null, refreshToken: null, user: null })
      },

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

/** 查询与失效操作使用同一身份前缀；会话边界负责身份变化后的组件重建。 */
export function identityKey(...key: readonly unknown[]) {
  const { sessionId, user } = useAuthStore.getState()
  return ['identity', user?.userId ?? 'guest', user?.enterpriseId ?? 'platform', sessionId, ...key]
}
