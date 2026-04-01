import { useEffect, useRef, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import * as api from '@/lib/api';

export default function VerifyEmailPage() {
  const [searchParams] = useSearchParams();
  const token = searchParams.get('token') ?? '';
  const [status, setStatus] = useState<'loading' | 'success' | 'error'>('loading');
  const [error, setError] = useState('');
  const calledRef = useRef(false);

  useEffect(() => {
    if (!token) { setStatus('error'); setError('Missing verification token.'); return; }
    // Guard against React StrictMode double-invocation — only call the API once
    if (calledRef.current) return;
    calledRef.current = true;
    api.authVerifyEmail(token)
      .then(() => setStatus('success'))
      .catch((err: any) => { setStatus('error'); setError(err.message ?? 'Verification failed.'); });
  }, [token]);

  return (
    <div className="flex min-h-screen items-center justify-center bg-[#0f1117] p-4">
      <div className="w-full max-w-md rounded-sm border border-[var(--border-default)] bg-[var(--gray-200)] p-8 text-center ">
        {status === 'loading' && (
          <>
            <div className="mb-4 flex justify-center">
              <div className="h-10 w-10 animate-spin rounded-full border-4 border-purple-500 border-t-transparent" />
            </div>
            <p className="text-muted">Verifying your email…</p>
          </>
        )}
        {status === 'success' && (
          <>
            <div className="mb-4 text-5xl">✅</div>
            <h2 className="mb-2 text-xl font-bold text-primary">Email verified!</h2>
            <p className="mb-6 text-muted">Your account is now active. You can log in.</p>
            <Link to="/login"
              className="inline-block rounded-sm bg-accent-blue px-6 py-2.5 font-semibold text-primary hover:bg-accent-blue">
              Go to login
            </Link>
          </>
        )}
        {status === 'error' && (
          <>
            <div className="mb-4 text-5xl">❌</div>
            <h2 className="mb-2 text-xl font-bold text-primary">Verification failed</h2>
            <p className="mb-4 text-muted">{error}</p>
            <Link to="/login" className="text-[var(--text-secondary)] hover:text-accent-blue text-sm">Back to login</Link>
          </>
        )}
      </div>
    </div>
  );
}
