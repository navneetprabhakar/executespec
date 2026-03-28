import { useState } from 'react';
import { Link, useNavigate, useLocation } from 'react-router-dom';
import { useAuthStore } from '@/stores/useAuthStore';

export default function LoginPage() {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const { login } = useAuthStore();
  const navigate = useNavigate();
  const location = useLocation();
  const from = (location.state as any)?.from?.pathname ?? '/';

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');
    setLoading(true);
    try {
      await login(email, password);
      navigate(from, { replace: true });
    } catch (err: any) {
      setError(err.message?.includes('403') ? 'Please verify your email before logging in.' :
               err.message?.includes('401') ? 'Invalid email or password.' :
               'Login failed. Please try again.');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="flex min-h-screen items-center justify-center bg-[#0f1117] p-4">
      <div className="w-full max-w-md">
        <div className="mb-8 text-center">
          <h1 className="text-3xl font-bold text-purple-400">executespec</h1>
          <p className="mt-2 text-slate-400">Multi-agent coding system</p>
        </div>
        <div className="rounded-sm border border-slate-700 bg-[#1a1f2e] p-8 ">
          <h2 className="mb-6 text-xl font-bold text-white">Sign in</h2>
          <form onSubmit={handleSubmit} className="space-y-4">
            <div>
              <label className="mb-1 block text-sm text-slate-400">Email</label>
              <input
                type="email"
                value={email}
                onChange={e => setEmail(e.target.value)}
                required autoFocus
                className="w-full rounded-sm border border-slate-600 bg-slate-800 px-3 py-2 text-white focus:border-purple-500 focus:outline-none"
              />
            </div>
            <div>
              <label className="mb-1 block text-sm text-slate-400">Password</label>
              <input
                type="password"
                value={password}
                onChange={e => setPassword(e.target.value)}
                required
                className="w-full rounded-sm border border-slate-600 bg-slate-800 px-3 py-2 text-white focus:border-purple-500 focus:outline-none"
              />
            </div>
            {error && <p className="rounded-sm bg-red-900/30 px-3 py-2 text-sm text-red-400">{error}</p>}
            <button
              type="submit"
              disabled={loading}
              className="w-full rounded-sm bg-purple-600 py-2.5 font-semibold text-white hover:bg-purple-500 disabled:opacity-50 transition-colors"
            >
              {loading ? 'Signing in…' : 'Sign in'}
            </button>
          </form>
          <div className="mt-4 flex flex-col gap-2 text-center text-sm text-slate-400">
            <Link to="/forgot-password" className="text-purple-400 hover:text-purple-300">
              Forgot your password?
            </Link>
            <span>
              Don't have an account?{' '}
              <Link to="/register" className="text-purple-400 hover:text-purple-300">Sign up free</Link>
            </span>
          </div>
        </div>
      </div>
    </div>
  );
}
