import { Navigate, useLocation } from 'react-router-dom'
import { useAuthStore } from '@/store/auth'

/**
 * Route guard.
 *
 * <p>This is a convenience, not a security boundary. Everything it protects is
 * also protected server-side; hiding a page here only spares the user a
 * pointless round trip.
 */
export default function RequireAuth({ children }: { children: React.ReactNode }) {
  const accessToken = useAuthStore((state) => state.accessToken)
  const location = useLocation()

  if (!accessToken) {
    return <Navigate to="/login" state={{ from: location.pathname }} replace />
  }
  return <>{children}</>
}
