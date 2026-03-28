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
      <div className="w-full max-w-md rounded-sm border border-slate-700 bg-[#1a1f2e] p-8 text-center ">
        {status === 'loading' && (
          <>
            <div className="mb-4 flex justify-center">
              <div className="h-10 w-10 animate-spin rounded-full border-4 border-purple-500 border-t-transparent" />
            </div>
            <p className="text-slate-400">Verifying your email…</p>
          </>
        )}
        {status === 'success' && (
          <>
            <div className="mb-4 text-5xl">✅</div>
            <h2 className="mb-2 text-xl font-bold text-white">Email verified!</h2>
            <p className="mb-6 text-slate-400">Your account is now active. You can log in.</p>
            <Link to="/login"
              className="inline-block rounded-sm bg-purple-600 px-6 py-2.5 font-semibold text-white hover:bg-purple-500">
              Go to login
            </Link>
          </>
        )}
        {status === 'error' && (
          <>
            <div className="mb-4 text-5xl">❌</div>
            <h2 className="mb-2 text-xl font-bold text-white">Verification failed</h2>
            <p className="mb-4 text-slate-400">{error}</p>
            <Link to="/login" className="text-purple-400 hover:text-purple-300 text-sm">Back to login</Link>
          </>
        )}
      </div>
    </div>
  );
}
