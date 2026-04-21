import { useEffect, useRef, useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { Loader2, PartyPopper, ShieldAlert, XCircle } from 'lucide-react';
import { useAuthStore } from '@/stores/useAuthStore';
import * as api from '@/lib/api';
import { AuthShell } from './LoginPage';

export default function AcceptInvitePage() {
  const [searchParams] = useSearchParams();
  const token = searchParams.get('token') ?? '';
  const { loadMe } = useAuthStore();
  const [status, setStatus] = useState<'loading' | 'success' | 'error' | 'needsAuth'>('loading');
  const [error, setError] = useState('');
  const navigate = useNavigate();
  const calledRef = useRef(false);

  useEffect(() => {
    if (!token) { setStatus('error'); setError('Missing invitation token.'); return; }
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
    <AuthShell>
      <div className="text-center">
        {status === 'loading' && (
          <>
            <div className="mx-auto mb-5 flex h-14 w-14 items-center justify-center rounded-full bg-[color-mix(in_srgb,var(--accent-blue)_15%,transparent)] border border-[color-mix(in_srgb,var(--accent-blue)_30%,transparent)]">
              <Loader2 className="h-7 w-7 text-accent-blue animate-spin" />
            </div>
            <h2 className="text-2xl font-semibold text-primary mb-2">Accepting invitation\u2026</h2>
            <p className="text-sm text-muted">This will only take a second.</p>
          </>
        )}
        {status === 'success' && (
          <>
            <div className="mx-auto mb-5 flex h-14 w-14 items-center justify-center rounded-full bg-[color-mix(in_srgb,var(--status-success)_15%,transparent)] border border-[color-mix(in_srgb,var(--status-success)_30%,transparent)]">
              <PartyPopper className="h-7 w-7 text-accent-green" />
            </div>
            <h2 className="text-2xl font-semibold text-primary mb-2">Welcome to the team</h2>
            <p className="text-sm text-muted">Redirecting you to your new workspace\u2026</p>
          </>
        )}
        {status === 'needsAuth' && (
          <>
            <div className="mx-auto mb-5 flex h-14 w-14 items-center justify-center rounded-full bg-[color-mix(in_srgb,var(--status-waiting)_15%,transparent)] border border-[color-mix(in_srgb,var(--status-waiting)_30%,transparent)]">
              <ShieldAlert className="h-7 w-7 text-accent-amber" />
            </div>
            <h2 className="text-2xl font-semibold text-primary mb-2">Sign in to accept</h2>
            <p className="text-sm text-muted mb-6">You need to be signed in to accept this invitation.</p>
            <div className="flex flex-col gap-2">
              <Link to={`/login?invite=${token}`}
                className="inline-flex items-center justify-center rounded-md bg-accent-blue px-6 py-2.5 text-sm font-medium text-primary hover:bg-[var(--accent-blue-hover)] transition-colors">
                Sign in
              </Link>
              <Link to={`/register?invite=${token}`}
                className="inline-flex items-center justify-center rounded-md border border-[var(--border-default)] px-6 py-2.5 text-sm font-medium text-secondary hover:text-primary hover:bg-[var(--gray-200)] transition-colors">
                Create account
              </Link>
            </div>
          </>
        )}
        {status === 'error' && (
          <>
            <div className="mx-auto mb-5 flex h-14 w-14 items-center justify-center rounded-full bg-[color-mix(in_srgb,var(--status-error)_15%,transparent)] border border-[color-mix(in_srgb,var(--status-error)_30%,transparent)]">
              <XCircle className="h-7 w-7 text-accent-red" />
            </div>
            <h2 className="text-2xl font-semibold text-primary mb-2">Invitation error</h2>
            <p className="text-sm text-muted mb-6">{error}</p>
            <Link to="/" className="text-sm text-accent-blue hover:underline font-medium">&larr; Go home</Link>
          </>
        )}
      </div>
    </AuthShell>
  );
}
