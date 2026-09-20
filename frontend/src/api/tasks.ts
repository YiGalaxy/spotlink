import { api } from './client'
import type { TaskView } from '@/types/api'

/**
 * What this enterprise currently has to act on.
 *
 * <p>Gathered server-side from every module at once, so the screen and the AI
 * advisor cannot disagree about what is pending. The client does not filter or
 * combine anything — it renders what it is given.
 */
export function fetchTasks() {
  return api.get<TaskView[]>('/tasks')
}

export function fetchTaskStreamStats() {
  return api.get<{ connections: number }>('/tasks/stream/stats')
}
