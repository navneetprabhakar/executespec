import {useEffect, useState} from 'react';
import * as api from '@/lib/api';
import {useAuthStore} from '@/stores/useAuthStore';
import {IdeBadge, IdeLoader} from '@/ide/IdeKit';

// ───────────────────────── Integrations ─────────────────────────
export function IdeIntegrations() {
  const [sources, setSources] = useState<string[] | null>(null);
  const [slack, setSlack] = useState<api.SlackStatus | null>(null);
  const [gh, setGh] = useState<api.GitHubAppStatus | null>(null);

  useEffect(() => {
    api
      .getIntegrationStatus()
      .then((s) => setSources(s.enabledSources ?? []))
      .catch(() => setSources([]));
    api
      .getSlackStatus()
      .then(setSlack)
      .catch(() => {});
    api
      .getGitHubAppStatus()
      .then(setGh)
      .catch(() => {});
  }, []);

  function open(getUrl: () => Promise<{installUrl?: string}>) {
    getUrl()
      .then((r) => r.installUrl && window.open(r.installUrl, '_blank'))
      .catch(() => {});
  }

  const has = (k: string) => (sources ?? []).map((s) => s.toLowerCase()).includes(k);
  const rows = [
    {
      name: 'GitHub',
      connected: !!gh?.installed,
      action: () => open(api.getGitHubAppInstallUrl as () => Promise<{installUrl?: string}>),
      label: 'Install app',
    },
    {
      name: 'Slack',
      connected: !!slack?.connected,
      sub: slack?.connected ? slack.teamName || '' : '',
      action: () => open(api.getSlackInstallUrl),
      label: 'Connect',
    },
    {name: 'Jira', connected: has('jira')},
    {name: 'Confluence', connected: has('confluence')},
    {name: 'Bitbucket', connected: has('bitbucket')},
  ];

  return (
    <div className='ide-pane' style={{maxWidth: 760}}>
      <div className='ide-h1'>Integrations</div>
      <div className='ide-sub'>Connect GitHub, Slack, and your issue trackers.</div>
      {sources === null ? (
        <IdeLoader label='Loading integrations…' />
      ) : (
        <div className='ide-card' style={{padding: 0}}>
          {rows.map((r, i) => (
            <div
              key={r.name}
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: 10,
                padding: '13px 16px',
                borderBottom: i < rows.length - 1 ? '1px solid var(--ide-border)' : 'none',
              }}
            >
              <span style={{flex: 1, fontWeight: 500}}>
                {r.name}
                {r.sub && (
                  <span style={{color: 'var(--ide-fg-muted)', fontWeight: 400}}> · {r.sub}</span>
                )}
              </span>
              <IdeBadge kind={r.connected ? 'ok' : 'warn'}>
                {r.connected ? 'Connected' : 'Not connected'}
              </IdeBadge>
              {r.action && !r.connected && (
                <button className='ide-btn ghost' style={{padding: '4px 10px'}} onClick={r.action}>
                  {r.label}
                </button>
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

// ───────────────────────── Admin ─────────────────────────
// Canonical activation funnel — label + the stage key returned by
// /admin/activation-funnel (mirrors analytics.ts's 6-event funnel).
const FUNNEL_STAGES: Array<[string, string]> = [
  ['Signups', 'SIGNUP'],
  ['Projects', 'PROJECT_CREATED'],
  ['Specs', 'SPEC_SUBMITTED'],
  ['Plans approved', 'PLAN_APPROVED'],
  ['Runs complete', 'RUN_COMPLETE'],
  ['PRs merged', 'PR_MERGED'],
];

// Shared row container — one bordered list inside an ide-card.
function AdminList({children}: {children: React.ReactNode}) {
  return (
    <div className='ide-card' style={{padding: 0}}>
      {children}
    </div>
  );
}

const ROW_BASE: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 10,
  padding: '10px 16px',
};
function rowStyle(last: boolean): React.CSSProperties {
  return {...ROW_BASE, borderBottom: last ? 'none' : '1px solid var(--ide-border)'};
}

// Small inline-edit <select> styled as a chip-sized control.
function MiniSelect({
  value,
  options,
  onChange,
  disabled,
}: {
  value: string;
  options: string[];
  onChange: (v: string) => void;
  disabled?: boolean;
}) {
  return (
    <select
      className='ide-select'
      value={value}
      disabled={disabled}
      onChange={(e) => onChange(e.target.value)}
      style={{width: 'auto', padding: '3px 8px', fontSize: 12}}
    >
      {options.map((o) => (
        <option key={o} value={o}>
          {o}
        </option>
      ))}
    </select>
  );
}

const USER_TIERS = ['FREE', 'PRO', 'TEAM', 'ENTERPRISE'];
const USER_ROLES = ['USER', 'FINANCE', 'SUPER_ADMIN'];
const USER_STATUSES = ['ACTIVE', 'SUSPENDED'];
const ORG_TIERS = ['FREE', 'PRO', 'TEAM', 'ENTERPRISE'];
const ORG_STATUSES = ['ACTIVE', 'SUSPENDED'];

type AdminSection = 'overview' | 'users' | 'orgs' | 'analytics' | 'ops' | 'topups';

export function IdeAdmin() {
  const user = useAuthStore((s) => s.user);
  const [section, setSection] = useState<AdminSection>('overview');
  const [toast, setToast] = useState<{kind: 'ok' | 'bad'; text: string} | null>(null);

  // shared notice helpers passed down to sections
  const ok = (text: string) => setToast({kind: 'ok', text});
  const bad = (e: unknown) =>
    setToast({kind: 'bad', text: e instanceof Error ? e.message : 'Action failed.'});

  const TABS: Array<[AdminSection, string]> = [
    ['overview', 'Overview'],
    ['users', 'Users'],
    ['orgs', 'Orgs'],
    ['analytics', 'Analytics'],
    ['ops', 'Ops'],
    ['topups', 'Top-ups'],
  ];

  return (
    <div className='ide-pane'>
      <div className='ide-h1'>Admin</div>
      <div className='ide-sub'>SUPER_ADMIN · every action here is logged.</div>

      <div className='ide-rtabs' style={{marginBottom: 16}}>
        {TABS.map(([key, label]) => (
          <button
            key={key}
            type='button'
            className={`ide-rtab${section === key ? ' active' : ''}`}
            onClick={() => setSection(key)}
          >
            {label}
          </button>
        ))}
      </div>

      {toast && (
        <div
          className='ide-card'
          style={{
            marginBottom: 12,
            color: toast.kind === 'ok' ? 'var(--ide-green)' : 'var(--ide-red)',
            borderColor: toast.kind === 'ok' ? 'var(--ide-green)' : 'var(--ide-red)',
            fontSize: 12.5,
          }}
        >
          {toast.text}
        </div>
      )}

      {section === 'overview' && <AdminOverview />}
      {section === 'users' && <AdminUsers ok={ok} bad={bad} />}
      {section === 'orgs' && <AdminOrgs ok={ok} bad={bad} />}
      {section === 'analytics' && <AdminAnalytics orgId={user?.orgId ?? null} />}
      {section === 'ops' && <AdminOps ok={ok} bad={bad} />}
      {section === 'topups' && <AdminTopups ok={ok} bad={bad} />}
    </div>
  );
}

// ───────────────────────── Overview (funnel + tiers + flags) ─────────────────────────
function AdminOverview() {
  const [funnel, setFunnel] = useState<api.ActivationFunnelReport | null>(null);
  const [flags, setFlags] = useState<api.FeatureFlagDto[]>([]);
  const [tiers, setTiers] = useState<api.TierLifecycleDto[]>([]);

  useEffect(() => {
    api
      .getActivationFunnel(30)
      .then(setFunnel)
      // keep the grid visible on error rather than spinning forever
      .catch(() => setFunnel({windowDays: 30, windowStart: '', stages: {}}));
    api
      .adminListFeatureFlags()
      .then(setFlags)
      .catch(() => {});
    api
      .fetchTiersLifecycle()
      .then(setTiers)
      .catch(() => {});
  }, []);

  const stage = (k: string) => {
    const s = funnel?.stages?.[k];
    return s ? String(s.windowed) : '—';
  };

  if (!funnel) return <IdeLoader label='Loading admin…' />;
  return (
    <>
      <div className='ide-h2'>Platform · last {funnel.windowDays}d</div>
      <div style={{display: 'flex', gap: 10, flexWrap: 'wrap'}}>
        {FUNNEL_STAGES.map(([label, key]) => (
          <div key={key} className='ide-card' style={{flex: 1, minWidth: 120}}>
            <div style={{fontSize: 11, color: 'var(--ide-fg-muted)'}}>{label}</div>
            <div
              style={{
                fontSize: 22,
                fontWeight: 700,
                color: 'var(--ide-fg-bright)',
                marginTop: 4,
              }}
            >
              {stage(key)}
            </div>
          </div>
        ))}
      </div>

      <div className='ide-h2'>Tiers</div>
      <AdminList>
        {tiers.map((t, i) => (
          <div key={t.tier} style={rowStyle(i === tiers.length - 1)}>
            <span style={{flex: 1, fontWeight: 500}}>{t.displayName || t.tier}</span>
            <span style={{fontSize: 12, color: 'var(--ide-fg-muted)'}}>
              {t.activeSubscribers} subs
            </span>
            <IdeBadge kind={t.state === 'LISTED' ? 'ok' : t.state === 'HIDDEN' ? 'warn' : 'bad'}>
              {t.state}
            </IdeBadge>
          </div>
        ))}
      </AdminList>

      <div className='ide-h2'>Feature flags · {flags.length}</div>
      <AdminList>
        {flags.map((f, i) => (
          <div key={f.id} style={{...rowStyle(i === flags.length - 1), fontSize: 12.5}}>
            <span className='ide-mono' style={{flex: 1}}>
              {f.flagKey}
            </span>
            <span style={{color: 'var(--ide-fg-muted)'}}>{f.scope}</span>
            <IdeBadge kind={f.flagValue === 'true' ? 'ok' : 'warn'}>{f.flagValue}</IdeBadge>
          </div>
        ))}
      </AdminList>
    </>
  );
}

// ───────────────────────── Users ─────────────────────────
function AdminUsers({ok, bad}: {ok: (s: string) => void; bad: (e: unknown) => void}) {
  const [users, setUsers] = useState<api.AuthUser[] | null>(null);

  const load = () =>
    api
      .adminListUsers()
      .then(setUsers)
      .catch(() => setUsers([]));
  useEffect(() => {
    load();
  }, []);

  // Optimistically merge a server-returned user back into the list.
  const patch = (u: api.AuthUser) =>
    setUsers((prev) => (prev ? prev.map((x) => (x.id === u.id ? u : x)) : prev));

  function setTier(u: api.AuthUser, tier: string) {
    api
      .adminUpdateUserTier(u.id, tier)
      .then((r) => {
        patch(r);
        ok(`${u.email} → ${tier}`);
      })
      .catch(bad);
  }
  function setRole(u: api.AuthUser, role: string) {
    api
      .adminUpdateUserRole(u.id, role)
      .then((r) => {
        patch(r);
        ok(`${u.email} role → ${role}`);
      })
      .catch(bad);
  }
  function setStatus(u: api.AuthUser, status: string) {
    if (status === 'SUSPENDED' && !window.confirm(`Suspend ${u.email}?`)) return;
    api
      .adminUpdateUserStatus(u.id, status)
      .then((r) => {
        patch(r);
        ok(`${u.email} → ${status}`);
      })
      .catch(bad);
  }
  function grant(u: api.AuthUser) {
    const raw = window.prompt(`Grant how many runs to ${u.email}?`, '10');
    if (!raw) return;
    const qty = Number(raw);
    if (!Number.isFinite(qty) || qty <= 0) return;
    api
      .adminGrantUserRuns(u.id, qty, null, 'admin grant')
      .then(() => ok(`Granted ${qty} runs to ${u.email}`))
      .catch(bad);
  }

  if (!users) return <IdeLoader label='Loading users…' />;
  return (
    <>
      <div className='ide-h2'>Users · {users.length}</div>
      <AdminList>
        {users.map((u, i) => (
          <div key={u.id} style={{...rowStyle(i === users.length - 1), flexWrap: 'wrap'}}>
            <div style={{flex: 1, minWidth: 180}}>
              <div style={{fontWeight: 500}}>
                {u.firstName} {u.lastName}
              </div>
              <div style={{fontSize: 11.5, color: 'var(--ide-fg-muted)'}} className='ide-mono'>
                {u.email}
              </div>
            </div>
            <MiniSelect value={u.tier} options={USER_TIERS} onChange={(v) => setTier(u, v)} />
            <MiniSelect value={u.globalRole} options={USER_ROLES} onChange={(v) => setRole(u, v)} />
            <MiniSelect
              value={USER_STATUSES.includes(u.status) ? u.status : 'ACTIVE'}
              options={USER_STATUSES}
              onChange={(v) => setStatus(u, v)}
            />
            <button
              className='ide-btn ghost'
              style={{padding: '3px 9px', fontSize: 12}}
              onClick={() => grant(u)}
            >
              + Runs
            </button>
          </div>
        ))}
      </AdminList>
    </>
  );
}

// ───────────────────────── Orgs ─────────────────────────
function AdminOrgs({ok, bad}: {ok: (s: string) => void; bad: (e: unknown) => void}) {
  const [orgs, setOrgs] = useState<api.OrgDto[] | null>(null);

  const load = () =>
    api
      .adminListOrgs()
      .then(setOrgs)
      .catch(() => setOrgs([]));
  useEffect(() => {
    load();
  }, []);

  const patch = (o: api.OrgDto) =>
    setOrgs((prev) => (prev ? prev.map((x) => (x.id === o.id ? o : x)) : prev));

  function setTier(o: api.OrgDto, tier: string) {
    api
      .adminUpdateOrgTier(o.id, tier)
      .then((r) => {
        patch(r);
        ok(`${o.name} → ${tier}`);
      })
      .catch(bad);
  }
  function setStatus(o: api.OrgDto, status: string) {
    if (status === 'SUSPENDED' && !window.confirm(`Suspend org ${o.name}?`)) return;
    api
      .adminUpdateOrgStatus(o.id, status)
      .then((r) => {
        patch(r);
        ok(`${o.name} → ${status}`);
      })
      .catch(bad);
  }
  function setLimit(o: api.OrgDto) {
    const raw = window.prompt(
      `Monthly run-limit override for ${o.name} (blank = clear, use tier default):`,
      o.monthlyRunLimitOverride != null ? String(o.monthlyRunLimitOverride) : '',
    );
    if (raw === null) return;
    const limit = raw.trim() === '' ? null : Number(raw);
    if (limit !== null && (!Number.isFinite(limit) || limit < 0)) return;
    api
      .adminUpdateOrgRunLimit(o.id, limit)
      .then((r) => {
        patch(r);
        ok(`${o.name} limit → ${limit ?? 'default'}`);
      })
      .catch(bad);
  }
  function grant(o: api.OrgDto) {
    const raw = window.prompt(`Grant how many runs to ${o.name}?`, '10');
    if (!raw) return;
    const qty = Number(raw);
    if (!Number.isFinite(qty) || qty <= 0) return;
    api
      .adminGrantOrgRuns(o.id, qty, null, 'admin grant')
      .then(() => ok(`Granted ${qty} runs to ${o.name}`))
      .catch(bad);
  }

  if (!orgs) return <IdeLoader label='Loading orgs…' />;
  return (
    <>
      <div className='ide-h2'>Orgs · {orgs.length}</div>
      <AdminList>
        {orgs.map((o, i) => (
          <div key={o.id} style={{...rowStyle(i === orgs.length - 1), flexWrap: 'wrap'}}>
            <div style={{flex: 1, minWidth: 160}}>
              <div style={{fontWeight: 500}}>{o.name}</div>
              <div style={{fontSize: 11.5, color: 'var(--ide-fg-muted)'}}>
                {o.monthlyRunCount} runs ·{' '}
                {o.monthlyRunLimitOverride != null
                  ? `${o.monthlyRunLimitOverride} cap`
                  : 'tier cap'}
              </div>
            </div>
            <MiniSelect value={o.tier} options={ORG_TIERS} onChange={(v) => setTier(o, v)} />
            <MiniSelect
              value={ORG_STATUSES.includes(o.status) ? o.status : 'ACTIVE'}
              options={ORG_STATUSES}
              onChange={(v) => setStatus(o, v)}
            />
            <button
              className='ide-btn ghost'
              style={{padding: '3px 9px', fontSize: 12}}
              onClick={() => setLimit(o)}
            >
              Limit
            </button>
            <button
              className='ide-btn ghost'
              style={{padding: '3px 9px', fontSize: 12}}
              onClick={() => grant(o)}
            >
              + Runs
            </button>
          </div>
        ))}
      </AdminList>
    </>
  );
}

// ───────────────────────── Analytics ─────────────────────────
// The analytics endpoints are entity-scoped (entityType + entityId). For the
// admin's own org we surface usage + billing; member rollup is org-only.
function AdminAnalytics({orgId}: {orgId: string | null}) {
  const [usage, setUsage] = useState<api.UsageMetrics | null>(null);
  const [billing, setBilling] = useState<api.BillingOverviewData | null>(null);
  const [members, setMembers] = useState<api.MemberUsage[] | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (!orgId) {
      setLoading(false);
      return;
    }
    Promise.allSettled([
      api.getAnalyticsUsage('org', orgId, 30),
      api.getAnalyticsBilling('org', orgId),
      api.getAnalyticsMembers(orgId, 30),
    ])
      .then(([u, b, m]) => {
        if (u.status === 'fulfilled') setUsage(u.value);
        if (b.status === 'fulfilled') setBilling(b.value);
        if (m.status === 'fulfilled') setMembers(m.value);
      })
      .finally(() => setLoading(false));
  }, [orgId]);

  if (!orgId) return <div className='ide-empty'>No org context — analytics are org-scoped.</div>;
  if (loading) return <IdeLoader label='Loading analytics…' />;

  const costEntries = usage ? Object.entries(usage.costByModel || {}) : [];
  return (
    <>
      <div className='ide-sub' style={{marginTop: -6}}>
        Scoped to your org · last 30d.
      </div>

      {usage && (
        <>
          <div className='ide-h2'>Usage</div>
          <div style={{display: 'flex', gap: 10, flexWrap: 'wrap'}}>
            {[
              ['Total runs', String(usage.totalRuns)],
              ['Successful', String(usage.successfulRuns)],
              ['Failed', String(usage.failedRuns)],
              ['Partial', String(usage.partialRuns)],
              ['Total cost', `$${(usage.totalCost ?? 0).toFixed(2)}`],
            ].map(([label, value]) => (
              <div key={label} className='ide-card' style={{flex: 1, minWidth: 110}}>
                <div style={{fontSize: 11, color: 'var(--ide-fg-muted)'}}>{label}</div>
                <div
                  style={{
                    fontSize: 20,
                    fontWeight: 700,
                    color: 'var(--ide-fg-bright)',
                    marginTop: 4,
                  }}
                >
                  {value}
                </div>
              </div>
            ))}
          </div>
        </>
      )}

      {costEntries.length > 0 && (
        <>
          <div className='ide-h2'>Cost by model</div>
          <AdminList>
            {costEntries.map(([model, cost], i) => (
              <div key={model} style={{...rowStyle(i === costEntries.length - 1), fontSize: 12.5}}>
                <span className='ide-mono' style={{flex: 1}}>
                  {model}
                </span>
                <span style={{color: 'var(--ide-fg-bright)'}}>${cost.toFixed(2)}</span>
              </div>
            ))}
          </AdminList>
        </>
      )}

      {billing && (
        <>
          <div className='ide-h2'>Billing</div>
          <div className='ide-card'>
            <div style={{display: 'flex', justifyContent: 'space-between', marginBottom: 6}}>
              <span style={{color: 'var(--ide-fg-muted)'}}>Plan</span>
              <span>{billing.currentPlan}</span>
            </div>
            <div style={{display: 'flex', justifyContent: 'space-between', marginBottom: 6}}>
              <span style={{color: 'var(--ide-fg-muted)'}}>Status</span>
              <span>{billing.subscriptionStatus}</span>
            </div>
            <div style={{display: 'flex', justifyContent: 'space-between', marginBottom: 6}}>
              <span style={{color: 'var(--ide-fg-muted)'}}>Period usage</span>
              <span>
                {billing.periodUsage} / {billing.runLimit}
              </span>
            </div>
            <div style={{display: 'flex', justifyContent: 'space-between'}}>
              <span style={{color: 'var(--ide-fg-muted)'}}>Top-up credits</span>
              <span>{billing.topupCredits}</span>
            </div>
          </div>
        </>
      )}

      {members && members.length > 0 && (
        <>
          <div className='ide-h2'>Members · {members.length}</div>
          <AdminList>
            {members.map((m, i) => (
              <div key={m.userId} style={{...rowStyle(i === members.length - 1), fontSize: 12.5}}>
                <span style={{flex: 1}}>
                  {m.firstName} {m.lastName}
                </span>
                <span style={{color: 'var(--ide-fg-muted)'}}>{m.runCount} runs</span>
                <span style={{color: 'var(--ide-fg-bright)'}}>
                  {Math.round((m.successRate ?? 0) * 100)}%
                </span>
              </div>
            ))}
          </AdminList>
        </>
      )}
    </>
  );
}

// ───────────────────────── Ops ─────────────────────────
function AdminOps({ok, bad}: {ok: (s: string) => void; bad: (e: unknown) => void}) {
  const [busy, setBusy] = useState<string | null>(null);

  function halt() {
    if (
      !window.confirm(
        'Halt ALL active runs platform-wide? This cancels in-flight pipelines for every org.',
      )
    )
      return;
    setBusy('halt');
    api
      .haltAllActiveRuns('admin emergency halt')
      .then((r) => ok(`Halted ${r.haltedCount} run(s).`))
      .catch(bad)
      .finally(() => setBusy(null));
  }
  function verifyChain() {
    setBusy('chain');
    api
      .verifyAuditChain()
      .then((r) =>
        r.clean
          ? ok(`Audit chain clean · ${r.verified}/${r.totalChained} verified.`)
          : bad(
              new Error(
                `Chain BREAK at seq ${r.firstBreak?.chainSeq}: ${r.firstBreak?.reason ?? 'unknown'}`,
              ),
            ),
      )
      .catch(bad)
      .finally(() => setBusy(null));
  }
  async function reconcile() {
    if (!window.confirm('Reconcile orphan runs — mark stale non-terminal runs FAILED?')) return;
    setBusy('reconcile');
    try {
      const r = await fetch('/api/admin/executions/reconcile', {
        method: 'POST',
        headers: {Authorization: `Bearer ${localStorage.getItem('access_token') ?? ''}`},
      });
      if (!r.ok) throw new Error(`Reconcile failed (${r.status}).`);
      ok('Reconcile dispatched.');
    } catch (e: unknown) {
      bad(e);
    } finally {
      setBusy(null);
    }
  }
  function usageReport(period: 'WEEKLY' | 'MONTHLY') {
    setBusy('report');
    api
      .adminTriggerUsageReport(period)
      .then((r) => ok(`${period} usage report sent (${r.emailsSent} email(s)).`))
      .catch(bad)
      .finally(() => setBusy(null));
  }

  function OpRow({
    title,
    desc,
    danger,
    busyKey,
    action,
    label,
  }: {
    title: string;
    desc: string;
    danger?: boolean;
    busyKey: string;
    action: () => void;
    label: string;
  }) {
    return (
      <div style={{...ROW_BASE, borderBottom: '1px solid var(--ide-border)'}}>
        <div style={{flex: 1}}>
          <div style={{fontWeight: 500, color: danger ? 'var(--ide-red)' : undefined}}>{title}</div>
          <div style={{fontSize: 11.5, color: 'var(--ide-fg-muted)'}}>{desc}</div>
        </div>
        <button
          className='ide-btn ghost'
          style={danger ? {color: 'var(--ide-red)', borderColor: 'var(--ide-red)'} : undefined}
          disabled={busy === busyKey}
          onClick={action}
        >
          {busy === busyKey ? '…' : label}
        </button>
      </div>
    );
  }

  return (
    <>
      <div className='ide-h2'>Operations</div>
      <AdminList>
        <OpRow
          title='Halt all active runs'
          desc='Emergency stop — cancels every in-flight pipeline platform-wide.'
          danger
          busyKey='halt'
          action={halt}
          label='Halt all'
        />
        <OpRow
          title='Verify audit chain'
          desc='Recompute the activity_audit hash-chain and report the first break.'
          busyKey='chain'
          action={verifyChain}
          label='Verify'
        />
        <OpRow
          title='Reconcile orphan runs'
          desc='Mark stale non-terminal ExecutionRuns as FAILED.'
          busyKey='reconcile'
          action={reconcile}
          label='Reconcile'
        />
        <div style={{...ROW_BASE, borderBottom: 'none'}}>
          <div style={{flex: 1}}>
            <div style={{fontWeight: 500}}>Trigger usage report</div>
            <div style={{fontSize: 11.5, color: 'var(--ide-fg-muted)'}}>
              Email the periodic usage digest to org admins.
            </div>
          </div>
          <button
            className='ide-btn ghost'
            disabled={busy === 'report'}
            onClick={() => usageReport('WEEKLY')}
          >
            Weekly
          </button>
          <button
            className='ide-btn ghost'
            disabled={busy === 'report'}
            onClick={() => usageReport('MONTHLY')}
          >
            Monthly
          </button>
        </div>
      </AdminList>
    </>
  );
}

// ───────────────────────── Top-up packages ─────────────────────────
function AdminTopups({ok, bad}: {ok: (s: string) => void; bad: (e: unknown) => void}) {
  const [pkgs, setPkgs] = useState<api.TopupPackageAdmin[] | null>(null);

  const load = () =>
    api
      .adminListTopupPackages()
      .then(setPkgs)
      .catch(() => setPkgs([]));
  useEffect(() => {
    load();
  }, []);

  function create() {
    const name = window.prompt('Package name?');
    if (!name) return;
    const runsRaw = window.prompt('Run count?', '50');
    const inrRaw = window.prompt('Amount (INR)?', '500');
    const runCount = Number(runsRaw);
    const amountInr = Number(inrRaw);
    if (!Number.isFinite(runCount) || runCount <= 0 || !Number.isFinite(amountInr)) return;
    api
      .adminCreateTopupPackage({name, runCount, amountInr})
      .then(() => {
        ok(`Created ${name}`);
        load();
      })
      .catch(bad);
  }
  function toggleActive(p: api.TopupPackageAdmin) {
    api
      .adminUpdateTopupPackage(p.id, {active: !p.active})
      .then(() => {
        ok(`${p.name} ${!p.active ? 'activated' : 'deactivated'}`);
        load();
      })
      .catch(bad);
  }
  function remove(p: api.TopupPackageAdmin) {
    if (!window.confirm(`Delete top-up package "${p.name}"?`)) return;
    api
      .adminDeleteTopupPackage(p.id)
      .then(() => {
        ok(`Deleted ${p.name}`);
        load();
      })
      .catch(bad);
  }

  if (!pkgs) return <IdeLoader label='Loading top-up packages…' />;
  return (
    <>
      <div
        className='ide-h2'
        style={{display: 'flex', alignItems: 'center', justifyContent: 'space-between'}}
      >
        <span>Top-up packages · {pkgs.length}</span>
        <button className='ide-btn' style={{padding: '4px 12px', fontSize: 12}} onClick={create}>
          + New
        </button>
      </div>
      {pkgs.length === 0 ? (
        <div className='ide-empty'>No top-up packages.</div>
      ) : (
        <AdminList>
          {pkgs.map((p, i) => (
            <div key={p.id} style={{...rowStyle(i === pkgs.length - 1), flexWrap: 'wrap'}}>
              <div style={{flex: 1, minWidth: 150}}>
                <div style={{fontWeight: 500}}>{p.name}</div>
                <div style={{fontSize: 11.5, color: 'var(--ide-fg-muted)'}}>
                  {p.runCount} runs · ₹{p.amountInr} / ${p.amountUsd}
                </div>
              </div>
              <IdeBadge kind={p.active ? 'ok' : 'warn'}>{p.active ? 'Active' : 'Off'}</IdeBadge>
              <button
                className='ide-btn ghost'
                style={{padding: '3px 9px', fontSize: 12}}
                onClick={() => toggleActive(p)}
              >
                {p.active ? 'Disable' : 'Enable'}
              </button>
              <button
                className='ide-btn ghost'
                style={{
                  padding: '3px 9px',
                  fontSize: 12,
                  color: 'var(--ide-red)',
                  borderColor: 'var(--ide-red)',
                }}
                onClick={() => remove(p)}
              >
                Delete
              </button>
            </div>
          ))}
        </AdminList>
      )}
    </>
  );
}
