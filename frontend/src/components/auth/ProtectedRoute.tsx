import { useEffect } from 'react';
import { Navigate, Outlet, useLocation } from 'react-router-dom';
import { useAuthStore } from '@/stores/useAuthStore';

/**
 * Wraps protected routes. If not authenticated, redirects to /login.
 * When used as a route element wrapper, renders <Outlet /> for nested routes.
 */
export default function ProtectedRoute() {
  const { isAuthenticated, isLoading, loadMe, user } = useAuthStore();
  const location = useLocation();

  useEffect(() => {
    if (!user && !isLoading) {
      loadMe();
    }
  }, []);

  if (isLoading) {
    return (
      <div className="flex h-screen items-center justify-center bg-[#0f1117]">
        <div className="h-8 w-8 animate-spin rounded-full border-4 border-purple-500 border-t-transparent" />
      </div>
    );
  }

  if (!isAuthenticated) {
    return <Navigate to="/login" state={{ from: location }} replace />;
  }

  return <Outlet />;
}
