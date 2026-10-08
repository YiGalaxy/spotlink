import React from 'react'
import ReactDOM from 'react-dom/client'
import { App as AntApp, ConfigProvider } from 'antd'
import zhCN from 'antd/locale/zh_CN'
import { QueryClientProvider } from '@tanstack/react-query'
import { RouterProvider } from 'react-router-dom'
import dayjs from 'dayjs'
import 'dayjs/locale/zh-cn'
import { router } from '@/router'
import { queryClient } from '@/lib/queryClient'
import { useAuthStore } from '@/store/auth'
import { registerMessageApi } from '@/utils/notify'
import './index.css'
import './styles/marketplace.css'

dayjs.locale('zh-cn')

function SessionBoundary() {
  const sessionId = useAuthStore((state) => state.sessionId)
  return (
    <QueryClientProvider client={queryClient}>
      <RouterProvider key={sessionId} router={router} />
    </QueryClientProvider>
  )
}
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
          colorPrimary: '#ce4314',
          borderRadius: 8,
          colorBgLayout: '#f5f3f0',
          fontFamily:
            "'Segoe UI', 'PingFang SC', 'Microsoft YaHei', sans-serif",
        },
      }}
    >
      <AntApp>
        <MessageBridge>
          <SessionBoundary />
        </MessageBridge>
      </AntApp>
    </ConfigProvider>
  </React.StrictMode>,
)
