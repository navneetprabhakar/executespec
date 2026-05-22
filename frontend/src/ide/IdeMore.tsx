import {useEffect, useState} from 'react';
import {Link, useNavigate} from 'react-router-dom';
import * as api from '@/lib/api';
import {useAuthStore, selectNormalizedTier} from '@/stores/useAuthStore';
import {useUiStore} from '@/stores/useUiStore';
import {IdeBadge, IdeLoader, statusKind} from '@/ide/IdeKit';
import {GcpCredentialPanel} from '@/signal/components/GcpCredentialPanel';

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
        <IdeLoader label='Loading runs…' />
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
          {to: '/deployments', label: 'Deployments', sub: 'Cloud Run'},
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

// ───────────────────────── Deployments (list + lifecycle) ─────────────────────────
// Permanent Cloud Run deployments promoted from runs. Mirrors the Signal
// DeploymentsPage (list + rollback + delete); the deploy itself is initiated
// from a run's result page ("Deploy to Cloud Run").
export function IdeDeployments() {
  const [items, setItems] = useState<api.DeploymentSummary[] | null>(null);
  const [busy, setBusy] = useState('');
  const [msg, setMsg] = useState('');

  function load() {
    api
      .getDeployments()
      .then(setItems)
      .catch(() => setItems([]));
  }
  useEffect(() => {
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  async function act(key: string, fn: () => Promise<unknown>, confirm?: string) {
    if (confirm && !window.confirm(confirm)) return;
    setBusy(key);
    setMsg('');
    try {
      await fn();
      setMsg('Done.');
      load();
    } catch (e: unknown) {
      setMsg(e instanceof Error ? e.message : 'Action failed.');
    } finally {
      setBusy('');
    }
  }

  return (
    <div className='ide-pane'>
      <div className='ide-h1'>Deployments</div>
      <div className='ide-sub'>
        Permanent Cloud Run services promoted from completed runs. Deploy a run from its result
        page.
      </div>
      <GcpCredentialPanel variant='ide' />
      {msg && (
        <div className='ide-card' style={{marginBottom: 12, color: 'var(--ide-blue)'}}>
          {msg}
        </div>
      )}
      {items === null ? (
        <IdeLoader label='Loading deployments…' />
      ) : items.length === 0 ? (
        <div className='ide-empty'>No permanent deployments yet.</div>
      ) : (
        <div className='ide-card' style={{padding: 0}}>
          {items.map((d, i) => (
            <div
              key={d.id}
              style={{
                padding: '12px 16px',
                borderBottom: i < items.length - 1 ? '1px solid var(--ide-border)' : 'none',
              }}
            >
              <div style={{display: 'flex', alignItems: 'center', gap: 10, marginBottom: 6}}>
                <IdeBadge kind={statusKind(d.status)}>{d.status}</IdeBadge>
                <span className='ide-mono' style={{fontSize: 12.5, fontWeight: 600}}>
                  {d.versionTag || d.id.slice(0, 8)}
                </span>
                <span style={{fontSize: 11, color: 'var(--ide-fg-muted)'}}>{d.strategy}</span>
                <Link
                  to={`/result/${d.runId}`}
                  style={{fontSize: 11, color: 'var(--ide-blue)', marginLeft: 'auto'}}
                >
                  run ↗
                </Link>
              </div>
              {d.serviceUrl && (
                <a
                  href={d.serviceUrl}
                  target='_blank'
                  rel='noreferrer'
                  className='ide-mono'
                  style={{
                    fontSize: 12,
                    color: 'var(--ide-blue)',
                    wordBreak: 'break-all',
                    display: 'block',
                    marginBottom: 8,
                  }}
                >
                  {d.serviceUrl}
                </a>
              )}
              <div style={{fontSize: 11, color: 'var(--ide-fg-muted)', marginBottom: 8}}>
                Created {fmtWhen(d.createdAt)}
                {d.ttlExpiresAt ? ` · TTL ${fmtWhen(d.ttlExpiresAt)}` : ''}
              </div>
              <div style={{display: 'flex', gap: 6}}>
                <button
                  className='ide-btn ghost'
                  style={{padding: '4px 10px'}}
                  disabled={busy === `rb${d.id}`}
                  onClick={() =>
                    act(
                      `rb${d.id}`,
                      () => api.rollbackDeployment(d.id),
                      'Roll this deployment back to the previous revision?',
                    )
                  }
                >
                  Rollback
                </button>
                <button
                  className='ide-btn ghost'
                  style={{padding: '4px 10px', color: 'var(--ide-red)'}}
                  disabled={busy === `rm${d.id}`}
                  onClick={() =>
                    act(
                      `rm${d.id}`,
                      () => api.deleteDeployment(d.id),
                      'Delete this deployment record? The Cloud Run service is torn down.',
                    )
                  }
                >
                  Delete
                </button>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
