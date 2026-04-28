import { useEffect, useState } from 'react';
import { useNavigate, Link } from 'react-router-dom';
import {
  authMe,
  type AuthUser,
} from '@/lib/api';
import { useUiStore } from '@/stores/useUiStore';
import { useAuthStore } from '@/stores/useAuthStore';
import { TIER_DISPLAY } from '../lib/terminology';
import Pill from '../components/Pill';
import { isV3OptedIn, setV3OptedIn } from '../lib/optIn';

/**
 * R-140 — single-page settings stack.
 *
 * Greenfield doc §4.8: every setting lives on this one page in a
 * vertical card stack. No sub-routes. No tabs. No navigation cost.
 *
 * Wave 1 ships the cards that consume v2 APIs unchanged:
 *   - Account (display name, email, theme)
 *   - AI provider (read-only summary; full BYOK form ships in R-141)
 *   - Repos (link to v2 integrations until R-144 lands the v3 surface)
 *   - Danger zone (sign out — destructive ops gated behind confirms in R-047)
 *
 * Theme toggle wires through useUiStore but the v3 shell forces
 * dark-mode rendering (R-101). The toggle is "saved for v2" — when
 * the user navigates back to v2 their pick takes effect.
 */
export default function V3Settings() {
  const navigate = useNavigate();
  const { theme, setTheme } = useUiStore();
  const logout = useAuthStore((s) => s.logout);
  const storeUser = useAuthStore((s) => s.user);
  const [user, setUser] = useState<AuthUser | null>(storeUser ?? null);
  const [loading, setLoading] = useState(!storeUser);
  const [savedToast, setSavedToast] = useState(false);

  useEffect(() => {
    if (storeUser) {
      setUser(storeUser);
      setLoading(false);
      return;
    }
    let cancelled = false;
    setLoading(true);
    authMe()
      .then((u) => {
        if (!cancelled) setUser(u);
      })
      .catch(() => {
        if (!cancelled) setUser(null);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [storeUser]);

  function flashSaved() {
    setSavedToast(true);
    window.setTimeout(() => setSavedToast(false), 1600);
  }

  if (loading) {
    return (
      <div className="v3-placeholder">
        <p>Loading settings…</p>
      </div>
    );
  }

  if (!user) {
    return (
      <div className="v3-placeholder" style={{ borderColor: 'var(--v3-error)', color: 'var(--v3-error)' }}>
        <h1>Not signed in</h1>
        <p>Sign in to manage your account.</p>
        <Link to="/login" style={{ color: 'var(--v3-brand)', fontSize: 12 }}>
          Sign in →
        </Link>
      </div>
    );
  }

  const tierDisplay = TIER_DISPLAY[user.tier ?? 'FREE'] ?? user.tier ?? 'Free';

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16, paddingBottom: 32 }}>
      <div>
        <h1 style={{ fontSize: 22, fontWeight: 600, color: 'var(--v3-text)', margin: 0 }}>
          Settings
        </h1>
        <p style={{ fontSize: 12, color: 'var(--v3-text-muted)', margin: '4px 0 0' }}>
          Account · AI provider · Repos · Danger zone — all in one place.
        </p>
      </div>

      {/* ── Account ─────────────────────────────────────────────────────── */}
      <Card title="Account">
        <Row label="Display name">
          <span>
            {user.firstName ?? ''} {user.lastName ?? ''}
          </span>
        </Row>
        <Row label="Email">
          <span>{user.email}</span>
        </Row>
        <Row label="Plan">
          <Pill
            tone={
              user.tier === 'FREE' ? 'neutral' : user.tier === 'ENTERPRISE' ? 'brand' : 'info'
            }
            size="sm"
          >
            {tierDisplay}
          </Pill>
        </Row>
        <Row label="Theme (v2 surfaces)">
          <ThemeChooser current={theme} onPick={(t) => { setTheme(t); flashSaved(); }} />
        </Row>
      </Card>

      {/* ── AI provider summary ─────────────────────────────────────────── */}
      <Card title="AI provider">
        <Row label="Default">
          <span>{user.tier === 'FREE' ? 'Anthropic (system credits)' : 'Project default'}</span>
        </Row>
        <Row label="BYOK">
          {user.tier === 'TEAM' || user.tier === 'ENTERPRISE' ? (
            <Link
              to="/settings#api-keys"
              style={{ color: 'var(--v3-brand)', fontSize: 12, textDecoration: 'none' }}
            >
              Manage your Anthropic / OpenAI / Google keys →
            </Link>
          ) : (
            <span style={{ color: 'var(--v3-text-subtle)' }}>
              Bring-your-own-keys is available on Team and Enterprise.
            </span>
          )}
        </Row>
      </Card>

      {/* ── v3 opt-in (R-180) ──────────────────────────────────────────── */}
      <V3OptInCard />

      {/* ── Repos / integrations ────────────────────────────────────────── */}
      <Card title="Repos & integrations">
        <Row label="GitHub">
          <span>{user.hasGithubPat ? 'PAT configured ✓' : 'Not connected'}</span>
        </Row>
        <Row label="More integrations">
          <Link
            to="/integrations"
            style={{ color: 'var(--v3-brand)', fontSize: 12, textDecoration: 'none' }}
          >
            Open v2 integrations →
          </Link>
        </Row>
        <Row label="More">
          <Link
            to="/v3/integrations"
            style={{ color: 'var(--v3-brand)', fontSize: 12, textDecoration: 'none' }}
          >
            Slack · Bitbucket · Jira · Confluence →
          </Link>
        </Row>
      </Card>

      {/* ── Danger zone ─────────────────────────────────────────────────── */}
      <Card title="Danger zone" tone="warn">
        <button
          type="button"
          onClick={async () => {
            if (!confirm('Sign out everywhere?')) return;
            await logout();
            navigate('/login');
          }}
          style={{
            padding: '6px 12px',
            background: 'transparent',
            color: 'var(--v3-error)',
            border: '1px solid rgba(239,68,68,0.4)',
            borderRadius: 6,
            cursor: 'pointer',
            fontSize: 12,
            fontFamily: 'inherit',
            fontWeight: 500,
          }}
        >
          Sign out
        </button>
      </Card>

      {savedToast && (
        <div
          role="status"
          style={{
            position: 'fixed',
            bottom: 24,
            left: '50%',
            transform: 'translateX(-50%)',
            padding: '6px 14px',
            background: 'var(--v3-surface-elevated)',
            border: '1px solid var(--v3-border-strong)',
            borderRadius: 6,
            fontSize: 12,
            color: 'var(--v3-text)',
            boxShadow: '0 8px 24px rgba(0,0,0,0.4)',
          }}
        >
          Saved ✓
        </div>
      )}
    </div>
  );
}

// ─── R-180 — v3 opt-in card ─────────────────────────────────────────────────

function V3OptInCard() {
  const [optedIn, setOptedIn] = useState(isV3OptedIn());
  function toggle() {
    const next = !optedIn;
    setV3OptedIn(next);
    setOptedIn(next);
  }
  return (
    <Card title="Try the new ExecuteSpec everywhere">
      <Row label="Use v3 for v2 routes too">
        <span style={{ display: 'inline-flex', gap: 8, alignItems: 'center' }}>
          <Pill tone={optedIn ? 'ok' : 'neutral'} size="sm">
            {optedIn ? 'On' : 'Off'}
          </Pill>
          <button
            type="button"
            onClick={toggle}
            style={{
              padding: '4px 10px',
              fontSize: 11.5,
              fontWeight: 600,
              background: optedIn ? 'transparent' : 'var(--v3-brand)',
              color: optedIn ? 'var(--v3-text-muted)' : '#0B0D10',
              border: optedIn ? '1px solid var(--v3-border)' : 'none',
              borderRadius: 4,
              cursor: 'pointer',
              fontFamily: 'inherit',
            }}
          >
            {optedIn ? 'Turn off' : 'Turn on'}
          </button>
        </span>
      </Row>
      <p style={{ margin: '6px 0 0', fontSize: 11, color: 'var(--v3-text-subtle)', lineHeight: 1.55 }}>
        When on, v2 routes (<code>/dashboard</code>, <code>/billing</code>, <code>/integrations</code>,{' '}
        <code>/settings</code>, etc.) automatically redirect to their v3
        equivalents. Old browser bookmarks and email links keep working.
        Turn off any time to switch back.
      </p>
    </Card>
  );
}

// ─── Layout primitives ───────────────────────────────────────────────────────

function Card({
  title,
  children,
  tone,
}: {
  title: string;
  children: React.ReactNode;
  tone?: 'warn';
}) {
  return (
    <section
      style={{
        background: 'var(--v3-surface)',
        border:
          tone === 'warn'
            ? '1px solid rgba(245,158,11,0.4)'
            : '1px solid var(--v3-border)',
        borderRadius: 8,
        padding: 14,
      }}
    >
      <h2
        style={{
          fontSize: 14,
          fontWeight: 600,
          color: 'var(--v3-text)',
          margin: '0 0 10px',
        }}
      >
        {title}
      </h2>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>{children}</div>
    </section>
  );
}

function Row({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div
      style={{
        display: 'flex',
        justifyContent: 'space-between',
        alignItems: 'center',
        padding: '4px 0',
        fontSize: 12.5,
        color: 'var(--v3-text)',
        borderBottom: '1px dashed var(--v3-border)',
      }}
    >
      <span style={{ color: 'var(--v3-text-muted)' }}>{label}</span>
      <span>{children}</span>
    </div>
  );
}

function ThemeChooser({
  current,
  onPick,
}: {
  current: 'dark' | 'light';
  onPick: (t: 'dark' | 'light') => void;
}) {
  return (
    <div style={{ display: 'inline-flex', gap: 4 }}>
      {(['dark', 'light'] as const).map((t) => (
        <button
          key={t}
          type="button"
          onClick={() => onPick(t)}
          style={{
            padding: '3px 10px',
            fontSize: 11,
            fontWeight: 600,
            background: current === t ? 'var(--v3-surface-elevated)' : 'transparent',
            color: current === t ? 'var(--v3-text)' : 'var(--v3-text-muted)',
            border: '1px solid var(--v3-border)',
            borderRadius: 4,
            cursor: 'pointer',
            fontFamily: 'inherit',
            textTransform: 'capitalize',
          }}
        >
          {t}
        </button>
      ))}
    </div>
  );
}
