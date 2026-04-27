import { Link, useNavigate } from 'react-router-dom';
import { useEffect, useState } from 'react';
import { useAuthStore } from '@/stores/useAuthStore';

/**
 * R-112 — "Save this run" sign-in.
 *
 * Greenfield doc §4.2: post-trial signup screen, framed as "save what
 * you already did" rather than "create an account". OAuth-first
 * (GitHub + Google), magic-link fallback. After auth, the anonymous
 * spec stashed in sessionStorage by the landing page (R-110) is
 * available to the new project home composer for re-submission as
 * a real run.
 *
 * Wave-1 implementation reuses the existing v2 OAuth + email flows
 * (no new backend wiring) — just a new framing on the same auth
 * surface. The OAuth buttons hand off to the v2 routes; on success,
 * the user lands on the v3 home (or the sandbox project home if
 * the spec was preserved).
 */
export default function V3Auth() {
  const navigate = useNavigate();
  const isAuthed = useAuthStore((s) => s.isAuthenticated);
  const login = useAuthStore((s) => s.login);
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // If they were already signed in (e.g. via a stale session), skip
  // straight to the v3 home rather than asking again.
  useEffect(() => {
    if (isAuthed) navigate('/v3/home');
  }, [isAuthed, navigate]);

  const stashedSpec =
    typeof window !== 'undefined' ? sessionStorage.getItem('v3-anon-spec') : null;

  async function onPasswordSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (!email.trim() || !password) return;
    setSubmitting(true);
    setError(null);
    try {
      await login(email.trim(), password);
      navigate('/v3/home');
    } catch (err) {
      setError((err as Error)?.message ?? 'Sign-in failed');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div
      style={{
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        gap: 16,
        padding: '64px 16px 32px',
        minHeight: 'calc(100vh - 56px - 48px)',
      }}
    >
      <div
        style={{
          width: '100%',
          maxWidth: 380,
          background: 'var(--v3-surface)',
          border: '1px solid var(--v3-border-strong)',
          borderRadius: 10,
          padding: 24,
        }}
      >
        <h1
          style={{
            fontSize: 22,
            fontWeight: 600,
            color: 'var(--v3-text)',
            margin: 0,
            textAlign: 'center',
          }}
        >
          Save this run.
        </h1>
        <p
          style={{
            fontSize: 13,
            color: 'var(--v3-text-muted)',
            textAlign: 'center',
            margin: '6px 0 20px',
            lineHeight: 1.55,
          }}
        >
          {stashedSpec
            ? 'You just shipped a real PR — sign in to keep it.'
            : 'Sign in to keep building. Free tier — no card required.'}
        </p>

        {/* OAuth — reuses existing v2 routes */}
        <div style={{ display: 'flex', flexDirection: 'column', gap: 8, marginBottom: 16 }}>
          <a
            href="/api/auth/oauth2/authorize?provider=github&returnTo=/v3/home"
            style={oauthButtonStyle('#24292e')}
          >
            <svg width="14" height="14" viewBox="0 0 16 16" fill="currentColor" aria-hidden="true">
              <path d="M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38v-1.34c-2.22.48-2.69-1.07-2.69-1.07-.36-.92-.89-1.17-.89-1.17-.73-.5.05-.49.05-.49.81.06 1.23.83 1.23.83.72 1.23 1.88.87 2.34.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82.64-.18 1.32-.27 2-.27s1.36.09 2 .27c1.53-1.03 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.27.82 2.15 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48v2.2c0 .21.15.46.55.38C13.71 14.53 16 11.54 16 8c0-4.42-3.58-8-8-8z" />
            </svg>
            Continue with GitHub
          </a>
          <a
            href="/api/auth/oauth2/authorize?provider=google&returnTo=/v3/home"
            style={oauthButtonStyle('#fff', '#1a1a1a')}
          >
            <svg width="14" height="14" viewBox="0 0 24 24" aria-hidden="true">
              <path fill="#4285F4" d="M22.56 12.25c0-.78-.07-1.53-.2-2.25H12v4.26h5.92c-.26 1.37-1.04 2.53-2.21 3.31v2.77h3.57c2.08-1.92 3.28-4.74 3.28-8.09z" />
              <path fill="#34A853" d="M12 23c2.97 0 5.46-.98 7.28-2.66l-3.57-2.77c-.99.66-2.23 1.06-3.71 1.06-2.86 0-5.29-1.93-6.16-4.53H2.18v2.84C3.99 20.53 7.7 23 12 23z" />
              <path fill="#FBBC05" d="M5.84 14.09c-.22-.66-.35-1.36-.35-2.09s.13-1.43.35-2.09V7.07H2.18C1.43 8.55 1 10.22 1 12s.43 3.45 1.18 4.93l2.85-2.22.81-.62z" />
              <path fill="#EA4335" d="M12 5.38c1.62 0 3.06.56 4.21 1.64l3.15-3.15C17.45 2.09 14.97 1 12 1 7.7 1 3.99 3.47 2.18 7.07l3.66 2.84c.87-2.6 3.3-4.53 6.16-4.53z" />
            </svg>
            Continue with Google
          </a>
        </div>

        {/* Divider */}
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: 8,
            margin: '14px 0 14px',
            color: 'var(--v3-text-subtle)',
            fontSize: 10.5,
          }}
        >
          <div style={{ flex: 1, height: 1, background: 'var(--v3-border)' }} />
          <span>or email + password</span>
          <div style={{ flex: 1, height: 1, background: 'var(--v3-border)' }} />
        </div>

        {/* Email + password (re-uses v2 login) */}
        <form onSubmit={onPasswordSubmit} style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
          <input
            type="email"
            autoComplete="email"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            placeholder="you@example.com"
            disabled={submitting}
            style={inputStyle}
          />
          <div style={{ position: 'relative' }}>
            <input
              type={showPassword ? 'text' : 'password'}
              autoComplete="current-password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              placeholder="Password"
              disabled={submitting}
              style={{ ...inputStyle, paddingRight: 56 }}
            />
            <button
              type="button"
              onClick={() => setShowPassword((s) => !s)}
              style={{
                position: 'absolute',
                right: 6,
                top: 6,
                bottom: 6,
                padding: '0 8px',
                background: 'transparent',
                color: 'var(--v3-text-subtle)',
                border: 'none',
                cursor: 'pointer',
                fontSize: 11,
              }}
            >
              {showPassword ? 'Hide' : 'Show'}
            </button>
          </div>
          <button
            type="submit"
            disabled={!email.trim() || !password || submitting}
            style={{
              padding: '8px 14px',
              fontSize: 13,
              fontWeight: 600,
              background:
                !email.trim() || !password || submitting
                  ? 'var(--v3-surface-elevated)'
                  : 'var(--v3-brand)',
              color:
                !email.trim() || !password || submitting
                  ? 'var(--v3-text-subtle)'
                  : '#0B0D10',
              border: 'none',
              borderRadius: 6,
              cursor:
                !email.trim() || !password || submitting ? 'not-allowed' : 'pointer',
              fontFamily: 'inherit',
            }}
          >
            {submitting ? 'Signing in…' : 'Sign in'}
          </button>
          {error && (
            <div
              role="alert"
              style={{
                padding: '6px 10px',
                background: 'rgba(239,68,68,0.12)',
                border: '1px solid rgba(239,68,68,0.36)',
                color: '#FCA5A5',
                borderRadius: 4,
                fontSize: 12,
              }}
            >
              {error}
            </div>
          )}
        </form>

        <p
          style={{
            marginTop: 16,
            textAlign: 'center',
            fontSize: 11,
            color: 'var(--v3-text-subtle)',
          }}
        >
          New here?{' '}
          <Link to="/register" style={{ color: 'var(--v3-brand)', textDecoration: 'none' }}>
            Sign up free
          </Link>{' '}
          · or{' '}
          <Link
            to="/forgot-password"
            style={{ color: 'var(--v3-text-subtle)', textDecoration: 'none' }}
          >
            forgot password
          </Link>
        </p>
      </div>
    </div>
  );
}

const inputStyle: React.CSSProperties = {
  width: '100%',
  padding: '8px 10px',
  background: 'var(--v3-bg)',
  color: 'var(--v3-text)',
  border: '1px solid var(--v3-border)',
  borderRadius: 6,
  fontSize: 13,
  outline: 'none',
  fontFamily: 'inherit',
};

function oauthButtonStyle(bg: string, color = '#fff'): React.CSSProperties {
  return {
    display: 'inline-flex',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 8,
    padding: '8px 14px',
    background: bg,
    color,
    border: '1px solid var(--v3-border-strong)',
    borderRadius: 6,
    fontSize: 13,
    fontWeight: 500,
    textDecoration: 'none',
    fontFamily: 'inherit',
  };
}
