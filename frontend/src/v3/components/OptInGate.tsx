import {Navigate, useLocation} from 'react-router-dom';
import type {ReactNode} from 'react';
import {isV3OptedIn, v3Destination} from '../lib/optIn';

/**
 * R-180 — wraps any v2 route's element. When the user has flipped
 * the v3-opt-in flag, render a Navigate to the v3 equivalent;
 * otherwise render the v2 element as if the gate weren't there.
 *
 * Usage in App.tsx:
 *   <Route path="dashboard" element={<OptInGate><DashboardPage /></OptInGate>} />
 *
 * Preserves query string + hash so deep links still work after
 * redirect. If no v3 destination is mapped for the current path,
 * the v2 element renders unchanged (safe-by-default).
 */
export default function OptInGate({children}: {children: ReactNode}) {
  const location = useLocation();
  if (!isV3OptedIn()) return <>{children}</>;
  const dest = v3Destination(location.pathname);
  if (!dest) return <>{children}</>;
  return <Navigate to={dest + location.search + location.hash} replace />;
}
