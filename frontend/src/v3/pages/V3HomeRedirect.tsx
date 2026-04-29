import { useEffect, useState } from 'react';
import { Navigate } from 'react-router-dom';
import { getProjects } from '@/lib/api';
import { useAuthStore } from '@/stores/useAuthStore';

/**
 * /v3/home — silent dispatcher.
 *
 * Logged-out → /login (preserving any `?spec=…` from the marketing
 *              site's hero composer handoff so LoginPage can stash
 *              it to sessionStorage for post-auth hydration).
 * Logged-in with ≥1 project → /v3/p/{firstProject} (chat thread).
 * Logged-in with 0 projects → /v3/p/empty (empty-state composer).
 *
 * R-341 (2026-04-29): anon target moved off /v3/landing — the
 * static marketing site at executespec.ai is now the canonical
 * landing.  V3Landing inside the SPA is a Navigate redirect to
 * root (kept around for any deep-links that still point at it).
 */
export default function V3HomeRedirect() {
  const isAuthed = useAuthStore((s) => s.isAuthenticated);
  const [target, setTarget] = useState<string | null>(null);

  useEffect(() => {
    if (!isAuthed) {
      // R-341 — preserve the URL query string so a marketing-site
      // handoff like /v3/home?spec=… still threads the spec through
      // to LoginPage.  In practice the bare-root → /v3/home leg
      // is rare (ProtectedRoute usually catches it first), but this
      // keeps the handoff robust against any direct deep-link.
      const search = typeof window !== 'undefined' ? window.location.search : '';
      setTarget(`/login${search}`);
      return;
    }
    let cancelled = false;
    getProjects()
      .then((list) => {
        if (cancelled) return;
        if (list && list.length > 0) {
          setTarget(`/v3/p/${list[0].id}`);
        } else {
          setTarget('/v3/p/empty');
        }
      })
      .catch(() => {
        if (!cancelled) setTarget('/v3/p/empty');
      });
    return () => {
      cancelled = true;
    };
  }, [isAuthed]);

  if (target) return <Navigate to={target} replace />;

  return (
    <div style={{ padding: 48, textAlign: 'center', color: 'var(--v3-text-muted)', fontSize: 12 }}>
      Loading…
    </div>
  );
}
