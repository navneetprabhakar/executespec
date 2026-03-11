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
          <h1 className="text-3xl font-bold text-purple-400">executespec</h1>
        </div>
        <div className="rounded-2xl border border-slate-700 bg-[#1a1f2e] p-8 shadow-2xl">
          {sent ? (
            <div className="text-center">
              <div className="mb-4 text-4xl">🔑</div>
              <h2 className="mb-2 text-xl font-bold text-white">Check your email</h2>
              <p className="text-slate-400 text-sm">
                If an account exists for <strong className="text-white">{email}</strong>,
                we sent a temporary password. Use it to log in and you'll be prompted to set a new password.
              </p>
              <Link to="/login" className="mt-6 inline-block text-purple-400 hover:text-purple-300 text-sm">
                Back to login
              </Link>
            </div>
          ) : (
            <>
              <h2 className="mb-2 text-xl font-bold text-white">Reset password</h2>
              <p className="mb-6 text-sm text-slate-400">Enter your email and we'll send a temporary password.</p>
              <form onSubmit={handleSubmit} className="space-y-4">
                <div>
                  <label className="mb-1 block text-sm text-slate-400">Email</label>
                  <input type="email" value={email} onChange={e => setEmail(e.target.value)} required autoFocus
                    className="w-full rounded-lg border border-slate-600 bg-slate-800 px-3 py-2 text-white focus:border-purple-500 focus:outline-none" />
                </div>
                <button type="submit" disabled={loading}
                  className="w-full rounded-lg bg-purple-600 py-2.5 font-semibold text-white hover:bg-purple-500 disabled:opacity-50 transition-colors">
                  {loading ? 'Sending…' : 'Send temporary password'}
                </button>
              </form>
              <Link to="/login" className="mt-4 block text-center text-sm text-slate-400 hover:text-white">
                Back to login
              </Link>
            </>
          )}
        </div>
      </div>
    </div>
  );
}
