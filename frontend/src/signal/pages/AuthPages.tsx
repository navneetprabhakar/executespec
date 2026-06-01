import {useEffect, useState} from 'react';
import {Link, useNavigate, useLocation} from 'react-router-dom';
import {Button} from '@/signal/components/Primitives';
import AuthLayout from '@/signal/components/AuthLayout';
import {useAuthStore} from '@/stores/useAuthStore';
import * as api from '@/lib/api';

// OAuth — direct hit on Spring Security's authorization endpoint mapped
// at /api/auth/oauth2/authorize/{registrationId} (see SecurityConfig
// .oauth2Login.authorizationEndpoint). The earlier /api/auth/oauth2/init
// bridge was a forward-compat hook for the v3 surface-split rewrite
// (vibe / executespec) which never landed on this branch — calling it
// returns 404, breaking Continue-with-Google. Going direct works on
// every env that has OAuth configured. URLs hardcoded verbatim from
// dev branch (no env-var indirection).

function GoogleIcon() {
  // Google's official 4-colour "G" mark. Matches dev branch's LoginPage and
  // the brand-asset published at developers.google.com/identity/branding.
  return (
    <svg viewBox='0 0 24 24' style={{width: 18, height: 18}} aria-hidden>
      <path
        d='M22.56 12.25c0-.78-.07-1.53-.2-2.25H12v4.26h5.92a5.06 5.06 0 0 1-2.2 3.32v2.77h3.57c2.08-1.92 3.28-4.74 3.28-8.1z'
        fill='#4285F4'
      />
      <path
        d='M12 23c2.97 0 5.46-.98 7.28-2.66l-3.57-2.77c-.98.66-2.23 1.06-3.71 1.06-2.86 0-5.29-1.93-6.16-4.53H2.18v2.84C3.99 20.53 7.7 23 12 23z'
        fill='#34A853'
      />
      <path
        d='M5.84 14.09c-.22-.66-.35-1.36-.35-2.09s.13-1.43.35-2.09V7.07H2.18C1.43 8.55 1 10.22 1 12s.43 3.45 1.18 4.93l2.85-2.22.81-.62z'
        fill='#FBBC05'
      />
      <path
        d='M12 5.38c1.62 0 3.06.56 4.21 1.64l3.15-3.15C17.45 2.09 14.97 1 12 1 7.7 1 3.99 3.47 2.18 7.07l3.66 2.84c.87-2.6 3.3-4.53 6.16-4.53z'
        fill='#EA4335'
      />
    </svg>
  );
}
function GitHubIcon() {
  return (
    <svg viewBox='0 0 24 24' style={{width: 18, height: 18}}>
      <path
        fill='currentColor'
        d='M12 .5C5.65.5.5 5.65.5 12c0 5.1 3.3 9.4 7.88 10.92.58.1.78-.25.78-.55 0-.27-.01-1-.02-1.96-3.2.7-3.88-1.54-3.88-1.54-.52-1.33-1.28-1.68-1.28-1.68-1.05-.72.08-.7.08-.7 1.16.08 1.77 1.19 1.77 1.19 1.03 1.77 2.7 1.26 3.36.96.1-.75.4-1.26.73-1.55-2.55-.29-5.24-1.28-5.24-5.7 0-1.26.45-2.29 1.19-3.09-.12-.3-.52-1.46.1-3.04 0 0 .97-.31 3.18 1.18A11.07 11.07 0 0 1 12 6.8c.98 0 1.98.13 2.91.38 2.2-1.5 3.18-1.18 3.18-1.18.63 1.58.23 2.74.11 3.04.74.8 1.19 1.83 1.19 3.09 0 4.42-2.7 5.4-5.26 5.69.41.36.78 1.06.78 2.13 0 1.54-.01 2.78-.01 3.16 0 .3.2.66.79.55C20.2 21.4 23.5 17.1 23.5 12 23.5 5.65 18.35.5 12 .5z'
      />
    </svg>
  );
}

function OAuthBtn({href, children}: {href: string; children: React.ReactNode}) {
  return (
    <a
      href={href}
      style={{
        width: '100%',
        background: 'var(--signal-card-bg)',
        border: '1px solid var(--signal-hairline)',
        color: 'var(--signal-ink)',
        padding: '13px 16px',
        borderRadius: 14,
        fontSize: 15,
        fontWeight: 500,
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        gap: 10,
        marginBottom: 10,
        fontFamily: 'inherit',
        cursor: 'pointer',
        textDecoration: 'none',
        boxSizing: 'border-box',
      }}
    >
      {children}
    </a>
  );
}

function decodeOAuthError(code: string): string {
  switch (code) {
    case 'oauth_no_email':
      return "We couldn't read an email from that account. Make sure the Google/GitHub account you used has a verified email and try again.";
    case 'oauth_email_conflict':
      return 'That email is already registered with a password. Sign in with your password, then link the OAuth provider from Account settings.';
    case 'oauth_failed':
      return 'OAuth sign-in failed. Try again, or use email + password.';
    default:
      return code.replace(/_/g, ' ');
  }
}

function ErrorBanner({msg}: {msg: string}) {
  if (!msg) return null;
  return (
    <div
      style={{
        background: 'var(--signal-coral-soft)',
        color: 'var(--signal-coral-text)',
        border: '1px solid var(--signal-coral)',
        borderRadius: 10,
        padding: '10px 14px',
        marginBottom: 12,
        fontSize: 13,
      }}
    >
      {msg}
    </div>
  );
}

export function LoginPage() {
  const navigate = useNavigate();
  const location = useLocation();
  const login = useAuthStore((s) => s.login);
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [err, setErr] = useState('');
  const [busy, setBusy] = useState(false);

  const fromState = (location.state as {from?: {pathname: string}} | null)?.from?.pathname;
  // `?redirect=` carries a full internal path (incl. query) across the login bounce — used by the
  // CLI/VS Code `/cli-login` device-authorization flow, which must preserve its ?port=&state=.
  const redirectParam = new URLSearchParams(location.search).get('redirect');
  const safeRedirect = redirectParam && redirectParam.startsWith('/') ? redirectParam : undefined;
  const target = safeRedirect || fromState || '/home';

  // Pick up OAuth failure bounces — the backend lands us at /login?error=…
  // when the OAuth dance can't complete (no email, email conflict, etc.).
  useEffect(() => {
    const errorCode = new URLSearchParams(location.search).get('error');
    if (errorCode) setErr(decodeOAuthError(errorCode));
  }, [location.search]);

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    setErr('');
    setBusy(true);
    try {
      await login(email, password);
      navigate(target, {replace: true});
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : 'Sign-in failed.';
      setErr(msg);
    } finally {
      setBusy(false);
    }
  }

  return (
    <AuthLayout>
      <div className='signal-splash'>
        <div className='brand'>
          <div className='logo'>E</div>
          <div style={{fontSize: 16, fontWeight: 600}}>ExecuteSpec</div>
        </div>
        <h1>
          Welcome back, <em>builder.</em>
        </h1>
        <p className='lede'>
          Sign in to pick up your runs, projects and pull requests right where you left them.
        </p>

        <ErrorBanner msg={err} />

        <OAuthBtn href='/api/auth/oauth2/authorize/google'>
          <GoogleIcon /> Continue with Google
        </OAuthBtn>
        <OAuthBtn href='/api/auth/oauth2/authorize/github'>
          <GitHubIcon /> Continue with GitHub
        </OAuthBtn>

        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: 10,
            color: 'var(--signal-text-mute)',
            fontSize: 12,
            fontWeight: 600,
            margin: '14px 0',
            textTransform: 'uppercase',
            letterSpacing: '0.1em',
          }}
        >
          <div style={{flex: 1, height: 1, background: 'var(--signal-hairline)'}} />
          or
          <div style={{flex: 1, height: 1, background: 'var(--signal-hairline)'}} />
        </div>

        <form onSubmit={onSubmit}>
          <div className='signal-field'>
            <label>Email</label>
            <input
              type='email'
              autoComplete='email'
              placeholder='you@company.com'
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              required
            />
          </div>
          <div className='signal-field'>
            <label>Password</label>
            <input
              type='password'
              autoComplete='current-password'
              placeholder='••••••••'
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              required
            />
            <div className='hint' style={{textAlign: 'right'}}>
              <Link to='/forgot-password' style={{color: 'var(--signal-coral)'}}>
                Forgot password?
              </Link>
            </div>
          </div>
          <Button kind='primary' block type='submit' disabled={busy}>
            {busy ? 'Signing in…' : 'Sign in'}
          </Button>
        </form>

        <div className='footer-link'>
          New here? <Link to='/signup'>Create an account</Link>
        </div>
      </div>
    </AuthLayout>
  );
}

export function SignupPage() {
  const navigate = useNavigate();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [err, setErr] = useState('');
  const [busy, setBusy] = useState(false);

  // Derive a first/last name from the email local-part so the user
  // doesn't have to fill them in. They can edit later in Account.
  function deriveNames(email: string): {firstName: string; lastName: string} {
    const local = (email.split('@')[0] || '').replace(/[._]+/g, ' ').trim();
    const parts = local.split(/\s+/).filter(Boolean);
    const firstName = parts[0] ? parts[0][0].toUpperCase() + parts[0].slice(1) : 'New';
    const lastName =
      parts.length > 1
        ? parts[parts.length - 1][0].toUpperCase() + parts[parts.length - 1].slice(1)
        : 'User';
    return {firstName, lastName};
  }

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    setErr('');
    setBusy(true);
    try {
      const {firstName, lastName} = deriveNames(email);
      await api.authRegister({email, password, firstName, lastName, signupSource: 'signal'});
      navigate('/verify-email?sent=1', {replace: true});
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : 'Sign-up failed.';
      setErr(msg);
    } finally {
      setBusy(false);
    }
  }

  return (
    <AuthLayout>
      <div className='signal-splash'>
        <div className='brand'>
          <div className='logo'>E</div>
          <div style={{fontSize: 16, fontWeight: 600}}>ExecuteSpec</div>
        </div>
        <h1>
          Create an <em>account.</em>
        </h1>
        <p className='lede'>Free tier · no card · 15 runs/month. You can upgrade any time.</p>

        <ErrorBanner msg={err} />

        <OAuthBtn href='/api/auth/oauth2/authorize/google'>
          <GoogleIcon /> Continue with Google
        </OAuthBtn>
        <OAuthBtn href='/api/auth/oauth2/authorize/github'>
          <GitHubIcon /> Continue with GitHub
        </OAuthBtn>

        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: 10,
            color: 'var(--signal-text-mute)',
            fontSize: 12,
            fontWeight: 600,
            margin: '14px 0',
            textTransform: 'uppercase',
            letterSpacing: '0.1em',
          }}
        >
          <div style={{flex: 1, height: 1, background: 'var(--signal-hairline)'}} />
          or
          <div style={{flex: 1, height: 1, background: 'var(--signal-hairline)'}} />
        </div>

        <form onSubmit={onSubmit}>
          <div className='signal-field'>
            <label>Email</label>
            <input
              type='email'
              autoComplete='email'
              placeholder='you@company.com'
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              required
            />
          </div>
          <div className='signal-field'>
            <label>Password</label>
            <input
              type='password'
              autoComplete='new-password'
              placeholder='At least 12 characters'
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              minLength={12}
              required
            />
            <div className='hint'>12+ characters.</div>
          </div>
          <Button kind='primary' block type='submit' disabled={busy}>
            {busy ? 'Creating…' : 'Create account'}
          </Button>
        </form>

        <p
          style={{
            fontSize: 12,
            color: 'var(--signal-text-mute)',
            textAlign: 'center',
            marginTop: 14,
            lineHeight: 1.5,
          }}
        >
          By signing up you agree to our{' '}
          <a href='/terms' style={{color: 'var(--signal-coral)'}}>
            Terms
          </a>{' '}
          &amp;{' '}
          <a href='/privacy' style={{color: 'var(--signal-coral)'}}>
            Privacy
          </a>
          .
        </p>

        <div className='footer-link'>
          Already have an account? <Link to='/login'>Sign in</Link>
        </div>
      </div>
    </AuthLayout>
  );
}

// Verify email (magic-link / signup splash)
export function VerifyPage() {
  // Two flows handled here. OAuth callback is handled by AuthCallbackPage now,
  // see /signal/pages/AuthCallbackPage.tsx.
  //   /verify-email                       — sign-up confirmation splash
  //   /verify-email?token=...&type=email  — magic-link verification (user→login)
  const navigate = useNavigate();
  const [msg, setMsg] = useState('');
  const [err, setErr] = useState('');

  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    const token = params.get('token');
    const errorCode = params.get('error');

    if (errorCode) {
      setErr(decodeOAuthError(errorCode));
      return;
    }

    if (token) {
      setMsg('Verifying your email…');
      api
        .authVerifyEmail(token)
        .then(() => {
          setMsg('Verified! Redirecting to sign-in…');
          setTimeout(() => navigate('/login', {replace: true}), 800);
        })
        .catch((e: unknown) => {
          const m = e instanceof Error ? e.message : 'Verification failed.';
          setErr(m);
        });
    }
  }, [navigate]);

  return (
    <AuthLayout>
      <div className='signal-splash' style={{paddingTop: 60}}>
        <div
          style={{
            width: 80,
            height: 80,
            borderRadius: 24,
            background: err ? 'var(--signal-coral-soft)' : 'var(--signal-mint-soft)',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            marginBottom: 28,
          }}
        >
          <svg
            viewBox='0 0 24 24'
            fill='none'
            stroke={err ? 'var(--signal-coral-text)' : '#1d6543'}
            strokeWidth={2}
            strokeLinecap='round'
            strokeLinejoin='round'
            style={{width: 36, height: 36}}
          >
            <path d='M4 4h16c1.1 0 2 .9 2 2v12c0 1.1-.9 2-2 2H4c-1.1 0-2-.9-2-2V6c0-1.1.9-2 2-2z' />
            <polyline points='22,6 12,13 2,6' />
          </svg>
        </div>
        <h1>{err ? 'Something went wrong.' : msg ? 'One sec…' : 'Check your inbox.'}</h1>
        <p className='lede'>
          {err
            ? err
            : msg
              ? msg
              : `We've sent a verification link to your inbox. Tap the link to finish setting up your account.`}
        </p>
        {!err && !msg && (
          <Button kind='primary' block to='/login'>
            I've verified — sign in
          </Button>
        )}
        {err && (
          <Button kind='secondary' block to='/login'>
            Back to sign-in
          </Button>
        )}
      </div>
    </AuthLayout>
  );
}

// Welcome (sandbox picker + repo linking)
export function WelcomePage() {
  const navigate = useNavigate();
  const [step, setStep] = useState<1 | 2>(1);
  const [linkBusy, setLinkBusy] = useState(false);
  const [pickedSpec, setPickedSpec] = useState<string | null>(null);
  const presets = [
    {
      tone: 'signal',
      icon: '⚡',
      title: 'Build a REST API endpoint',
      desc: 'Spring Boot, with a Postgres table, validation and a passing test. ~3 minutes.',
      meta: '~3 min · 1 credit · Java',
      spec: 'Add a /api/widgets REST endpoint that exposes GET/POST/PUT/DELETE for a Widget entity stored in Postgres. Include validation, a passing test, and an OpenAPI annotation.',
    },
    {
      tone: 'mint',
      icon: '🎨',
      title: 'Build a React component',
      desc: 'A sortable, filterable data table with TypeScript types and a snapshot test.',
      meta: '~4 min · 1 credit · React + TS',
      spec: 'Build a generic <DataTable> React component with TypeScript types: columns prop, sortable header click, free-text filter, pagination. Include a vitest snapshot test.',
    },
    {
      tone: 'coral',
      icon: '⌨',
      title: 'Build a CLI tool',
      desc: 'A Python CLI with argument parsing, a config file, and a pytest suite.',
      meta: '~2 min · 1 credit · Python',
      spec: 'Build a Python CLI tool that takes a subcommand + flags, reads a TOML config, and ships with a pytest suite. Use argparse and pathlib.',
    },
  ];

  function pick(spec: string) {
    sessionStorage.setItem('signal:pending-spec', spec);
    setPickedSpec(spec);
    setStep(2);
  }

  async function connectGitHubAndContinue() {
    setLinkBusy(true);
    try {
      const r = await api.getGitHubAppInstallUrl();
      const url = (r as unknown as {installUrl?: string}).installUrl;
      if (url) window.location.href = url;
    } catch {
      // Fall through to skip if install URL fails.
      navigate('/compose');
    } finally {
      setLinkBusy(false);
    }
  }

  return (
    <AuthLayout>
      {step === 1 ? (
        <div className='welcome-step'>
          <div className='step-indicator'>
            STEP <b>1</b> · OF 2
          </div>
          <h2>Pick a starter spec to see ExecuteSpec in action.</h2>
          <p>
            We'll run it in a sandbox project — no card required, no commits to your repos. You can
            edit the spec before we kick off.
          </p>
          {presets.map((p) => (
            <button
              key={p.title}
              type='button'
              className={`preset-card ${p.tone}`}
              onClick={() => pick(p.spec)}
            >
              <div className='icon'>{p.icon}</div>
              <h4>{p.title}</h4>
              <p className='desc'>{p.desc}</p>
              <div className='meta'>
                {p.meta.split('·').map((s, i) => (
                  <span key={i}>{s.trim()}</span>
                ))}
              </div>
            </button>
          ))}
          <Button kind='ghost' block onClick={() => navigate('/compose')} style={{marginTop: 12}}>
            Skip the sandbox — write my own spec →
          </Button>
        </div>
      ) : (
        <div className='welcome-step'>
          <div className='step-indicator'>
            STEP <b>2</b> · OF 2
          </div>
          <h2>Want the agent to work on your real code?</h2>
          <p>
            Link a GitHub repo and we'll point your first run at it instead of the sandbox. You can
            always link more later from Integrations.
          </p>
          <button
            type='button'
            className='preset-card signal'
            onClick={connectGitHubAndContinue}
            disabled={linkBusy}
          >
            <div className='icon'>
              <svg
                viewBox='0 0 24 24'
                style={{width: 28, height: 28}}
                fill='currentColor'
                aria-hidden
              >
                <path d='M12 .5C5.65.5.5 5.65.5 12c0 5.1 3.3 9.4 7.88 10.92.58.1.78-.25.78-.55 0-.27-.01-1-.02-1.96-3.2.7-3.88-1.54-3.88-1.54-.52-1.33-1.28-1.68-1.28-1.68-1.05-.72.08-.7.08-.7 1.16.08 1.77 1.19 1.77 1.19 1.03 1.77 2.7 1.26 3.36.96.1-.75.4-1.26.73-1.55-2.55-.29-5.24-1.28-5.24-5.7 0-1.26.45-2.29 1.19-3.09-.12-.3-.52-1.46.1-3.04 0 0 .97-.31 3.18 1.18A11.07 11.07 0 0 1 12 6.8c.98 0 1.98.13 2.91.38 2.2-1.5 3.18-1.18 3.18-1.18.63 1.58.23 2.74.11 3.04.74.8 1.19 1.83 1.19 3.09 0 4.42-2.7 5.4-5.26 5.69.41.36.78 1.06.78 2.13 0 1.54-.01 2.78-.01 3.16 0 .3.2.66.79.55C20.2 21.4 23.5 17.1 23.5 12 23.5 5.65 18.35.5 12 .5z' />
              </svg>
            </div>
            <h4>Install the GitHub App</h4>
            <p className='desc'>
              One click. You pick which repos. Webhooks installed automatically.
            </p>
          </button>
          <Button
            kind='secondary'
            block
            onClick={() => navigate('/compose')}
            style={{marginTop: 12}}
          >
            Use sandbox for now →
          </Button>
          <Button kind='ghost' block onClick={() => setStep(1)} style={{marginTop: 6}}>
            ← Back to spec
          </Button>
          {pickedSpec && (
            <p
              style={{
                fontSize: 12,
                color: 'var(--signal-text-mute)',
                marginTop: 14,
                textAlign: 'center',
              }}
            >
              Your starter spec is queued. We'll prefill the composer once you continue.
            </p>
          )}
        </div>
      )}
    </AuthLayout>
  );
}
