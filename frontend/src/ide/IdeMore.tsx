import {useEffect, useMemo, useState} from 'react';
import {Link, useNavigate} from 'react-router-dom';
import * as api from '@/lib/api';
import {useAuthStore, selectNormalizedTier} from '@/stores/useAuthStore';
import {useUiStore} from '@/stores/useUiStore';
import {IdeBadge, IdeLoader, statusKind} from '@/ide/IdeKit';
import {runTitleFor, relTime} from '@/signal/lib/runUtils';
import {GcpCredentialPanel} from '@/signal/components/GcpCredentialPanel';

function fmtWhen(iso?: string | null): string {
  if (!iso) return '—';
  const d = new Date(iso);
  return isNaN(d.getTime()) ? '—' : d.toLocaleString();
}

const PAGE_SIZE = 30;
const LIVE_STATUSES = new Set([
  'CLASSIFYING',
  'PLANNING',
  'EXECUTING',
  'AWAITING_APPROVAL',
  'RUNNING',
  'PROVIDER_SWITCHING',
  'PAUSED',
]);
const isLiveStatus = (s?: string | null) => LIVE_STATUSES.has((s || '').toUpperCase());
// verifiedScore is on RunDetail; RunSummary carries it on the wire but not in the
// type. Read it (and the cost field) through a cast — same shape AllRunsPage uses.
const scoreOf = (r: api.RunSummary) => (r as unknown as {verifiedScore?: number}).verifiedScore;
const costOf = (r: api.RunSummary) =>
  r.actualCostUsd ??
  (r as unknown as {actualCost?: number}).actualCost ??
  (r as unknown as {totalCost?: number}).totalCost;

type RunFilter = 'all' | 'live' | 'shipped' | 'needs' | 'failed';

// Score → IDE band colour (≥85 shippable / 70-84 needs review / <70 failed).
function scoreColor(score?: number | null): string {
  if (score == null) return 'var(--ide-fg-muted)';
  if (score >= 85) return 'var(--ide-green)';
  if (score >= 70) return 'var(--ide-yellow)';
  return 'var(--ide-red)';
}

// ───────────────────────── All runs (list) ─────────────────────────
export function IdeRuns() {
  const [runs, setRuns] = useState<api.RunSummary[] | null>(null);
  const [filter, setFilter] = useState<RunFilter>('all');
  const [page, setPage] = useState(0);
  const [more, setMore] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const [err, setErr] = useState('');

  useEffect(() => {
    let cancelled = false;
    api
      .getRuns({page: 0, size: PAGE_SIZE})
      .then((p) => {
        if (cancelled) return;
        setRuns(p.content ?? []);
        setMore(p.hasNext);
        setPage(0);
      })
      .catch((e: unknown) => {
        if (cancelled) return;
        setRuns([]);
        setErr(e instanceof Error ? e.message : 'Failed to load runs.');
      });
    return () => {
      cancelled = true;
    };
  }, []);

  async function loadMore() {
    setLoadingMore(true);
    setErr('');
    try {
      const next = await api.getRuns({page: page + 1, size: PAGE_SIZE});
      setRuns((prev) => [...(prev ?? []), ...(next.content ?? [])]);
      setPage((p) => p + 1);
      setMore(next.hasNext);
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Failed to load more.');
    } finally {
      setLoadingMore(false);
    }
  }

  // Bucket each loaded run once; drive both the strip counts and the filtered list.
  const counts = useMemo(() => {
    const list = runs ?? [];
    let live = 0,
      shipped = 0,
      needs = 0,
      failed = 0;
    for (const r of list) {
      const s = (r.status || '').toUpperCase();
      const score = scoreOf(r);
      if (isLiveStatus(s)) live++;
      else if (s === 'COMPLETED' && (score == null || score >= 85)) shipped++;
      else if (s === 'PARTIAL' || (s === 'COMPLETED' && score != null && score < 85)) needs++;
      else if (s === 'FAILED' || s === 'CANCELLED') failed++;
    }
    return {all: list.length, live, shipped, needs, failed};
  }, [runs]);

  const filtered = useMemo(() => {
    const list = runs ?? [];
    if (filter === 'all') return list;
    return list.filter((r) => {
      const s = (r.status || '').toUpperCase();
      const score = scoreOf(r);
      if (filter === 'live') return isLiveStatus(s);
      if (filter === 'shipped') return s === 'COMPLETED' && (score == null || score >= 85);
      if (filter === 'needs')
        return s === 'PARTIAL' || (s === 'COMPLETED' && score != null && score < 85);
      if (filter === 'failed') return s === 'FAILED' || s === 'CANCELLED';
      return true;
    });
  }, [runs, filter]);

  const TABS: Array<{key: RunFilter; label: string; n: number}> = [
    {key: 'all', label: 'All', n: counts.all},
    {key: 'live', label: 'Live', n: counts.live},
    {key: 'shipped', label: 'Shipped', n: counts.shipped},
    {key: 'needs', label: 'Needs review', n: counts.needs},
    {key: 'failed', label: 'Failed', n: counts.failed},
  ];

  return (
    <div className='ide-pane'>
      <div className='ide-h1'>All runs</div>
      <div className='ide-sub'>
        {runs === null
          ? 'Every spec you’ve run, newest first.'
          : `${counts.all} loaded · ${counts.live} live`}
      </div>

      <div className='ide-rtabs' style={{marginBottom: 12}}>
        {TABS.map((t) => (
          <button
            key={t.key}
            className={`ide-rtab${filter === t.key ? ' active' : ''}`}
            onClick={() => setFilter(t.key)}
          >
            {t.label} <span className='ide-rtab-badge'>{t.n}</span>
          </button>
        ))}
      </div>

      {err && (
        <div className='ide-card' style={{marginBottom: 12, color: 'var(--ide-red)'}}>
          {err}
        </div>
      )}

      {runs === null ? (
        <IdeLoader label='Loading runs…' />
      ) : counts.all === 0 ? (
        <div className='ide-empty'>No runs yet — start with a new spec.</div>
      ) : filtered.length === 0 ? (
        <div className='ide-empty'>
          No runs match this filter.{' '}
          <button
            className='ide-btn ghost'
            style={{padding: '2px 8px', marginLeft: 4}}
            onClick={() => setFilter('all')}
          >
            Show all
          </button>
        </div>
      ) : (
        <>
          <div className='ide-card' style={{padding: 0}}>
            {filtered.map((r, i) => {
              const live = isLiveStatus(r.status);
              const score = scoreOf(r);
              const cost = costOf(r);
              return (
                <Link
                  key={r.runId}
                  to={live ? `/live/${r.runId}` : `/result/${r.runId}`}
                  style={{
                    display: 'flex',
                    alignItems: 'center',
                    gap: 12,
                    padding: '11px 16px',
                    textDecoration: 'none',
                    color: 'var(--ide-fg)',
                    borderBottom: i < filtered.length - 1 ? '1px solid var(--ide-border)' : 'none',
                  }}
                >
                  <IdeBadge kind={statusKind(r.status)}>{r.status}</IdeBadge>
                  <span style={{flex: 1, minWidth: 0}}>
                    <span
                      style={{
                        display: 'block',
                        overflow: 'hidden',
                        textOverflow: 'ellipsis',
                        whiteSpace: 'nowrap',
                        fontWeight: 500,
                      }}
                    >
                      {runTitleFor(r)}
                    </span>
                    <span className='ide-mono' style={{fontSize: 11, color: 'var(--ide-fg-muted)'}}>
                      R-{r.runId.slice(0, 6)}
                      {r.projectName ? ` · ${String(r.projectName).slice(0, 24)}` : ''} ·{' '}
                      {relTime(r.startedAt)}
                    </span>
                  </span>
                  {r.aiProvider && (
                    <span style={{color: 'var(--ide-fg-muted)', fontSize: 12, flexShrink: 0}}>
                      {r.aiProvider.toLowerCase()}
                    </span>
                  )}
                  <span style={{flexShrink: 0, textAlign: 'right', minWidth: 56}}>
                    <span style={{fontSize: 12.5, fontWeight: 600, color: scoreColor(score)}}>
                      {score != null ? `${Math.round(score)}%` : live ? 'live' : '—'}
                    </span>
                    {cost != null && (
                      <span
                        className='ide-mono'
                        style={{display: 'block', fontSize: 11, color: 'var(--ide-fg-muted)'}}
                      >
                        ${cost.toFixed(2)}
                      </span>
                    )}
                  </span>
                </Link>
              );
            })}
          </div>
          {more && (
            <button
              className='ide-btn ghost'
              style={{marginTop: 12, width: '100%'}}
              disabled={loadingMore}
              onClick={loadMore}
            >
              {loadingMore ? 'Loading…' : 'Load more'}
            </button>
          )}
        </>
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
  const [notifPrefs, setNotifPrefs] = useState<api.NotificationPrefRow[] | null>(null);
  const [notifBusy, setNotifBusy] = useState<string | null>(null);
  const [exporting, setExporting] = useState(false);
  const [dsarBusy, setDsarBusy] = useState(false);
  const [erasing, setErasing] = useState(false);
  const [msg, setMsg] = useState('');

  useEffect(() => {
    if (!permissions) {
      api
        .getMyPermissions()
        .then((p) => useAuthStore.setState({permissions: p}))
        .catch(() => {});
    }
  }, [permissions]);

  // Notification preferences for the toggle panel below.
  useEffect(() => {
    api
      .getNotificationPreferences()
      .then(setNotifPrefs)
      .catch(() => setNotifPrefs([]));
  }, []);

  async function togglePref(eventType: string, channel: 'IN_APP' | 'EMAIL', enabled: boolean) {
    const key = `${eventType}:${channel}`;
    setNotifBusy(key);
    try {
      await api.updateNotificationPreference(eventType, channel, enabled);
      setNotifPrefs(await api.getNotificationPreferences());
    } catch {
      /* swallow — toggle stays at its prior value */
    } finally {
      setNotifBusy(null);
    }
  }

  // GDPR data export — no api helper exists, so fetch the ZIP with the bearer
  // token (same key the api client uses) and trigger a blob download.
  async function onExport() {
    setExporting(true);
    setMsg('');
    try {
      const token = localStorage.getItem('access_token') ?? '';
      const resp = await fetch('/api/auth/me/data-export', {
        headers: token ? {Authorization: `Bearer ${token}`} : {},
      });
      if (!resp.ok) throw new Error(`Export failed (${resp.status}).`);
      const blob = await resp.blob();
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `executespec-export-${new Date().toISOString().slice(0, 10)}.zip`;
      a.click();
      URL.revokeObjectURL(url);
      setMsg('Export downloaded.');
    } catch (e: unknown) {
      setMsg(e instanceof Error ? e.message : 'Export failed.');
    } finally {
      setExporting(false);
    }
  }

  // DSAR self-service export — GET /api/dsar/me returns a JSON snapshot of every
  // record we hold for the caller (GDPR Art. 15), distinct from the GDPR zip
  // above. No api helper, so fetch with the bearer + trigger a JSON download.
  async function onDsarExport() {
    setDsarBusy(true);
    setMsg('');
    try {
      const token = localStorage.getItem('access_token') ?? '';
      const resp = await fetch('/api/dsar/me', {
        headers: token ? {Authorization: `Bearer ${token}`} : {},
      });
      if (!resp.ok) throw new Error(`Data request failed (${resp.status}).`);
      const blob = await resp.blob();
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `executespec-dsar-${new Date().toISOString().slice(0, 10)}.json`;
      a.click();
      URL.revokeObjectURL(url);
      setMsg('Data request (JSON) downloaded.');
    } catch (e: unknown) {
      setMsg(e instanceof Error ? e.message : 'Data request failed.');
    } finally {
      setDsarBusy(false);
    }
  }

  // Right-to-erasure — DELETE /api/auth/me/data soft-deletes the caller's
  // completed/failed runs + schedules workspace teardown (GDPR Art. 17). Strong
  // window.confirm warning; no api helper, so direct fetch with the bearer.
  async function onErase() {
    if (
      !window.confirm(
        'Delete your data? This soft-deletes every completed and failed run on your account and schedules the workspaces for permanent deletion. Active runs must be cancelled first. This cannot be undone.',
      )
    )
      return;
    setErasing(true);
    setMsg('');
    try {
      const token = localStorage.getItem('access_token') ?? '';
      const resp = await fetch('/api/auth/me/data', {
        method: 'DELETE',
        headers: token ? {Authorization: `Bearer ${token}`} : {},
      });
      if (!resp.ok) throw new Error(`Erasure failed (${resp.status}).`);
      const body = (await resp.json().catch(() => ({}))) as {runsScheduledForDeletion?: number};
      const n = body.runsScheduledForDeletion ?? 0;
      setMsg(`Erasure requested — ${n} run${n === 1 ? '' : 's'} scheduled for deletion.`);
    } catch (e: unknown) {
      setMsg(e instanceof Error ? e.message : 'Erasure failed.');
    } finally {
      setErasing(false);
    }
  }

  const email = user?.email || '—';
  const name =
    [user?.firstName, user?.lastName].filter(Boolean).join(' ').trim() || email.split('@')[0];
  const usage = permissions
    ? `${permissions.currentRunCount} / ${permissions.monthlyRunLimit === -1 ? '∞' : permissions.monthlyRunLimit}`
    : '— / —';

  // Plan-limits rows from the permissions object (mirrors AccountPage's KvList).
  const limitRows: Array<{k: string; v: string}> = [
    {
      k: 'Monthly run cap',
      v: permissions
        ? permissions.monthlyRunLimit === -1
          ? 'Unlimited'
          : String(permissions.monthlyRunLimit)
        : '—',
    },
    {k: 'Runs used', v: permissions ? String(permissions.currentRunCount) : '—'},
    {
      k: 'Max org seats',
      v: permissions
        ? permissions.maxOrgMembers === -1
          ? 'Unlimited'
          : String(permissions.maxOrgMembers)
        : '—',
    },
    {k: 'Live log access', v: permissions?.canViewLiveLog ? 'Enabled' : 'PRO+'},
    {k: 'BYOK keys', v: permissions?.canManageAiKeys ? 'Enabled' : 'PRO+'},
    {k: 'Audit log', v: permissions?.canViewAudit ? 'Enabled' : 'TEAM+'},
    {
      k: 'Deploy engine',
      v: permissions?.canDeploy
        ? `Up to ${permissions.maxDeployments === -1 ? '∞' : permissions.maxDeployments}`
        : 'PRO+',
    },
  ];

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

      <div className='ide-h2'>Plan limits</div>
      <div className='ide-card'>
        {limitRows.map((row, i) => (
          <div
            key={row.k}
            className='ide-kv'
            style={i === limitRows.length - 1 ? {borderBottom: 'none'} : undefined}
          >
            <span>{row.k}</span>
            <span style={{color: 'var(--ide-fg-bright)'}}>{row.v}</span>
          </div>
        ))}
      </div>

      <div className='ide-h2'>Notifications</div>
      {notifPrefs === null ? (
        <IdeLoader label='Loading notification preferences…' />
      ) : notifPrefs.length === 0 ? (
        <div className='ide-empty'>No notification events to configure yet.</div>
      ) : (
        <div className='ide-card' style={{padding: 0}}>
          {notifPrefs.map((row, i) => {
            const key = `${row.eventType}:${row.channel}`;
            return (
              <div
                key={key}
                style={{
                  display: 'flex',
                  justifyContent: 'space-between',
                  alignItems: 'center',
                  gap: 12,
                  padding: '11px 16px',
                  borderBottom: i < notifPrefs.length - 1 ? '1px solid var(--ide-border)' : 'none',
                }}
              >
                <span style={{minWidth: 0, flex: 1}}>
                  <span style={{display: 'block', fontSize: 13, color: 'var(--ide-fg-bright)'}}>
                    {row.eventType.replace(/_/g, ' ').toLowerCase()}
                  </span>
                  <span style={{fontSize: 11, color: 'var(--ide-fg-muted)'}}>
                    {row.channel.toLowerCase()} channel
                  </span>
                </span>
                <button
                  type='button'
                  aria-pressed={row.enabled}
                  disabled={notifBusy === key}
                  onClick={() => togglePref(row.eventType, row.channel, !row.enabled)}
                  style={{
                    position: 'relative',
                    width: 38,
                    height: 20,
                    flexShrink: 0,
                    borderRadius: 999,
                    border: '1px solid var(--ide-border)',
                    background: row.enabled ? 'var(--ide-accent)' : 'var(--ide-bg-tab-inactive)',
                    cursor: notifBusy === key ? 'default' : 'pointer',
                    opacity: notifBusy === key ? 0.6 : 1,
                    transition: 'background 0.15s',
                  }}
                >
                  <span
                    style={{
                      position: 'absolute',
                      top: 1,
                      left: row.enabled ? 18 : 1,
                      width: 16,
                      height: 16,
                      borderRadius: '50%',
                      background: '#fff',
                      transition: 'left 0.15s',
                    }}
                  />
                </button>
              </div>
            );
          })}
        </div>
      )}

      <div className='ide-h2'>Security &amp; data</div>
      <div className='ide-card'>
        <div
          style={{
            display: 'flex',
            justifyContent: 'space-between',
            alignItems: 'center',
            gap: 12,
            paddingBottom: 12,
            borderBottom: '1px solid var(--ide-border)',
          }}
        >
          <span>
            <span style={{display: 'block', fontWeight: 500, color: 'var(--ide-fg-bright)'}}>
              Export my data (GDPR)
            </span>
            <span style={{fontSize: 12, color: 'var(--ide-fg-muted)'}}>
              ZIP · runs, projects, billing
            </span>
          </span>
          <button
            className='ide-btn ghost'
            style={{padding: '6px 14px', flexShrink: 0}}
            disabled={exporting}
            onClick={onExport}
          >
            {exporting ? 'Preparing…' : 'Export'}
          </button>
        </div>
        <div
          style={{
            display: 'flex',
            justifyContent: 'space-between',
            alignItems: 'center',
            gap: 12,
            padding: '12px 0',
            borderBottom: '1px solid var(--ide-border)',
          }}
        >
          <span>
            <span style={{display: 'block', fontWeight: 500, color: 'var(--ide-fg-bright)'}}>
              Request my data (DSAR)
            </span>
            <span style={{fontSize: 12, color: 'var(--ide-fg-muted)'}}>
              JSON · everything we hold · GDPR Art. 15
            </span>
          </span>
          <button
            className='ide-btn ghost'
            style={{padding: '6px 14px', flexShrink: 0}}
            disabled={dsarBusy}
            onClick={onDsarExport}
          >
            {dsarBusy ? 'Preparing…' : 'Download JSON'}
          </button>
        </div>
        <div
          style={{display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 12}}
        >
          <span>
            <span style={{display: 'block', fontWeight: 500, color: 'var(--ide-fg-bright)'}}>
              Delete my data (right to erasure)
            </span>
            <span style={{fontSize: 12, color: 'var(--ide-fg-muted)'}}>
              Schedules your runs for permanent deletion · GDPR Art. 17
            </span>
          </span>
          <button
            className='ide-btn ghost'
            style={{padding: '6px 14px', flexShrink: 0, color: 'var(--ide-red)'}}
            disabled={erasing}
            onClick={onErase}
          >
            {erasing ? 'Deleting…' : 'Delete'}
          </button>
        </div>
        {msg && <div style={{marginTop: 10, fontSize: 12.5, color: 'var(--ide-blue)'}}>{msg}</div>}
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
