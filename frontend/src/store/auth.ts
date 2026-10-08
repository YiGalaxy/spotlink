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

/** 持久化会话状态；当前 token 存于 localStorage，生产环境应改用 HttpOnly Cookie。 */
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
