import { useEffect } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { notification } from 'antd'
import { useAuthStore } from '@/store/auth'

/** 通过 SSE 失效相关查询，保持任务和交易数据及时更新。 */
export function useTaskStream() {
  const accessToken = useAuthStore((state) => state.accessToken)
  const queryClient = useQueryClient()

  useEffect(() => {
    if (!accessToken) return

    const source = new EventSource(`/api/tasks/stream?token=${encodeURIComponent(accessToken)}`)

    source.addEventListener('tasks', (event) => {
      void queryClient.invalidateQueries({ queryKey: ['tasks'] })

      // 任务变化可能同时影响订单、挂牌、库存和行情。
      void queryClient.invalidateQueries({ queryKey: ['my-orders'] })
      void queryClient.invalidateQueries({ queryKey: ['my-listings'] })
      void queryClient.invalidateQueries({ queryKey: ['inventory-notes'] })
      void queryClient.invalidateQueries({ queryKey: ['market-quotes'] })

      const reason = readReason(event)
      if (reason) {
        notification.info({
          message: '有新的待办',
          description: reason,
          placement: 'bottomRight',
          duration: 4,
        })
      }
    })

    source.onerror = () => {
      // EventSource 会自动重试；查询层轮询负责长连接不可用时的兜底。
    }

    return () => source.close()
  }, [accessToken, queryClient])
}

/** 读取事件原因；格式异常时忽略提示。 */
function readReason(event: Event): string | null {
  try {
    const payload = JSON.parse((event as MessageEvent).data) as { reason?: string }
    return payload.reason ?? null
  } catch {
    return null
  }
}
