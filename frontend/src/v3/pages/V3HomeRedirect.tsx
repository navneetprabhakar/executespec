import { useEffect, useState } from 'react';
import { Navigate } from 'react-router-dom';
import { getProjects } from '@/lib/api';
import { useAuthStore } from '@/stores/useAuthStore';

/**
 * /v3/home — silent dispatcher.
 *
 * Logged-out → /v3/landing (chat-first marketing landing).
 * Logged-in with ≥1 project → /v3/p/{firstProject} (chat thread).
 * Logged-in with 0 projects → /v3/p/empty (empty-state composer).
 *
 * No primitive-demo content here anymore — the real app
 * shape decides on first paint.
 */
export default function V3HomeRedirect() {
  const isAuthed = useAuthStore((s) => s.isAuthenticated);
  const [target, setTarget] = useState<string | null>(null);

  useEffect(() => {
    if (!isAuthed) {
      setTarget('/v3/landing');
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
