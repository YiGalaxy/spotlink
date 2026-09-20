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
 * Session state.
 *
 * <p>Persisted to localStorage so a page refresh does not log the user out.
 * The token is read from here by the axios request interceptor.
 *
 * <p>Note for the security-minded reader: localStorage is readable by any
 * script on the page, so an XSS bug becomes token theft. The production
 * hardening is a refresh token in an HttpOnly cookie with a short-lived access
 * token in memory. That trade-off is recorded in docs/adr rather than silently
 * taken.
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
