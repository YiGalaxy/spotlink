import React from 'react'
import ReactDOM from 'react-dom/client'
import { App as AntApp, ConfigProvider } from 'antd'
import zhCN from 'antd/locale/zh_CN'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { RouterProvider } from 'react-router-dom'
import dayjs from 'dayjs'
import 'dayjs/locale/zh-cn'
import { router } from '@/router'
import { registerMessageApi } from '@/utils/notify'
import './index.css'

dayjs.locale('zh-cn')

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: 1,
      // 开启，与通常的建议相反，这里是刻意为之。交易界面是一份共享文档：
      // 你在看另一个标签页时，对手方可能正在改动它。若关闭此项，卖方切回
      // 该标签页会看到一份过期页面，直到他想起手动刷新——这曾是一个被上报
      // 的 bug，而这一行正是它的根因。
      refetchOnWindowFocus: true,
      // 不设全局 refetchInterval。轮询每个已挂载的 query，会让顾问对话记录、
      // 规则语料和企业档案统统进入 30 秒定时器，而这些数据只在用户做了操作
      // 时才会变化。真正需要心跳的那个 query——任务列表，对手方随时可能改动
      // 它——自行声明了间隔；而任务流在能做得更好时会主动推送。
      staleTime: 5_000,
    },
  },
})

/**
 * 把 Ant Design 的 App 上下文接入 axios 层。
 *
 * <p>在渲染期间赋值而不是放在 effect 里，是刻意的：React Query 会在同一次
 * commit 中发出第一个请求，而 effect 会晚于它执行，这会让最早的那几个错误
 * 拿不到 message 实例。
 */
function MessageBridge({ children }: { children: React.ReactNode }) {
  const { message } = AntApp.useApp()
  registerMessageApi(message)
  return <>{children}</>
}

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <ConfigProvider
      locale={zhCN}
      theme={{
        token: {
          colorPrimary: '#1f5eff',
          borderRadius: 6,
        },
      }}
    >
      <AntApp>
        <MessageBridge>
          <QueryClientProvider client={queryClient}>
            <RouterProvider router={router} />
          </QueryClientProvider>
        </MessageBridge>
      </AntApp>
    </ConfigProvider>
  </React.StrictMode>,
)
