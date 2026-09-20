import { createBrowserRouter, Navigate } from 'react-router-dom'
import MainLayout from '@/layouts/MainLayout'
import RequireAuth from './RequireAuth'
import LoginPage from '@/pages/LoginPage'
import DashboardPage from '@/pages/DashboardPage'
import AdvisorPage from '@/pages/AdvisorPage'
import EnterprisePage from '@/pages/EnterprisePage'

export const router = createBrowserRouter([
  {
    path: '/login',
    element: <LoginPage />,
  },
  {
    path: '/',
    element: (
      <RequireAuth>
        <MainLayout />
      </RequireAuth>
    ),
    children: [
      { index: true, element: <DashboardPage /> },
      { path: 'advisor', element: <AdvisorPage /> },
      { path: 'enterprise', element: <EnterprisePage /> },
    ],
  },
  {
    path: '*',
    element: <Navigate to="/" replace />,
  },
])
