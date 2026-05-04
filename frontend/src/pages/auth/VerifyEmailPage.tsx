import {useEffect, useRef, useState} from 'react';
import {Link, useNavigate, useSearchParams} from 'react-router-dom';
import {Loader2, CheckCircle2, XCircle} from 'lucide-react';
import * as api from '@/lib/api';
import {AuthShell} from './LoginPage';
import {useAuthStore} from '@/stores/useAuthStore';

export default function VerifyEmailPage() {
  const [searchParams] = useSearchParams();
  const token = searchParams.get('token') ?? '';
  const [status, setStatus] = useState<'loading' | 'success' | 'error'>('loading');
  const [error, setError] = useState('');
  const calledRef = useRef(false);
  const navigate = useNavigate();
  const {loadMe} = useAuthStore();

  useEffect(() => {
    if (!token) {
      setStatus('error');
      setError('Missing verification token.');
      return;
    }
    if (calledRef.current) return;
    calledRef.current = true;
    api
      .authVerifyEmail(token)
      .then(async (resp: any) => {
        // [F-29] (audit Sprint 1 PR-2, 2026-04-30) — auto-session.
        // Backend now returns {accessToken, user} on verify; store the
        // token + load /me, then redirect to /home. HomeRedirectPage picks
        // the right destination based on project count, so the user
        // never has to retype their password after clicking the
        // verification link in their inbox.
        if (resp?.accessToken) {
          localStorage.setItem('access_token', resp.accessToken);
          useAuthStore.setState({accessToken: resp.accessToken, isAuthenticated: true});
          await loadMe().catch(() => {
            /* non-fatal — store has user from verify resp */
          });
        }
        setStatus('success');
        // Brief success flash (1.2s) so the user sees "Email verified"
        // before the dispatcher takes them to their home; then redirect.
        setTimeout(() => navigate('/home', {replace: true}), 1200);
      })
      .catch((err: any) => {
        setStatus('error');
        setError(err.message ?? 'Verification failed.');
      });
  }, [token, navigate, loadMe]);

  return (
    <AuthShell>
      <div className='text-center'>
        {status === 'loading' && (
          <>
            <div className='mx-auto mb-5 flex h-14 w-14 items-center justify-center rounded-full bg-[color-mix(in_srgb,var(--accent-blue)_15%,transparent)] border border-[color-mix(in_srgb,var(--accent-blue)_30%,transparent)]'>
              <Loader2 className='h-7 w-7 text-accent-blue animate-spin' />
            </div>
            <h2 className='text-2xl font-semibold text-primary mb-2'>Verifying your email</h2>
            <p className='text-sm text-muted'>One moment…</p>
          </>
        )}
        {status === 'success' && (
          <>
            <div className='mx-auto mb-5 flex h-14 w-14 items-center justify-center rounded-full bg-[color-mix(in_srgb,var(--status-success)_15%,transparent)] border border-[color-mix(in_srgb,var(--status-success)_30%,transparent)]'>
              <CheckCircle2 className='h-7 w-7 text-accent-green' />
            </div>
            <h2 className='text-2xl font-semibold text-primary mb-2'>Email verified</h2>
            <p className='text-sm text-muted mb-6'>Your account is active. Let's ship something.</p>
            <Link
              to='/login'
              className='inline-flex items-center justify-center rounded-md bg-accent-blue px-6 py-2.5 text-sm font-medium text-primary hover:bg-[var(--accent-blue-hover)] transition-colors'
            >
              Sign in &rarr;
            </Link>
          </>
        )}
        {status === 'error' && (
          <>
            <div className='mx-auto mb-5 flex h-14 w-14 items-center justify-center rounded-full bg-[color-mix(in_srgb,var(--status-error)_15%,transparent)] border border-[color-mix(in_srgb,var(--status-error)_30%,transparent)]'>
              <XCircle className='h-7 w-7 text-accent-red' />
            </div>
            <h2 className='text-2xl font-semibold text-primary mb-2'>Verification failed</h2>
            <p className='text-sm text-muted mb-6'>{error}</p>
            <Link to='/login' className='text-sm text-accent-blue hover:underline font-medium'>
              &larr; Back to login
            </Link>
          </>
        )}
      </div>
    </AuthShell>
  );
}
