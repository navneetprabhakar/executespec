import { useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { Check } from 'lucide-react';
import * as api from '@/lib/api';
import { AuthShell } from './LoginPage';

const PWD_RULES: Array<{ label: string; test: (p: string) => boolean }> = [
  { label: '10+ characters', test: (p) => p.length >= 10 },
  { label: 'uppercase letter', test: (p) => /[A-Z]/.test(p) },
  { label: 'lowercase letter', test: (p) => /[a-z]/.test(p) },
  { label: 'digit', test: (p) => /[0-9]/.test(p) },
  { label: 'special character', test: (p) => /[!@#$%^&*()_+\-=\[\]{};':",./<>?]/.test(p) },
  { label: 'no spaces', test: (p) => p.length > 0 && !/\s/.test(p) },
];

export default function ResetPasswordPage() {
  const [searchParams] = useSearchParams();
  const token = searchParams.get('token') ?? '';
  const [pwd, setPwd] = useState('');
  const [confirm, setConfirm] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const navigate = useNavigate();

  const pwdValid = PWD_RULES.every(r => r.test(pwd));
  const pwdMatch = pwd.length > 0 && pwd === confirm;

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');
    if (!pwdValid) { setError('Password does not meet all requirements'); return; }
    if (!pwdMatch) { setError('Passwords do not match'); return; }
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
    <AuthShell>
      <div className="mb-6">
        <h2 className="text-2xl font-semibold text-primary">Choose new password</h2>
        <p className="mt-1 text-sm text-muted">Make it strong — we'll keep it safe.</p>
      </div>
      <form onSubmit={handleSubmit} className="space-y-3.5">
        <div>
          <label htmlFor="pwd" className="mb-1.5 block text-xs font-medium text-secondary uppercase tracking-wider">New password</label>
          <input id="pwd" type="password" value={pwd} onChange={e => setPwd(e.target.value)} required minLength={10} autoComplete="new-password"
            className="w-full rounded-md border border-[var(--border-default)] bg-[var(--gray-200)] px-3 py-2.5 text-sm text-primary focus:border-accent-blue focus:outline-none transition-colors" />
          {pwd.length > 0 && (
            <ul className="mt-2 grid grid-cols-2 gap-x-3 gap-y-1 text-[11px]">
              {PWD_RULES.map((r) => {
                const ok = r.test(pwd);
                return (
                  <li key={r.label} className={`flex items-center gap-1.5 ${ok ? 'text-accent-green' : 'text-muted'}`}>
                    {ok ? <Check className="h-3 w-3 flex-shrink-0" /> : <span className="inline-block h-1 w-1 rounded-full bg-muted flex-shrink-0" />}
                    {r.label}
                  </li>
                );
              })}
            </ul>
          )}
        </div>
        <div>
          <label htmlFor="confirm" className="mb-1.5 block text-xs font-medium text-secondary uppercase tracking-wider">Confirm password</label>
          <input id="confirm" type="password" value={confirm} onChange={e => setConfirm(e.target.value)} required autoComplete="new-password"
            className="w-full rounded-md border border-[var(--border-default)] bg-[var(--gray-200)] px-3 py-2.5 text-sm text-primary focus:border-accent-blue focus:outline-none transition-colors" />
        </div>
        {error && (
          <p className="rounded-md bg-[color-mix(in_srgb,var(--status-error)_12%,transparent)] border border-[color-mix(in_srgb,var(--status-error)_30%,transparent)] px-3 py-2 text-xs text-accent-red">
            {error}
          </p>
        )}
        <button type="submit" disabled={loading || !pwdValid || !pwdMatch}
          className="w-full rounded-md bg-accent-blue py-2.5 text-sm font-medium text-primary hover:bg-[var(--accent-blue-hover)] disabled:opacity-50 disabled:cursor-not-allowed transition-colors">
          {loading ? 'Resetting…' : 'Reset password'}
        </button>
      </form>
      <p className="mt-5 text-center text-sm text-muted">
        <Link to="/login" className="text-accent-blue hover:underline font-medium">&larr; Back to login</Link>
      </p>
    </AuthShell>
  );
}
