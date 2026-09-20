import { api } from './client'
import type { PublicStats } from '@/types/api'

/**
 * Platform-wide figures, readable without signing in.
 *
 * <p>Everything on this endpoint describes the venue as a whole. Nothing
 * scoped to one enterprise belongs here — a visitor has no enterprise, so
 * there would be nothing to scope it by.
 */
export function fetchPublicStats() {
  return api.get<PublicStats>('/public/stats')
}
