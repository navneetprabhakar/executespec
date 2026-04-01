import { useState } from 'react';
import { Link } from 'react-router-dom';
import * as api from '@/lib/api';

export default function ForgotPasswordPage() {
  const [email, setEmail] = useState('');
  const [sent, setSent] = useState(false);
  const [loading, setLoading] = useState(false);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setLoading(true);
    try { await api.authForgotPassword(email); } catch { /* ignore — prevent enumeration */ }
    setSent(true);
    setLoading(false);
  };

  return (
    <div className="flex min-h-screen items-center justify-center bg-[#0f1117] p-4">
      <div className="w-full max-w-md">
        <div className="mb-8 text-center">
          <h1 className="text-3xl font-bold text-[var(--text-secondary)]">executespec</h1>
        </div>
        <div className="rounded-sm border border-[var(--border-default)] bg-[var(--gray-200)] p-8 ">
          {sent ? (
            <div className="text-center">
              <div className="mb-4 text-4xl">🔑</div>
              <h2 className="mb-2 text-xl font-bold text-primary">Check your email</h2>
              <p className="text-muted text-sm">
                If an account exists for <strong className="text-primary">{email}</strong>,
                we sent a temporary password. Use it to log in and you'll be prompted to set a new password.
              </p>
              <Link to="/login" className="mt-6 inline-block text-[var(--text-secondary)] hover:text-accent-blue text-sm">
                Back to login
              </Link>
            </div>
          ) : (
            <>
              <h2 className="mb-2 text-xl font-bold text-primary">Reset password</h2>
              <p className="mb-6 text-sm text-muted">Enter your email and we'll send a temporary password.</p>
              <form onSubmit={handleSubmit} className="space-y-4">
                <div>
                  <label className="mb-1 block text-sm text-muted">Email</label>
                  <input type="email" value={email} onChange={e => setEmail(e.target.value)} required autoFocus
                    className="w-full rounded-sm border border-[var(--border-default)] bg-[var(--gray-300)] px-3 py-2 text-primary focus:border-accent-blue focus:outline-none" />
                </div>
                <button type="submit" disabled={loading}
                  className="w-full rounded-sm bg-accent-blue py-2.5 font-semibold text-primary hover:bg-accent-blue disabled:opacity-50 transition-colors">
                  {loading ? 'Sending…' : 'Send temporary password'}
                </button>
              </form>
              <Link to="/login" className="mt-4 block text-center text-sm text-muted hover:text-primary">
                Back to login
              </Link>
            </>
          )}
        </div>
      </div>
    </div>
  );
}
