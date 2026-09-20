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
      refetchOnWindowFocus: false,
    },
  },
})

/**
 * Bridges Ant Design's App context into the axios layer.
 *
 * <p>Assigning during render rather than in an effect is deliberate: React
 * Query fires its first request during the same commit, and an effect would
 * run after it, leaving the earliest errors without a message instance.
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
