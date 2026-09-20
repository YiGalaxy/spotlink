import { useEffect } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { notification } from 'antd'
import { useAuthStore } from '@/store/auth'

/**
 * 对手方有动作时，保持任务列表最新。
 *
 * <p><b>事件本身不携带任何数据，而这正是关键所在。</b>服务端只说了「你的待办
 * 变了」这一件事，接下来由这个 hook 重新拉取。若把任务本身发过来，就意味着
 * 服务端要在第二个地方再次判断什么算待办，而界面和 AI 顾问迟早会对此产生分歧。
 *
 * <p>为什么用流而不是轮询：被等待的这件事是有期限的。挂牌方没注意到的摘牌，
 * 就是一份终将过期的摘牌，而当初引到这里来的全部抱怨，正是卖方发现成交发现得
 * 太晚。轮询会把这段延迟变成一种设计选择；用流则把它变成一个网络往返。
 *
 * <p>token 放在查询串里，因为 `EventSource` 无法设置请求头——这与行情推送是
 * 同一个取舍，也用同一种兜底：服务端只在一份具名的流路径清单上接受查询串里的
 * token，其余地方一律不接受。
 */
export function useTaskStream() {
  const accessToken = useAuthStore((state) => state.accessToken)
  const queryClient = useQueryClient()

  useEffect(() => {
    if (!accessToken) return

    const source = new EventSource(`/api/tasks/stream?token=${encodeURIComponent(accessToken)}`)

    source.addEventListener('tasks', (event) => {
      void queryClient.invalidateQueries({ queryKey: ['tasks'] })

      // 凡是能改变任务列表的东西，同样能改变任务列表派生自的那些列表，
      // 所以这些也一并刷新，而不是等用户自己跳转过去。
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
      // EventSource 会自行重试；任务 query 上的轮询间隔，是为那些撑不住长连接
      // 的服务器准备的兜底。
    }

    return () => source.close()
  }, [accessToken, queryClient])
}

/**
 * 从事件中读出原因，并容忍任何意外情况。
 *
 * <p>一个格式错乱的帧最多该损失一条提示，而不该损失整个通知。上面那次重新
 * 拉取才是要紧的部分；这条消息只是顺带的人情。
 */
function readReason(event: Event): string | null {
  try {
    const payload = JSON.parse((event as MessageEvent).data) as { reason?: string }
    return payload.reason ?? null
  } catch {
    return null
  }
}
