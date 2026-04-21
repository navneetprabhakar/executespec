import { useState } from 'react';
import { Link } from 'react-router-dom';
import { Eye, EyeOff, Mail, Check, AlertCircle } from 'lucide-react';
import { useAuthStore } from '@/stores/useAuthStore';
import { AuthShell } from './LoginPage';

const PWD_RULES: Array<{ label: string; test: (p: string) => boolean }> = [
  { label: '10+ characters', test: (p) => p.length >= 10 },
  { label: 'uppercase letter', test: (p) => /[A-Z]/.test(p) },
  { label: 'lowercase letter', test: (p) => /[a-z]/.test(p) },
  { label: 'digit', test: (p) => /[0-9]/.test(p) },
  { label: 'special character', test: (p) => /[!@#$%^&*()_+\-=\[\]{};':",./<>?]/.test(p) },
  { label: 'no spaces', test: (p) => p.length > 0 && !/\s/.test(p) },
];

export default function RegisterPage() {
  const [form, setForm] = useState({ email: '', firstName: '', lastName: '', password: '', confirm: '' });
  const [error, setError] = useState('');
  const [success, setSuccess] = useState(false);
  const [loading, setLoading] = useState(false);
  const [showPassword, setShowPassword] = useState(false);
  const [showConfirm, setShowConfirm] = useState(false);
  const { register } = useAuthStore();

  const set = (k: keyof typeof form) => (e: React.ChangeEvent<HTMLInputElement>) =>
    setForm(f => ({ ...f, [k]: e.target.value }));

  const pwdValid = PWD_RULES.every(r => r.test(form.password));
  const pwdMatch = form.password.length > 0 && form.password === form.confirm;

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');
    if (form.password !== form.confirm) { setError('Passwords do not match'); return; }
    if (!pwdValid) { setError('Password does not meet all requirements'); return; }
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
      <AuthShell>
        <div className="text-center">
          <div className="mx-auto mb-5 flex h-14 w-14 items-center justify-center rounded-full bg-[color-mix(in_srgb,var(--accent-blue)_15%,transparent)] border border-[color-mix(in_srgb,var(--accent-blue)_30%,transparent)]">
            <Mail className="h-7 w-7 text-accent-blue" />
          </div>
          <h2 className="text-2xl font-semibold text-primary mb-2">Check your inbox</h2>
          <p className="text-sm text-muted leading-relaxed">
            We sent a verification link to<br/>
            <strong className="text-primary font-medium">{form.email}</strong>
          </p>
          <p className="mt-4 text-xs text-muted">Click the link to activate your account and start your first run.</p>
          <Link to="/login" className="mt-6 inline-block text-sm text-accent-blue hover:underline font-medium">
            &larr; Back to login
          </Link>
        </div>
      </AuthShell>
    );
  }

  return (
    <AuthShell>
      <div className="mb-6">
        <h2 className="text-2xl font-semibold text-primary">Create your account</h2>
        <p className="mt-1 text-sm text-muted">Free. 5 runs / month. No credit card.</p>
      </div>

      <form onSubmit={handleSubmit} className="space-y-3.5">
        <div className="grid grid-cols-2 gap-3">
          <Field label="First name" id="firstName">
            <input id="firstName" type="text" value={form.firstName} onChange={set('firstName')} required autoComplete="given-name"
              className="w-full rounded-md border border-[var(--border-default)] bg-[var(--gray-200)] px-3 py-2.5 text-sm text-primary focus:border-accent-blue focus:outline-none transition-colors" />
          </Field>
          <Field label="Last name" id="lastName">
            <input id="lastName" type="text" value={form.lastName} onChange={set('lastName')} required autoComplete="family-name"
              className="w-full rounded-md border border-[var(--border-default)] bg-[var(--gray-200)] px-3 py-2.5 text-sm text-primary focus:border-accent-blue focus:outline-none transition-colors" />
          </Field>
        </div>

        <Field label="Work email" id="email">
          <input id="email" type="email" value={form.email} onChange={set('email')} required autoComplete="email"
            className="w-full rounded-md border border-[var(--border-default)] bg-[var(--gray-200)] px-3 py-2.5 text-sm text-primary placeholder-[var(--text-muted)] focus:border-accent-blue focus:outline-none transition-colors"
            placeholder="you@company.com" />
        </Field>

        <Field label="Password" id="password">
          <div className="relative">
            <input id="password" type={showPassword ? 'text' : 'password'} value={form.password} onChange={set('password')} required minLength={10} autoComplete="new-password"
              className="w-full rounded-md border border-[var(--border-default)] bg-[var(--gray-200)] px-3 py-2.5 pr-10 text-sm text-primary focus:border-accent-blue focus:outline-none transition-colors" />
            <button type="button" onClick={() => setShowPassword(!showPassword)}
              className="absolute right-2 top-1/2 -translate-y-1/2 p-1 text-muted hover:text-primary transition-colors" tabIndex={-1}
              aria-label={showPassword ? 'Hide password' : 'Show password'}>
              {showPassword ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
            </button>
          </div>
          {form.password.length > 0 && (
            <ul className="mt-2 grid grid-cols-2 gap-x-3 gap-y-1 text-[11px]">
              {PWD_RULES.map((r) => {
                const ok = r.test(form.password);
                return (
                  <li key={r.label} className={`flex items-center gap-1.5 ${ok ? 'text-accent-green' : 'text-muted'}`}>
                    {ok ? <Check className="h-3 w-3 flex-shrink-0" /> : <span className="inline-block h-1 w-1 rounded-full bg-muted flex-shrink-0" />}
                    {r.label}
                  </li>
                );
              })}
            </ul>
          )}
        </Field>

        <Field label="Confirm password" id="confirm">
          <div className="relative">
            <input id="confirm" type={showConfirm ? 'text' : 'password'} value={form.confirm} onChange={set('confirm')} required autoComplete="new-password"
              className="w-full rounded-md border border-[var(--border-default)] bg-[var(--gray-200)] px-3 py-2.5 pr-10 text-sm text-primary focus:border-accent-blue focus:outline-none transition-colors" />
            <button type="button" onClick={() => setShowConfirm(!showConfirm)}
              className="absolute right-2 top-1/2 -translate-y-1/2 p-1 text-muted hover:text-primary transition-colors" tabIndex={-1}
              aria-label={showConfirm ? 'Hide password' : 'Show password'}>
              {showConfirm ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
            </button>
          </div>
          {form.confirm.length > 0 && !pwdMatch && (
            <p className="mt-1 text-[11px] text-accent-red flex items-center gap-1">
              <AlertCircle className="h-3 w-3" /> Passwords don't match
            </p>
          )}
        </Field>

        {error && (
          <p className="rounded-md bg-[color-mix(in_srgb,var(--status-error)_12%,transparent)] border border-[color-mix(in_srgb,var(--status-error)_30%,transparent)] px-3 py-2 text-xs text-accent-red">
            {error}
          </p>
        )}

        <button type="submit" disabled={loading || !pwdValid || !pwdMatch}
          className="w-full rounded-md bg-accent-blue py-2.5 text-sm font-medium text-primary hover:bg-[var(--accent-blue-hover)] disabled:opacity-50 disabled:cursor-not-allowed transition-colors">
          {loading ? 'Creating account…' : 'Create free account'}
        </button>

        <p className="text-center text-[11px] text-muted leading-relaxed">
          By registering you agree to our{' '}
          <Link to="/terms" className="text-accent-blue hover:underline">Terms</Link> and{' '}
          <Link to="/privacy" className="text-accent-blue hover:underline">Privacy Policy</Link>.
        </p>
      </form>

      <p className="mt-5 text-center text-sm text-muted">
        Already have an account?{' '}
        <Link to="/login" className="text-accent-blue hover:underline font-medium">Sign in</Link>
      </p>
    </AuthShell>
  );
}

function Field({ label, id, children }: { label: string; id: string; children: React.ReactNode }) {
  return (
    <div>
      <label htmlFor={id} className="mb-1.5 block text-xs font-medium text-secondary uppercase tracking-wider">
        {label}
      </label>
      {children}
    </div>
  );
}
