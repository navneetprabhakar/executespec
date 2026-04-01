import { useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import * as api from '@/lib/api';

export default function ResetPasswordPage() {
  const [searchParams] = useSearchParams();
  const token = searchParams.get('token') ?? '';
  const [pwd, setPwd] = useState('');
  const [confirm, setConfirm] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const navigate = useNavigate();

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');
    if (pwd !== confirm) { setError('Passwords do not match'); return; }
    setLoading(true);
    try {
      await api.authResetPassword(token, pwd);
      navigate('/login', { state: { message: 'Password reset successfully. Please log in.' } });
    } catch (err: any) {
      setError(err.message ?? 'Reset failed. Link may have expired.');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="flex min-h-screen items-center justify-center bg-[#0f1117] p-4">
      <div className="w-full max-w-md">
        <div className="mb-8 text-center">
          <h1 className="text-3xl font-bold text-[var(--text-secondary)]">executespec</h1>
        </div>
        <div className="rounded-sm border border-[var(--border-default)] bg-[var(--gray-200)] p-8 ">
          <h2 className="mb-6 text-xl font-bold text-primary">Choose new password</h2>
          <form onSubmit={handleSubmit} className="space-y-4">
            <div>
              <label className="mb-1 block text-sm text-muted">New password</label>
              <input type="password" value={pwd} onChange={e => setPwd(e.target.value)} required minLength={8}
                className="w-full rounded-sm border border-[var(--border-default)] bg-[var(--gray-300)] px-3 py-2 text-primary focus:border-accent-blue focus:outline-none" />
              <p className="mt-1 text-xs text-muted">Min 8 chars, 1 uppercase, 1 digit, no spaces</p>
            </div>
            <div>
              <label className="mb-1 block text-sm text-muted">Confirm password</label>
              <input type="password" value={confirm} onChange={e => setConfirm(e.target.value)} required
                className="w-full rounded-sm border border-[var(--border-default)] bg-[var(--gray-300)] px-3 py-2 text-primary focus:border-accent-blue focus:outline-none" />
            </div>
            {error && <p className="rounded-sm bg-red-900/30 px-3 py-2 text-sm text-accent-red">{error}</p>}
            <button type="submit" disabled={loading}
              className="w-full rounded-sm bg-accent-blue py-2.5 font-semibold text-primary hover:bg-accent-blue disabled:opacity-50">
              {loading ? 'Resetting…' : 'Reset password'}
            </button>
          </form>
          <Link to="/login" className="mt-4 block text-center text-sm text-muted hover:text-primary">
            Back to login
          </Link>
        </div>
      </div>
    </div>
  );
}
