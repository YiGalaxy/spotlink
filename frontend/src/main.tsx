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
      // On, which is the opposite of the usual recommendation, and deliberate
      // here. A trading screen is a shared document: the other party changes it
      // while you are looking at another tab. With this off, a seller returns
      // to the tab and sees a stale page until they think to reload — which was
      // a reported bug, and this line was its root cause.
      refetchOnWindowFocus: true,
      // No global refetchInterval. Polling every mounted query would put the
      // advisor transcript, the rule corpus and the enterprise profile on a
      // 30-second timer for data that only changes when the user does
      // something. The one query that genuinely needs a heartbeat — the task
      // list, which the other party can change at any moment — asks for its own
      // interval, and the task stream pushes when it can do better than that.
      staleTime: 5_000,
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
