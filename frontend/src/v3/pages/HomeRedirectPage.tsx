import {useEffect, useState} from 'react';
import {Navigate} from 'react-router-dom';
import {getProjects} from '@/lib/api';
import {useAuthStore} from '@/stores/useAuthStore';

/**
 * /home — silent dispatcher.
 *
 * Logged-out → /login (preserving any `?spec=…` from the marketing
 *              site's hero composer handoff so LoginPage can stash
 *              it to sessionStorage for post-auth hydration).
 * Logged-in with ≥1 project → /projects/{firstProject} (chat thread).
 * Logged-in with 0 projects → /projects/empty (empty-state composer).
 *
 * R-341 (2026-04-29): anon target moved off / — the
 * static marketing site at executespec.ai is now the canonical
 * landing.  LandingPage inside the SPA is a Navigate redirect to
 * root (kept around for any deep-links that still point at it).
 */
export default function HomeRedirectPage() {
  const isAuthed = useAuthStore((s) => s.isAuthenticated);
  const [target, setTarget] = useState<string | null>(null);

  useEffect(() => {
    if (!isAuthed) {
      // R-341 — preserve the URL query string so a marketing-site
      // handoff like /home?spec=… still threads the spec through
      // to LoginPage.  In practice the bare-root → /home leg
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
        // R-343 — persona-tagged signup fork.  RegisterPage stashes the
        // user's chosen persona; we honor it on the FIRST dispatch only
        // (clear after read) so subsequent /home visits land on the
        // first project as before.  Enterprise → trust page (procurement
        // path), Team → /team for the invite-teammates step, Personal /
        // unset → first project (or empty-state).
        let persona: string | null = null;
        try {
          persona = sessionStorage.getItem('v3-signup-persona');
          if (persona) sessionStorage.removeItem('v3-signup-persona');
        } catch (_) {
          /* private mode — ignore */
        }

        if (persona === 'enterprise') {
          setTarget('/trust');
          return;
        }
        if (persona === 'team' && (!list || list.length === 0)) {
          // Send Erin to /team for the invite-first onboarding step.
          // If she already has a project, dispatch normally — assume
          // she's a returning user.
          setTarget('/team');
          return;
        }
        if (list && list.length > 0) {
          setTarget(`/projects/${list[0].id}`);
        } else {
          setTarget('/projects/empty');
        }
      })
      .catch(() => {
        if (!cancelled) setTarget('/projects/empty');
      });
    return () => {
      cancelled = true;
    };
  }, [isAuthed]);

  if (target) return <Navigate to={target} replace />;

  return (
    <div style={{padding: 48, textAlign: 'center', color: 'var(--v3-text-muted)', fontSize: 12}}>
      Loading…
    </div>
  );
}
