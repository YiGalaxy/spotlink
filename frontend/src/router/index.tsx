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

/**
 * 路由，按「谁能读到什么」来划分。
 *
 * <p><b>这种划分依据的是页面会展示什么，而不是想不想保护它。</b>市场行情和价格
 * 走势图描述的是交易场所本身，访客读到它们就能得到完整答案。规则手册不在这里了：
 * 它是运营方自己的语料，已移到控制台，而它背后的接口也同时从公开清单中移除——
 * 藏起一个页面却把它的 API 敞着，不是更小的改动，而是自相矛盾的改动。库存、
 * 订单、合同和顾问描述的是某一家企业——把它们渲染给一个没有企业的人看，它们
 * 就不是被守卫的页面，而是空页面，而空页面比一句登录提示更糟。
 *
 * <p>所有这些都不是安全边界，而且这是这段注释在本目录中第二次出现，是故意的：
 * 这里的每一条路由在服务端同样强制执行，而一个看起来像安全的守卫，恰恰是没人
 * 会去检查的守卫。
 */
export const router = createBrowserRouter([
  {
    path: '/login',
    element: <LoginPage />,
  },
  {
    path: '/',
    element: <MainLayout />,
    children: [
      // 首页对已登录用户同样保持公开：交易场所对自身的介绍，不该是登录之后的
      // 奖励。
      { index: true, element: <LandingPage /> },
      { path: 'market', element: <MarketPage /> },
      { path: 'trading', element: <TradingPage /> },

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
    ],
  },
  {
    path: '*',
    element: <Navigate to="/" replace />,
  },
])
