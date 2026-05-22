import {useEffect, useState} from 'react';
import {Link, useNavigate} from 'react-router-dom';
import * as api from '@/lib/api';
import {useAuthStore, selectNormalizedTier} from '@/stores/useAuthStore';
import {useUiStore} from '@/stores/useUiStore';
import {IdeBadge, statusKind} from '@/ide/IdeKit';

function fmtWhen(iso?: string | null): string {
  if (!iso) return '—';
  const d = new Date(iso);
  return isNaN(d.getTime()) ? '—' : d.toLocaleString();
}

// ───────────────────────── All runs (list) ─────────────────────────
export function IdeRuns() {
  const [runs, setRuns] = useState<api.RunSummary[] | null>(null);

  useEffect(() => {
    api
      .getRuns({size: 40})
      .then((r) => setRuns(r.content ?? []))
      .catch(() => setRuns([]));
  }, []);

  return (
    <div className='ide-pane'>
      <div className='ide-h1'>All runs</div>
      <div className='ide-sub'>Every spec you've run, newest first.</div>
      {runs === null ? (
        <div className='ide-empty'>Loading…</div>
      ) : runs.length === 0 ? (
        <div className='ide-empty'>No runs yet — start with a new spec.</div>
      ) : (
        <div className='ide-card' style={{padding: 0}}>
          {runs.map((r, i) => (
            <Link
              key={r.runId}
              to={`/result/${r.runId}`}
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: 12,
                padding: '11px 16px',
                textDecoration: 'none',
                color: 'var(--ide-fg)',
                borderBottom: i < runs.length - 1 ? '1px solid var(--ide-border)' : 'none',
              }}
            >
              <IdeBadge kind={statusKind(r.status)}>{r.status}</IdeBadge>
              <span
                style={{
                  flex: 1,
                  minWidth: 0,
                  overflow: 'hidden',
                  textOverflow: 'ellipsis',
                  whiteSpace: 'nowrap',
                  fontWeight: 500,
                }}
              >
                {(r.runTitle || r.userRequest || r.runId).slice(0, 100)}
              </span>
              {r.aiProvider && (
                <span style={{color: 'var(--ide-fg-muted)', fontSize: 12}}>
                  {r.aiProvider.toLowerCase()}
                </span>
              )}
              <span style={{color: 'var(--ide-fg-muted)', fontSize: 12, flexShrink: 0}}>
                {fmtWhen(r.startedAt)}
              </span>
            </Link>
          ))}
        </div>
      )}
    </div>
  );
}

// ───────────────────────── Account / settings ─────────────────────────
export function IdeAccount() {
  const navigate = useNavigate();
  const user = useAuthStore((s) => s.user);
  const logout = useAuthStore((s) => s.logout);
  const tier = useAuthStore(selectNormalizedTier);
  const permissions = useAuthStore((s) => s.permissions);
  const theme = useUiStore((s) => s.theme);
  const setTheme = useUiStore((s) => s.setTheme);
  const uiMode = useUiStore((s) => s.uiMode);
  const setUiMode = useUiStore((s) => s.setUiMode);

  useEffect(() => {
    if (!permissions) {
      api
        .getMyPermissions()
        .then((p) => useAuthStore.setState({permissions: p}))
        .catch(() => {});
    }
  }, [permissions]);

  const email = user?.email || '—';
  const name =
    [user?.firstName, user?.lastName].filter(Boolean).join(' ').trim() || email.split('@')[0];
  const usage = permissions
    ? `${permissions.currentRunCount} / ${permissions.monthlyRunLimit === -1 ? '∞' : permissions.monthlyRunLimit}`
    : '— / —';

  function Seg({
    on,
    onClick,
    children,
  }: {
    on: boolean;
    onClick: () => void;
    children: React.ReactNode;
  }) {
    return (
      <button
        className='ide-badge'
        style={{
          cursor: 'pointer',
          borderColor: on ? 'var(--ide-accent)' : undefined,
          color: on ? 'var(--ide-accent)' : undefined,
        }}
        onClick={onClick}
      >
        {children}
      </button>
    );
  }

  return (
    <div className='ide-pane' style={{maxWidth: 760}}>
      <div className='ide-h1'>Account</div>
      <div className='ide-sub'>{email}</div>

      <div className='ide-card'>
        <div style={{fontSize: 15, fontWeight: 600, color: 'var(--ide-fg-bright)'}}>{name}</div>
        <div style={{display: 'flex', gap: 6, marginTop: 8, flexWrap: 'wrap'}}>
          <IdeBadge kind='ok'>{tier} tier</IdeBadge>
          <IdeBadge kind='warn'>{usage} runs</IdeBadge>
          {user?.globalRole === 'SUPER_ADMIN' && <IdeBadge kind='bad'>SUPER_ADMIN</IdeBadge>}
        </div>
      </div>

      <div className='ide-h2'>Preferences</div>
      <div className='ide-card'>
        <div className='ide-kv' style={{alignItems: 'center'}}>
          <span>Interface</span>
          <span style={{display: 'flex', gap: 4}}>
            <Seg on={uiMode === 'signal'} onClick={() => setUiMode('signal')}>
              Signal
            </Seg>
            <Seg on={uiMode === 'ide'} onClick={() => setUiMode('ide')}>
              IDE
            </Seg>
          </span>
        </div>
        <div className='ide-kv' style={{alignItems: 'center', borderBottom: 'none'}}>
          <span>Theme</span>
          <span style={{display: 'flex', gap: 4}}>
            <Seg on={theme === 'light'} onClick={() => setTheme('light')}>
              Light
            </Seg>
            <Seg on={theme === 'dark'} onClick={() => setTheme('dark')}>
              Dark
            </Seg>
            <Seg on={theme === 'system'} onClick={() => setTheme('system')}>
              System
            </Seg>
          </span>
        </div>
      </div>

      <div className='ide-h2'>Manage</div>
      <div className='ide-card' style={{padding: 0}}>
        {[
          {to: '/billing', label: 'Billing & invoices', sub: `${tier} tier`},
          {to: '/plans', label: 'Compare plans', sub: ''},
          {to: '/api-keys', label: 'API keys (BYOK)', sub: ''},
          {to: '/integrations', label: 'Integrations', sub: 'GitHub · Slack · Stripe'},
          ...(user?.orgRole === 'ORG_ADMIN' || user?.globalRole === 'SUPER_ADMIN'
            ? [{to: '/org/settings', label: 'Org settings', sub: 'SSO · keys · model matrix'}]
            : []),
        ].map((row, i, arr) => (
          <Link
            key={row.to}
            to={row.to}
            style={{
              display: 'flex',
              justifyContent: 'space-between',
              alignItems: 'center',
              padding: '12px 16px',
              textDecoration: 'none',
              color: 'var(--ide-fg)',
              borderBottom: i < arr.length - 1 ? '1px solid var(--ide-border)' : 'none',
            }}
          >
            <span>{row.label}</span>
            <span style={{color: 'var(--ide-fg-muted)', fontSize: 12}}>{row.sub} ›</span>
          </Link>
        ))}
      </div>

      <button
        className='ide-btn ghost'
        style={{marginTop: 16, color: 'var(--ide-red)'}}
        onClick={() => {
          void logout();
          navigate('/login', {replace: true});
        }}
      >
        Sign out
      </button>
    </div>
  );
}
