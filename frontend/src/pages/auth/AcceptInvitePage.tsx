import { useEffect, useRef, useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { useAuthStore } from '@/stores/useAuthStore';
import * as api from '@/lib/api';

export default function AcceptInvitePage() {
  const [searchParams] = useSearchParams();
  const token = searchParams.get('token') ?? '';
  const { isAuthenticated, loadMe } = useAuthStore();
  const [status, setStatus] = useState<'loading' | 'success' | 'error' | 'needsAuth'>('loading');
  const [error, setError] = useState('');
  const navigate = useNavigate();
  const calledRef = useRef(false);

  useEffect(() => {
    if (!token) { setStatus('error'); setError('Missing invitation token.'); return; }
    // Guard against React StrictMode double-invocation
    if (calledRef.current) return;
    calledRef.current = true;

    api.acceptInvite(token)
      .then(async () => {
        await loadMe();
        setStatus('success');
        setTimeout(() => navigate('/'), 2000);
      })
      .catch((err: any) => {
        if (err.message?.includes('401') || err.message?.includes('403')) {
          setStatus('needsAuth');
        } else {
          setStatus('error');
          setError(err.message ?? 'Failed to accept invitation.');
        }
      });
  }, [token]);

  return (
    <div className="flex min-h-screen items-center justify-center bg-[#0f1117] p-4">
      <div className="w-full max-w-md rounded-sm border border-[var(--border-default)] bg-[var(--gray-200)] p-8 text-center ">
        {status === 'loading' && (
          <>
            <div className="mb-4 flex justify-center">
              <div className="h-10 w-10 animate-spin rounded-full border-4 border-purple-500 border-t-transparent" />
            </div>
            <p className="text-muted">Accepting invitation…</p>
          </>
        )}
        {status === 'success' && (
          <>
            <div className="mb-4 text-5xl">🎉</div>
            <h2 className="mb-2 text-xl font-bold text-primary">Welcome to the team!</h2>
            <p className="text-muted">You've joined the org. Redirecting…</p>
          </>
        )}
        {status === 'needsAuth' && (
          <>
            <div className="mb-4 text-4xl">🔐</div>
            <h2 className="mb-2 text-xl font-bold text-primary">Sign in to accept</h2>
            <p className="mb-6 text-muted">You need to be logged in to accept this invitation.</p>
            <Link to={`/login?invite=${token}`}
              className="inline-block rounded-sm bg-accent-blue px-6 py-2.5 font-semibold text-primary hover:bg-accent-blue">
              Sign in
            </Link>
            <p className="mt-3 text-sm text-muted">
              No account?{' '}
              <Link to={`/register?invite=${token}`} className="text-[var(--text-secondary)] hover:text-accent-blue">
                Register
              </Link>
            </p>
          </>
        )}
        {status === 'error' && (
          <>
            <div className="mb-4 text-5xl">❌</div>
            <h2 className="mb-2 text-xl font-bold text-primary">Invitation error</h2>
            <p className="mb-4 text-muted">{error}</p>
            <Link to="/" className="text-[var(--text-secondary)] hover:text-accent-blue text-sm">Go home</Link>
          </>
        )}
      </div>
    </div>
  );
}
