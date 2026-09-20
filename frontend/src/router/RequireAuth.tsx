import { Navigate, useLocation } from 'react-router-dom'
import { useAuthStore } from '@/store/auth'

/**
 * 路由守卫。
 *
 * <p>这是便利手段，不是安全边界。它所保护的一切在服务端同样受保护；在这里
 * 把页面藏起来，只是省得用户白跑一趟。
 */
export default function RequireAuth({ children }: { children: React.ReactNode }) {
  const accessToken = useAuthStore((state) => state.accessToken)
  const location = useLocation()

  if (!accessToken) {
    return <Navigate to="/login" state={{ from: location.pathname }} replace />
  }
  return <>{children}</>
}
