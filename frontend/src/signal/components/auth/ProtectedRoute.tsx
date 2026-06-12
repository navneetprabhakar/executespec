import {useEffect} from 'react';
import {Navigate, Outlet, useLocation} from 'react-router-dom';
import {useAuthStore} from '@/stores/useAuthStore';

/**
 * Wraps protected routes. If not authenticated, redirects to /login.
 *
 * R-341 (2026-04-29): the bare-root `/` for anon used to redirect to
 * `/` — the in-app conversion-optimized landing.  Now that
 * executespec.ai (the static marketing site) is the canonical public
 * landing surface, the bare `/` redirects to `/login` with any URL
 * query string preserved.  The website's hero composer hands off via
 * `dev.executespec.ai/?spec=<encoded>`; LoginPage reads `?spec=` on
 * mount and stashes it to sessionStorage so the existing post-auth
 * claim flow (AuthPage.claimAndForward) hydrates the composer with the
 * typed spec.
 *
 * Auth-walled routes like `/dashboard`, `/projects`, `/audit` still
 * bounce to `/login` + carry the `from` location so the user can come
 * back after signing in.
 *
 * When used as a route element wrapper, renders <Outlet /> for nested routes.
 *
 * Moved from `frontend/src/components/auth/ProtectedRoute.tsx` to live
 * alongside the rest of the Signal chrome (2026-05-16, after the v2/v3/v4
 * directories were retired).
 */
export default function ProtectedRoute() {
  const {isAuthenticated, isLoading, loadMe, user} = useAuthStore();
  const location = useLocation();

  useEffect(() => {
    if (!user && !isLoading) {
      loadMe();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  if (isLoading) {
    return (
      <div className='flex h-screen items-center justify-center bg-[#0f1117]'>
        <div className='h-8 w-8 animate-spin rounded-full border-4 border-purple-500 border-t-transparent' />
      </div>
    );
  }

  if (!isAuthenticated) {
    if (location.pathname === '/') {
      // R-341: marketing-site funnel handoff.  Preserve the search
      // string (e.g. `?spec=Add%20OAuth`) so LoginPage can stash the
      // user's typed spec for post-auth hydration.
      return <Navigate to={`/login${location.search}`} replace />;
    }
    return <Navigate to='/login' state={{from: location}} replace />;
  }

  return <Outlet />;
}
