import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { Eye, EyeOff } from 'lucide-react';
import { useAuthStore } from '@/stores/useAuthStore';

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

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');
    if (form.password !== form.confirm) { setError('Passwords do not match'); return; }
    if (form.password.length < 10) { setError('Password must be at least 10 characters'); return; }
    if (!/[A-Z]/.test(form.password)) { setError('Password must contain at least one uppercase letter'); return; }
    if (!/[a-z]/.test(form.password)) { setError('Password must contain at least one lowercase letter'); return; }
    if (!/[0-9]/.test(form.password)) { setError('Password must contain at least one digit'); return; }
    if (!/[!@#$%^&*()_+\-=\[\]{};':",./<>?]/.test(form.password)) { setError('Password must contain at least one special character'); return; }
    if (/\s/.test(form.password)) { setError('Password must not contain whitespace'); return; }
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
        <div className="w-full max-w-md rounded-sm border border-[var(--border-default)] bg-[var(--gray-200)] p-8 text-center ">
          <div className="mb-4 text-5xl">📧</div>
          <h2 className="mb-2 text-xl font-bold text-primary">Check your inbox</h2>
          <p className="text-muted">
            We sent a verification link to <strong className="text-primary">{form.email}</strong>.
            Click it to activate your account.
          </p>
          <Link to="/login" className="mt-6 inline-block text-[var(--text-secondary)] hover:text-accent-blue text-sm">
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
          <h1 className="text-3xl font-bold text-[var(--text-secondary)]">executespec</h1>
          <p className="mt-2 text-muted">Create your free account</p>
        </div>
        <div className="rounded-sm border border-[var(--border-default)] bg-[var(--gray-200)] p-8 ">
          <h2 className="mb-6 text-xl font-bold text-primary">Sign up</h2>
          <form onSubmit={handleSubmit} className="space-y-4">
            <div className="grid grid-cols-2 gap-3">
              <div>
                <label className="mb-1 block text-sm text-muted">First name</label>
                <input type="text" value={form.firstName} onChange={set('firstName')} required
                  className="w-full rounded-sm border border-[var(--border-default)] bg-[var(--gray-300)] px-3 py-2 text-primary focus:border-accent-blue focus:outline-none" />
              </div>
              <div>
                <label className="mb-1 block text-sm text-muted">Last name</label>
                <input type="text" value={form.lastName} onChange={set('lastName')} required
                  className="w-full rounded-sm border border-[var(--border-default)] bg-[var(--gray-300)] px-3 py-2 text-primary focus:border-accent-blue focus:outline-none" />
              </div>
            </div>
            <div>
              <label className="mb-1 block text-sm text-muted">Email</label>
              <input type="email" value={form.email} onChange={set('email')} required
                className="w-full rounded-sm border border-[var(--border-default)] bg-[var(--gray-300)] px-3 py-2 text-primary focus:border-accent-blue focus:outline-none" />
            </div>
            <div>
              <label className="mb-1 block text-sm text-muted">Password</label>
              <div className="relative">
                <input type={showPassword ? 'text' : 'password'} value={form.password} onChange={set('password')} required minLength={10}
                  className="w-full rounded-sm border border-[var(--border-default)] bg-[var(--gray-300)] px-3 py-2 pr-10 text-primary focus:border-accent-blue focus:outline-none" />
                <button type="button" onClick={() => setShowPassword(!showPassword)}
                  className="absolute right-2 top-1/2 -translate-y-1/2 text-muted hover:text-primary transition-colors" tabIndex={-1}>
                  {showPassword ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
                </button>
              </div>
              <p className="mt-1 text-xs text-muted">Min 10 chars, uppercase, lowercase, digit, special character, no spaces</p>
            </div>
            <div>
              <label className="mb-1 block text-sm text-muted">Confirm password</label>
              <div className="relative">
                <input type={showConfirm ? 'text' : 'password'} value={form.confirm} onChange={set('confirm')} required
                  className="w-full rounded-sm border border-[var(--border-default)] bg-[var(--gray-300)] px-3 py-2 pr-10 text-primary focus:border-accent-blue focus:outline-none" />
                <button type="button" onClick={() => setShowConfirm(!showConfirm)}
                  className="absolute right-2 top-1/2 -translate-y-1/2 text-muted hover:text-primary transition-colors" tabIndex={-1}>
                  {showConfirm ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
                </button>
              </div>
            </div>
            {error && <p className="rounded-sm bg-red-900/30 px-3 py-2 text-sm text-accent-red">{error}</p>}
            <button type="submit" disabled={loading}
              className="w-full rounded-sm bg-accent-blue py-2.5 font-semibold text-primary hover:bg-accent-blue disabled:opacity-50 transition-colors">
              {loading ? 'Creating account…' : 'Create account'}
            </button>
            <p className="mt-3 text-center text-xs text-muted">
              By registering, you agree to our{' '}
              <Link to="/terms" className="text-[var(--text-secondary)] hover:text-accent-blue">Terms of Service</Link>
              {' '}and{' '}
              <Link to="/privacy" className="text-[var(--text-secondary)] hover:text-accent-blue">Privacy Policy</Link>.
            </p>
          </form>
          <p className="mt-4 text-center text-sm text-muted">
            Already have an account?{' '}
            <Link to="/login" className="text-[var(--text-secondary)] hover:text-accent-blue">Sign in</Link>
          </p>
        </div>
      </div>
    </div>
  );
}
