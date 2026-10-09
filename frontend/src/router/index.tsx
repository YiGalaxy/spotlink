import { createBrowserRouter, Navigate } from 'react-router-dom'
import MainLayout from '@/layouts/MainLayout'
import RequireAuth from './RequireAuth'
import LoginPage from '@/pages/LoginPage'
import LandingPage from '@/pages/LandingPage'
import DashboardPage from '@/pages/DashboardPage'
import AdvisorPage from '@/pages/AdvisorPage'
import EnterprisePage from '@/pages/EnterprisePage'
import InventoryPage from '@/pages/InventoryPage'
import MarketPage from '@/pages/MarketPage'
import TradingPage from '@/pages/TradingPage'
import KnowledgePage from '@/pages/KnowledgePage'
import AdminModelPage from '@/pages/AdminModelPage'
import AdminPage from '@/pages/AdminPage'

/** 路由按公开页面和登录后页面划分；真正的鉴权由服务端执行。 */
export const router = createBrowserRouter([
  {
    path: '/login',
    element: <LoginPage />,
  },
  {
    path: '/',
    element: <MainLayout />,
    children: [
      // 首页对所有用户公开。
      { index: true, element: <LandingPage /> },
      { path: 'market', element: <MarketPage /> },
      { path: 'trading', element: <TradingPage /> },
      { path: 'admin', element: <Navigate to="/admin/overview" replace /> },
      ...['overview', 'enterprises', 'users', 'orders', 'audit'].map(section => ({ path: `admin/${section}`, element: <RequireAuth><AdminPage key={section} /></RequireAuth> })),
      { path: 'admin/model', element: <RequireAuth><AdminModelPage /></RequireAuth> },

      {
        path: 'dashboard',
        element: (
          <RequireAuth>
            <DashboardPage />
          </RequireAuth>
        ),
      },
      {
        path: 'inventory',
        element: (
          <RequireAuth>
            <InventoryPage />
          </RequireAuth>
        ),
      },
      {
        path: 'advisor',
        element: (
          <RequireAuth>
            <AdvisorPage />
          </RequireAuth>
        ),
      },
      {
        path: 'enterprise',
        element: (
          <RequireAuth>
            <EnterprisePage />
          </RequireAuth>
        ),
      },
      {
        path: 'knowledge',
        element: (
          <RequireAuth>
            <KnowledgePage />
          </RequireAuth>
        ),
      },
    ],
  },
  {
    path: '*',
    element: <Navigate to="/" replace />,
  },
])
