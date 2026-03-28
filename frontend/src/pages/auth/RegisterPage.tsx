import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useAuthStore } from '@/stores/useAuthStore';

export default function RegisterPage() {
  const [form, setForm] = useState({ email: '', firstName: '', lastName: '', password: '', confirm: '' });
  const [error, setError] = useState('');
  const [success, setSuccess] = useState(false);
  const [loading, setLoading] = useState(false);
  const { register } = useAuthStore();

  const set = (k: keyof typeof form) => (e: React.ChangeEvent<HTMLInputElement>) =>
    setForm(f => ({ ...f, [k]: e.target.value }));

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');
    if (form.password !== form.confirm) { setError('Passwords do not match'); return; }
    if (form.password.length < 8) { setError('Password must be at least 8 characters'); return; }
    if (!/[A-Z]/.test(form.password)) { setError('Password must contain at least one uppercase letter'); return; }
    if (!/[0-9]/.test(form.password)) { setError('Password must contain at least one digit'); return; }
    setLoading(true);
    try {
      await register(form.email, form.firstName, form.lastName, form.password);
      setSuccess(true);
    } catch (err: any) {
      setError(err.message?.includes('409') ? 'This email is already registered.' :
               err.message ?? 'Registration failed');
    } finally {
      setLoading(false);
    }
  };

  if (success) {
    return (
      <div className="flex min-h-screen items-center justify-center bg-[#0f1117] p-4">
        <div className="w-full max-w-md rounded-sm border border-slate-700 bg-[#1a1f2e] p-8 text-center ">
          <div className="mb-4 text-5xl">📧</div>
          <h2 className="mb-2 text-xl font-bold text-white">Check your inbox</h2>
          <p className="text-slate-400">
            We sent a verification link to <strong className="text-white">{form.email}</strong>.
            Click it to activate your account.
          </p>
          <Link to="/login" className="mt-6 inline-block text-purple-400 hover:text-purple-300 text-sm">
            Back to login
          </Link>
        </div>
      </div>
    );
  }

  return (
    <div className="flex min-h-screen items-center justify-center bg-[#0f1117] p-4">
      <div className="w-full max-w-md">
        <div className="mb-8 text-center">
          <h1 className="text-3xl font-bold text-purple-400">executespec</h1>
          <p className="mt-2 text-slate-400">Create your free account</p>
        </div>
        <div className="rounded-sm border border-slate-700 bg-[#1a1f2e] p-8 ">
          <h2 className="mb-6 text-xl font-bold text-white">Sign up</h2>
          <form onSubmit={handleSubmit} className="space-y-4">
            <div className="grid grid-cols-2 gap-3">
              <div>
                <label className="mb-1 block text-sm text-slate-400">First name</label>
                <input type="text" value={form.firstName} onChange={set('firstName')} required
                  className="w-full rounded-sm border border-slate-600 bg-slate-800 px-3 py-2 text-white focus:border-purple-500 focus:outline-none" />
              </div>
              <div>
                <label className="mb-1 block text-sm text-slate-400">Last name</label>
                <input type="text" value={form.lastName} onChange={set('lastName')} required
                  className="w-full rounded-sm border border-slate-600 bg-slate-800 px-3 py-2 text-white focus:border-purple-500 focus:outline-none" />
              </div>
            </div>
            <div>
              <label className="mb-1 block text-sm text-slate-400">Email</label>
              <input type="email" value={form.email} onChange={set('email')} required
                className="w-full rounded-sm border border-slate-600 bg-slate-800 px-3 py-2 text-white focus:border-purple-500 focus:outline-none" />
            </div>
            <div>
              <label className="mb-1 block text-sm text-slate-400">Password</label>
              <input type="password" value={form.password} onChange={set('password')} required minLength={8}
                className="w-full rounded-sm border border-slate-600 bg-slate-800 px-3 py-2 text-white focus:border-purple-500 focus:outline-none" />
              <p className="mt-1 text-xs text-slate-500">Min 8 chars, 1 uppercase, 1 digit, no spaces</p>
            </div>
            <div>
              <label className="mb-1 block text-sm text-slate-400">Confirm password</label>
              <input type="password" value={form.confirm} onChange={set('confirm')} required
                className="w-full rounded-sm border border-slate-600 bg-slate-800 px-3 py-2 text-white focus:border-purple-500 focus:outline-none" />
            </div>
            {error && <p className="rounded-sm bg-red-900/30 px-3 py-2 text-sm text-red-400">{error}</p>}
            <button type="submit" disabled={loading}
              className="w-full rounded-sm bg-purple-600 py-2.5 font-semibold text-white hover:bg-purple-500 disabled:opacity-50 transition-colors">
              {loading ? 'Creating account…' : 'Create account'}
            </button>
          </form>
          <p className="mt-4 text-center text-sm text-slate-400">
            Already have an account?{' '}
            <Link to="/login" className="text-purple-400 hover:text-purple-300">Sign in</Link>
          </p>
        </div>
      </div>
    </div>
  );
}
