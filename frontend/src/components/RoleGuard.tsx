import { Navigate, Outlet, useLocation } from 'react-router-dom';
import { useAuth } from '../auth/AuthProvider';
import { Role } from '../types';

export function RoleGuard({ roles }: { roles: Role[] }) {
  const { session, isAllowed } = useAuth();
  const location = useLocation();
  if (!session) {
    return <Navigate to={`/sign-in?${new URLSearchParams({ next: location.pathname + location.search })}`} replace />;
  }
  if (!isAllowed(roles)) {
    return <Navigate to="/access-denied" replace />;
  }
  return <Outlet />;
}
