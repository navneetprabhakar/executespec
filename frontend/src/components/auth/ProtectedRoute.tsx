import { useEffect } from 'react';
import { Navigate, Outlet, useLocation } from 'react-router-dom';
import { useAuthStore } from '@/stores/useAuthStore';

/**
 * Wraps protected routes. If not authenticated, redirects to /login —
 * EXCEPT for the bare root `/`, which sends anon visitors to the public
 * /v3/landing surface (the conversion-optimized public landing built
 * across Waves 4-8). Sending anon → login at the root defeats the
 * funnel: a visitor who lands on the bare domain has expressed zero
 * intent to "log in", they're discovering the product. Auth-walled
 * routes like `/dashboard`, `/projects`, `/audit` still bounce to
 * /login + carry the `from` location so the user can come back after
 * signing in.
 *
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
    // Concern 1 (2026-04-28) — anon hitting `/` lands on the public
    // marketing surface, not the login form. Every other protected
    // path keeps the prior /login behaviour so the back-after-signin
    // round-trip works.
    if (location.pathname === '/') {
      return <Navigate to="/v3/landing" replace />;
    }
    return <Navigate to="/login" state={{ from: location }} replace />;
  }

  return <Outlet />;
}
