import { Navigate, useLocation } from 'react-router-dom'
import { useAuthStore } from '@/store/auth'

/** 前端路由守卫，仅改善体验；服务端负责真正鉴权。 */
export default function RequireAuth({ children }: { children: React.ReactNode }) {
  const accessToken = useAuthStore((state) => state.accessToken)
  const location = useLocation()

  if (!accessToken) {
    return <Navigate to="/login" state={{ from: location.pathname + location.search }} replace />
  }
  return <>{children}</>
}
