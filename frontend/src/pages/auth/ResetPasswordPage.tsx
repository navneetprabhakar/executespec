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
          <h1 className="text-3xl font-bold text-purple-400">executespec</h1>
        </div>
        <div className="rounded-sm border border-slate-700 bg-[#1a1f2e] p-8 ">
          <h2 className="mb-6 text-xl font-bold text-white">Choose new password</h2>
          <form onSubmit={handleSubmit} className="space-y-4">
            <div>
              <label className="mb-1 block text-sm text-slate-400">New password</label>
              <input type="password" value={pwd} onChange={e => setPwd(e.target.value)} required minLength={8}
                className="w-full rounded-sm border border-slate-600 bg-slate-800 px-3 py-2 text-white focus:border-purple-500 focus:outline-none" />
              <p className="mt-1 text-xs text-slate-500">Min 8 chars, 1 uppercase, 1 digit, no spaces</p>
            </div>
            <div>
              <label className="mb-1 block text-sm text-slate-400">Confirm password</label>
              <input type="password" value={confirm} onChange={e => setConfirm(e.target.value)} required
                className="w-full rounded-sm border border-slate-600 bg-slate-800 px-3 py-2 text-white focus:border-purple-500 focus:outline-none" />
            </div>
            {error && <p className="rounded-sm bg-red-900/30 px-3 py-2 text-sm text-red-400">{error}</p>}
            <button type="submit" disabled={loading}
              className="w-full rounded-sm bg-purple-600 py-2.5 font-semibold text-white hover:bg-purple-500 disabled:opacity-50">
              {loading ? 'Resetting…' : 'Reset password'}
            </button>
          </form>
          <Link to="/login" className="mt-4 block text-center text-sm text-slate-400 hover:text-white">
            Back to login
          </Link>
        </div>
      </div>
    </div>
  );
}
