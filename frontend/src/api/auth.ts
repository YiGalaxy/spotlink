import { api } from './client'
import type { LoginResponse, UserProfile } from '@/types/api'

export function login(username: string, password: string) {
  return api.post<LoginResponse>('/auth/login', { username, password })
}

export function fetchCurrentUser() {
  return api.get<UserProfile>('/auth/me')
}
