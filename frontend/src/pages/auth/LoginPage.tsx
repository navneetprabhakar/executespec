import { useState } from 'react';
import { Link, useNavigate, useLocation, useSearchParams } from 'react-router-dom';
import { Eye, EyeOff, ShieldCheck, Zap, GitBranch, CheckCircle2 } from 'lucide-react';
import { useAuthStore } from '@/stores/useAuthStore';

const OAUTH_ERROR_MESSAGES: Record<string, string> = {
  oauth_email_conflict: 'This email is already registered with a different login method.',
  oauth_failed: 'OAuth login failed. Please try again.',
  oauth_no_email: 'Could not retrieve your email from the provider. Please try another method.',
};

/**
 * v2.5.13 UX overhaul — split-screen login.
 * - Desktop: brand story on the left, form on the right (matches how every modern
 *   B2B SaaS login works and gives SEMs/Leads a "this feels enterprise" first impression).
 * - Mobile: form-only, compact. Brand story collapses to a single line above the form.
 * - Same pattern reused by RegisterPage, ForgotPasswordPage, ResetPasswordPage.
 */
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
    <AuthShell>
      <div className="mb-6">
        <h2 className="text-2xl font-semibold text-primary">Welcome back</h2>
        <p className="mt-1 text-sm text-muted">Sign in to ship a spec.</p>
      </div>

      {/* OAuth */}
      <div className="space-y-2.5">
        <OAuthButton href="/api/auth/oauth2/authorize/google" provider="google">
          Continue with Google
        </OAuthButton>
        <OAuthButton href="/api/auth/oauth2/authorize/github" provider="github">
          Continue with GitHub
        </OAuthButton>
      </div>

      <Divider label="or" />

      <form onSubmit={handleSubmit} className="space-y-3.5">
        <Field label="Email" id="email">
          <input
            id="email"
            type="email"
            value={email}
            onChange={e => setEmail(e.target.value)}
            required autoFocus
            autoComplete="email"
            className="w-full rounded-md border border-[var(--border-default)] bg-[var(--gray-200)] px-3 py-2.5 text-sm text-primary placeholder-[var(--text-muted)] focus:border-accent-blue focus:outline-none transition-colors"
            placeholder="you@company.com"
          />
        </Field>
        <Field label="Password" id="password"
               right={<Link to="/forgot-password" className="text-xs text-accent-blue hover:underline">Forgot?</Link>}>
          <div className="relative">
            <input
              id="password"
              type={showPassword ? 'text' : 'password'}
              value={password}
              onChange={e => setPassword(e.target.value)}
              required
              autoComplete="current-password"
              className="w-full rounded-md border border-[var(--border-default)] bg-[var(--gray-200)] px-3 py-2.5 pr-10 text-sm text-primary focus:border-accent-blue focus:outline-none transition-colors"
              placeholder="\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022"
            />
            <button
              type="button"
              onClick={() => setShowPassword(!showPassword)}
              className="absolute right-2 top-1/2 -translate-y-1/2 p-1 text-muted hover:text-primary transition-colors"
              tabIndex={-1}
              aria-label={showPassword ? 'Hide password' : 'Show password'}
            >
              {showPassword ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
            </button>
          </div>
        </Field>
        {error && (
          <p className="rounded-md bg-[color-mix(in_srgb,var(--status-error)_12%,transparent)] border border-[color-mix(in_srgb,var(--status-error)_30%,transparent)] px-3 py-2 text-xs text-accent-red">
            {error}
          </p>
        )}
        <button
          type="submit"
          disabled={loading}
          className="w-full rounded-md bg-accent-blue py-2.5 text-sm font-medium text-primary hover:bg-[var(--accent-blue-hover)] disabled:opacity-50 transition-colors"
        >
          {loading ? 'Signing in\u2026' : 'Sign in'}
        </button>
      </form>

      <p className="mt-5 text-center text-sm text-muted">
        Don't have an account?{' '}
        <Link to="/register" className="text-accent-blue hover:underline font-medium">Sign up free</Link>
      </p>
    </AuthShell>
  );
}

// ── Shared auth layout ─────────────────────────────────────────────────────
/** Split-screen shell used by Login / Register / Forgot / Reset / Verify. */
export function AuthShell({ children }: { children: React.ReactNode }) {
  return (
    <div className="min-h-[100dvh] grid lg:grid-cols-2 bg-[var(--geist-bg)]">
      {/* Left — brand story (hidden on mobile, visible at lg+) */}
      <aside className="hidden lg:flex flex-col justify-between relative overflow-hidden p-12 border-r border-[var(--border-default)]">
        <div
          aria-hidden
          className="absolute inset-0 -z-10 opacity-70"
          style={{
            background:
              'radial-gradient(ellipse 80% 60% at 20% 10%, color-mix(in srgb, var(--accent-blue) 18%, transparent), transparent),' +
              'radial-gradient(ellipse 60% 50% at 80% 90%, color-mix(in srgb, var(--accent-harness) 15%, transparent), transparent)',
          }}
        />
        <Link to="/" className="inline-flex items-center gap-2.5 text-sm font-medium text-primary">
          <span className="flex h-7 w-7 items-center justify-center rounded-md bg-primary text-[var(--geist-bg)] font-mono font-bold text-sm">ES</span>
          ExecuteSpec
        </Link>

        <div className="max-w-md space-y-6">
          <h1 className="text-3xl xl:text-4xl font-semibold text-primary leading-[1.1] tracking-tight">
            Don't prompt. <span className="text-transparent bg-clip-text" style={{ backgroundImage: 'linear-gradient(90deg, #fb923c, #f59e0b)' }}>Specify.</span>
          </h1>
          <p className="text-base text-secondary leading-relaxed">
            The spec-driven coding platform with autonomous CI/CD validation. Write a spec, get tested code you can actually ship.
          </p>
          <ul className="space-y-3 text-sm text-secondary">
            <Feature icon={<Zap className="h-4 w-4 text-accent-blue" />} title="Prototype in minutes">
              Auto-approved plan, fast models, instant Cloud Run preview.
            </Feature>
            <Feature icon={<ShieldCheck className="h-4 w-4 text-accent-blue" />} title="Ship with confidence">
              Syntax, type, lint, and unit-test gates on every task.
            </Feature>
            <Feature icon={<GitBranch className="h-4 w-4 text-accent-blue" />} title="Multi-repo projects">
              Architecture docs, tech-debt panel, project memory.
            </Feature>
          </ul>
        </div>

        <p className="text-xs text-muted">
          &copy; 2026 ExecuteSpec &middot; <Link to="/terms" className="hover:text-secondary">Terms</Link> &middot; <Link to="/privacy" className="hover:text-secondary">Privacy</Link> &middot; <Link to="/dpa" className="hover:text-secondary">DPA</Link>
        </p>
      </aside>

      {/* Right — form */}
      <main className="flex flex-col justify-center p-6 sm:p-10 lg:p-16">
        <div className="mx-auto w-full max-w-sm">
          {/* Mobile-only brand header */}
          <Link to="/" className="lg:hidden mb-8 inline-flex items-center gap-2.5 text-sm font-medium text-primary">
            <span className="flex h-7 w-7 items-center justify-center rounded-md bg-primary text-[var(--geist-bg)] font-mono font-bold text-sm">ES</span>
            ExecuteSpec
          </Link>
          {children}
        </div>
        <p className="lg:hidden mt-12 text-center text-xs text-muted">
          <Link to="/terms" className="hover:text-secondary">Terms</Link> &middot; <Link to="/privacy" className="hover:text-secondary">Privacy</Link> &middot; <Link to="/dpa" className="hover:text-secondary">DPA</Link>
        </p>
      </main>
    </div>
  );
}

function Feature({ icon, title, children }: { icon: React.ReactNode; title: string; children: React.ReactNode }) {
  return (
    <li className="flex gap-3">
      <span className="mt-0.5 flex h-6 w-6 flex-shrink-0 items-center justify-center rounded-md border border-[var(--border-default)] bg-[var(--gray-100)]">
        {icon}
      </span>
      <div>
        <p className="text-primary font-medium">{title}</p>
        <p className="text-xs text-muted">{children}</p>
      </div>
    </li>
  );
}

function OAuthButton({ href, provider, children }: { href: string; provider: 'google' | 'github'; children: React.ReactNode }) {
  return (
    <a
      href={href}
      className="flex w-full items-center justify-center gap-3 rounded-md border border-[var(--border-default)] bg-[var(--gray-100)] px-4 py-2.5 text-sm font-medium text-primary transition-colors hover:bg-[var(--gray-200)] hover:border-[var(--border-hover)]"
    >
      {provider === 'google' ? (
        <svg className="h-4 w-4" viewBox="0 0 24 24" aria-hidden>
          <path d="M22.56 12.25c0-.78-.07-1.53-.2-2.25H12v4.26h5.92a5.06 5.06 0 0 1-2.2 3.32v2.77h3.57c2.08-1.92 3.28-4.74 3.28-8.1z" fill="#4285F4"/>
          <path d="M12 23c2.97 0 5.46-.98 7.28-2.66l-3.57-2.77c-.98.66-2.23 1.06-3.71 1.06-2.86 0-5.29-1.93-6.16-4.53H2.18v2.84C3.99 20.53 7.7 23 12 23z" fill="#34A853"/>
          <path d="M5.84 14.09c-.22-.66-.35-1.36-.35-2.09s.13-1.43.35-2.09V7.07H2.18C1.43 8.55 1 10.22 1 12s.43 3.45 1.18 4.93l2.85-2.22.81-.62z" fill="#FBBC05"/>
          <path d="M12 5.38c1.62 0 3.06.56 4.21 1.64l3.15-3.15C17.45 2.09 14.97 1 12 1 7.7 1 3.99 3.47 2.18 7.07l3.66 2.84c.87-2.6 3.3-4.53 6.16-4.53z" fill="#EA4335"/>
        </svg>
      ) : (
        <svg className="h-4 w-4 text-primary" fill="currentColor" viewBox="0 0 24 24" aria-hidden>
          <path d="M12 2C6.477 2 2 6.484 2 12.017c0 4.425 2.865 8.18 6.839 9.504.5.092.682-.217.682-.483 0-.237-.008-.868-.013-1.703-2.782.605-3.369-1.343-3.369-1.343-.454-1.158-1.11-1.466-1.11-1.466-.908-.62.069-.608.069-.608 1.003.07 1.531 1.032 1.531 1.032.892 1.53 2.341 1.088 2.91.832.092-.647.35-1.088.636-1.338-2.22-.253-4.555-1.113-4.555-4.951 0-1.093.39-1.988 1.029-2.688-.103-.253-.446-1.272.098-2.65 0 0 .84-.27 2.75 1.026A9.564 9.564 0 0 1 12 6.844a9.59 9.59 0 0 1 2.504.337c1.909-1.296 2.747-1.027 2.747-1.027.546 1.379.202 2.398.1 2.651.64.7 1.028 1.595 1.028 2.688 0 3.848-2.339 4.695-4.566 4.943.359.309.678.92.678 1.855 0 1.338-.012 2.419-.012 2.747 0 .268.18.58.688.482A10.02 10.02 0 0 0 22 12.017C22 6.484 17.522 2 12 2z"/>
        </svg>
      )}
      {children}
    </a>
  );
}

function Divider({ label }: { label: string }) {
  return (
    <div className="relative my-5">
      <div className="absolute inset-0 flex items-center">
        <div className="w-full border-t border-[var(--border-default)]" />
      </div>
      <div className="relative flex justify-center">
        <span className="bg-[var(--geist-bg)] px-3 text-[11px] uppercase tracking-wider text-muted">{label}</span>
      </div>
    </div>
  );
}

function Field({ label, id, right, children }: { label: string; id: string; right?: React.ReactNode; children: React.ReactNode }) {
  return (
    <div>
      <div className="mb-1.5 flex items-center justify-between">
        <label htmlFor={id} className="text-xs font-medium text-secondary uppercase tracking-wider">
          {label}
        </label>
        {right}
      </div>
      {children}
    </div>
  );
}
