import { useState } from 'react';
import { Link } from 'react-router-dom';
import { KeyRound } from 'lucide-react';
import * as api from '@/lib/api';
import { AuthShell } from './LoginPage';

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
    <AuthShell>
      {sent ? (
        <div className="text-center">
          <div className="mx-auto mb-5 flex h-14 w-14 items-center justify-center rounded-full bg-[color-mix(in_srgb,var(--accent-blue)_15%,transparent)] border border-[color-mix(in_srgb,var(--accent-blue)_30%,transparent)]">
            <KeyRound className="h-7 w-7 text-accent-blue" />
          </div>
          <h2 className="text-2xl font-semibold text-primary mb-2">Check your email</h2>
          <p className="text-sm text-muted leading-relaxed">
            If an account exists for <strong className="text-primary">{email}</strong>, we sent a temporary password. Use it to sign in and you'll be prompted to set a new one.
          </p>
          <Link to="/login" className="mt-6 inline-block text-sm text-accent-blue hover:underline font-medium">
            &larr; Back to login
          </Link>
        </div>
      ) : (
        <>
          <div className="mb-6">
            <h2 className="text-2xl font-semibold text-primary">Reset password</h2>
            <p className="mt-1 text-sm text-muted">Enter your email and we'll send a temporary password.</p>
          </div>
          <form onSubmit={handleSubmit} className="space-y-3.5">
            <div>
              <label htmlFor="email" className="mb-1.5 block text-xs font-medium text-secondary uppercase tracking-wider">Email</label>
              <input id="email" type="email" value={email} onChange={e => setEmail(e.target.value)} required autoFocus autoComplete="email"
                className="w-full rounded-md border border-[var(--border-default)] bg-[var(--gray-200)] px-3 py-2.5 text-sm text-primary placeholder-[var(--text-muted)] focus:border-accent-blue focus:outline-none transition-colors"
                placeholder="you@company.com" />
            </div>
            <button type="submit" disabled={loading}
              className="w-full rounded-md bg-accent-blue py-2.5 text-sm font-medium text-primary hover:bg-[var(--accent-blue-hover)] disabled:opacity-50 transition-colors">
              {loading ? 'Sending\u2026' : 'Send temporary password'}
            </button>
          </form>
          <p className="mt-5 text-center text-sm text-muted">
            Remembered it?{' '}
            <Link to="/login" className="text-accent-blue hover:underline font-medium">Sign in</Link>
          </p>
        </>
      )}
    </AuthShell>
  );
}
