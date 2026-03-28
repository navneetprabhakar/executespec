import { useState } from 'react';
import { useAuthStore } from '@/stores/useAuthStore';
import * as api from '@/lib/api';

export default function ChangePasswordModal() {
  const { user, loadMe } = useAuthStore();
  const [current, setCurrent] = useState('');
  const [newPwd, setNewPwd] = useState('');
  const [confirm, setConfirm] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);

  if (!user?.mustChangePassword) return null;

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');
    if (newPwd !== confirm) { setError('Passwords do not match'); return; }
    if (newPwd.length < 8) { setError('Password must be at least 8 characters'); return; }
    setLoading(true);
    try {
      await api.authChangePassword(user.mustChangePassword ? null : current, newPwd);
      await loadMe();
    } catch (err: any) {
      setError(err.message ?? 'Failed to change password');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-2 md:p-4 bg-black/70 backdrop-blur-sm">
      <div className="w-full max-w-md rounded-sm bg-[#1a1f2e] border border-slate-700 p-8  overflow-y-auto" style={{ maxHeight: '95dvh' }}>
        <h2 className="mb-2 text-xl font-bold text-white">Change your password</h2>
        <p className="mb-6 text-sm text-slate-400">
          Your account requires a password change before you can continue.
        </p>
        <form onSubmit={handleSubmit} className="space-y-4">
          {!user.mustChangePassword && (
            <div>
              <label className="mb-1 block text-sm text-slate-400">Current password</label>
              <input
                type="password"
                value={current}
                onChange={e => setCurrent(e.target.value)}
                className="w-full rounded-sm border border-slate-600 bg-slate-800 px-3 py-2 text-white focus:border-purple-500 focus:outline-none"
              />
            </div>
          )}
          <div>
            <label className="mb-1 block text-sm text-slate-400">New password</label>
            <input
              type="password"
              value={newPwd}
              onChange={e => setNewPwd(e.target.value)}
              required minLength={8}
              className="w-full rounded-sm border border-slate-600 bg-slate-800 px-3 py-2 text-white focus:border-purple-500 focus:outline-none"
            />
            <p className="mt-1 text-xs text-slate-500">Min 8 chars, 1 uppercase, 1 digit, no spaces</p>
          </div>
          <div>
            <label className="mb-1 block text-sm text-slate-400">Confirm new password</label>
            <input
              type="password"
              value={confirm}
              onChange={e => setConfirm(e.target.value)}
              required
              className="w-full rounded-sm border border-slate-600 bg-slate-800 px-3 py-2 text-white focus:border-purple-500 focus:outline-none"
            />
          </div>
          {error && <p className="text-sm text-red-400">{error}</p>}
          <button
            type="submit"
            disabled={loading}
            className="w-full rounded-sm bg-purple-600 py-2.5 font-semibold text-white hover:bg-purple-500 disabled:opacity-50"
          >
            {loading ? 'Saving…' : 'Set new password'}
          </button>
        </form>
      </div>
    </div>
  );
}
