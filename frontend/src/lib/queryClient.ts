import { QueryClient } from '@tanstack/react-query'

export const queryClient = new QueryClient({
  defaultOptions: {
    queries: { retry: 1, refetchOnWindowFocus: true, staleTime: 5_000 },
  },
})

const pending = new Set<AbortController>()

export function registerRequest(controller: AbortController) {
  pending.add(controller)
  return () => pending.delete(controller)
}

/** 身份变更时同步终止旧请求并删除查询、突变缓存。 */
export function clearSessionWork() {
  for (const controller of pending) controller.abort()
  pending.clear()
  void queryClient.cancelQueries()
  queryClient.clear()
}
