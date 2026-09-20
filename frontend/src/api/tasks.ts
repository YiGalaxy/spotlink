import { api } from './client'
import type { TaskView } from '@/types/api'

/**
 * 本企业当前需要着手处理的事项。
 *
 * <p>由服务端一次性从各个模块汇总而来，这样界面和 AI 顾问对「哪些事项在
 * 待办」就不会产生分歧。客户端不做任何筛选或合并——给它什么就渲染什么。
 */
export function fetchTasks() {
  return api.get<TaskView[]>('/tasks')
}

export function fetchTaskStreamStats() {
  return api.get<{ connections: number }>('/tasks/stream/stats')
}
