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

/**
 * Routing, split by who can read what.
 *
 * <p><b>The split follows what a page would show, not a wish to protect it.</b>
 * A marketplace, a price chart and a rulebook describe the venue, so a visitor
 * reads them and gets a complete answer. Inventory, orders, contracts and the
 * advisor describe one enterprise — rendered for someone with no enterprise
 * they are not guarded pages, they are empty ones, and an empty page is a worse
 * answer than a login prompt.
 *
 * <p>None of this is a security boundary, and that is the second time this
 * comment exists in this directory on purpose: every route here is enforced
 * server-side too, and a guard that looks like security is one nobody checks.
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
      // The homepage stays public even for signed-in users: a venue's
      // description of itself is not a reward for logging in.
      { index: true, element: <LandingPage /> },
      { path: 'market', element: <MarketPage /> },
      { path: 'trading', element: <TradingPage /> },
      { path: 'knowledge', element: <KnowledgePage /> },

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
