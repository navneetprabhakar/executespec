import { useState } from 'react';
import { Link, useNavigate, useLocation, useSearchParams } from 'react-router-dom';
import { Eye, EyeOff } from 'lucide-react';
import { useAuthStore } from '@/stores/useAuthStore';

const OAUTH_ERROR_MESSAGES: Record<string, string> = {
  oauth_email_conflict: 'This email is already registered with a different login method.',
  oauth_failed: 'OAuth login failed. Please try again.',
  oauth_no_email: 'Could not retrieve your email from the provider. Please try another method.',
};

export default function LoginPage() {
  const [searchParams] = useSearchParams();
  const oauthError = searchParams.get('error');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [error, setError] = useState(oauthError ? (OAUTH_ERROR_MESSAGES[oauthError] ?? 'Login failed.') : '');
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
          <h1 className="text-3xl font-bold text-[var(--text-secondary)]">executespec</h1>
          <p className="mt-2 text-muted">Multi-agent coding system</p>
        </div>
        <div className="rounded-sm border border-[var(--border-default)] bg-[var(--gray-200)] p-8 ">
          <h2 className="mb-6 text-xl font-bold text-primary">Sign in</h2>

          {/* OAuth buttons */}
          <div className="mb-6 space-y-3">
            <a
              href="/api/auth/oauth2/authorize/google"
              className="flex w-full items-center justify-center gap-3 rounded-sm border border-[var(--border-default)] bg-[#24292f] px-4 py-2.5 font-medium text-primary transition-colors hover:bg-[#30363d]"
            >
              <svg className="h-5 w-5" viewBox="0 0 24 24">
                <path d="M22.56 12.25c0-.78-.07-1.53-.2-2.25H12v4.26h5.92a5.06 5.06 0 0 1-2.2 3.32v2.77h3.57c2.08-1.92 3.28-4.74 3.28-8.1z" fill="#4285F4"/>
                <path d="M12 23c2.97 0 5.46-.98 7.28-2.66l-3.57-2.77c-.98.66-2.23 1.06-3.71 1.06-2.86 0-5.29-1.93-6.16-4.53H2.18v2.84C3.99 20.53 7.7 23 12 23z" fill="#34A853"/>
                <path d="M5.84 14.09c-.22-.66-.35-1.36-.35-2.09s.13-1.43.35-2.09V7.07H2.18C1.43 8.55 1 10.22 1 12s.43 3.45 1.18 4.93l2.85-2.22.81-.62z" fill="#FBBC05"/>
                <path d="M12 5.38c1.62 0 3.06.56 4.21 1.64l3.15-3.15C17.45 2.09 14.97 1 12 1 7.7 1 3.99 3.47 2.18 7.07l3.66 2.84c.87-2.6 3.3-4.53 6.16-4.53z" fill="#EA4335"/>
              </svg>
              Continue with Google
            </a>
            <a
              href="/api/auth/oauth2/authorize/github"
              className="flex w-full items-center justify-center gap-3 rounded-sm border border-[var(--border-default)] bg-[#24292f] px-4 py-2.5 font-medium text-primary transition-colors hover:bg-[#30363d]"
            >
              <svg className="h-5 w-5" fill="currentColor" viewBox="0 0 24 24">
                <path d="M12 2C6.477 2 2 6.484 2 12.017c0 4.425 2.865 8.18 6.839 9.504.5.092.682-.217.682-.483 0-.237-.008-.868-.013-1.703-2.782.605-3.369-1.343-3.369-1.343-.454-1.158-1.11-1.466-1.11-1.466-.908-.62.069-.608.069-.608 1.003.07 1.531 1.032 1.531 1.032.892 1.53 2.341 1.088 2.91.832.092-.647.35-1.088.636-1.338-2.22-.253-4.555-1.113-4.555-4.951 0-1.093.39-1.988 1.029-2.688-.103-.253-.446-1.272.098-2.65 0 0 .84-.27 2.75 1.026A9.564 9.564 0 0 1 12 6.844a9.59 9.59 0 0 1 2.504.337c1.909-1.296 2.747-1.027 2.747-1.027.546 1.379.202 2.398.1 2.651.64.7 1.028 1.595 1.028 2.688 0 3.848-2.339 4.695-4.566 4.943.359.309.678.92.678 1.855 0 1.338-.012 2.419-.012 2.747 0 .268.18.58.688.482A10.02 10.02 0 0 0 22 12.017C22 6.484 17.522 2 12 2z"/>
              </svg>
              Continue with GitHub
            </a>
          </div>

          {/* Divider */}
          <div className="relative mb-6">
            <div className="absolute inset-0 flex items-center">
              <div className="w-full border-t border-[var(--border-default)]" />
            </div>
            <div className="relative flex justify-center text-sm">
              <span className="bg-[var(--gray-200)] px-3 text-muted">or continue with email</span>
            </div>
          </div>

          <form onSubmit={handleSubmit} className="space-y-4">
            <div>
              <label className="mb-1 block text-sm text-muted">Email</label>
              <input
                type="email"
                value={email}
                onChange={e => setEmail(e.target.value)}
                required autoFocus
                className="w-full rounded-sm border border-[var(--border-default)] bg-[var(--gray-300)] px-3 py-2 text-primary focus:border-accent-blue focus:outline-none"
              />
            </div>
            <div>
              <label className="mb-1 block text-sm text-muted">Password</label>
              <div className="relative">
                <input
                  type={showPassword ? 'text' : 'password'}
                  value={password}
                  onChange={e => setPassword(e.target.value)}
                  required
                  className="w-full rounded-sm border border-[var(--border-default)] bg-[var(--gray-300)] px-3 py-2 pr-10 text-primary focus:border-accent-blue focus:outline-none"
                />
                <button
                  type="button"
                  onClick={() => setShowPassword(!showPassword)}
                  className="absolute right-2 top-1/2 -translate-y-1/2 text-muted hover:text-primary transition-colors"
                  tabIndex={-1}
                >
                  {showPassword ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
                </button>
              </div>
            </div>
            {error && <p className="rounded-sm bg-red-900/30 px-3 py-2 text-sm text-accent-red">{error}</p>}
            <button
              type="submit"
              disabled={loading}
              className="w-full rounded-sm bg-accent-blue py-2.5 font-semibold text-primary hover:bg-accent-blue disabled:opacity-50 transition-colors"
            >
              {loading ? 'Signing in…' : 'Sign in'}
            </button>
          </form>
          <div className="mt-4 flex flex-col gap-2 text-center text-sm text-muted">
            <Link to="/forgot-password" className="text-[var(--text-secondary)] hover:text-accent-blue">
              Forgot your password?
            </Link>
            <span>
              Don't have an account?{' '}
              <Link to="/register" className="text-[var(--text-secondary)] hover:text-accent-blue">Sign up free</Link>
            </span>
          </div>
        </div>
        <div className="mt-6 flex items-center justify-center gap-3 text-xs text-slate-600">
          <Link to="/terms" className="hover:text-muted transition-colors">Terms</Link>
          <span>&middot;</span>
          <Link to="/privacy" className="hover:text-muted transition-colors">Privacy</Link>
          <span>&middot;</span>
          <Link to="/dpa" className="hover:text-muted transition-colors">DPA</Link>
        </div>
      </div>
    </div>
  );
}
