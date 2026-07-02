import {useEffect, useState} from 'react';
import {useNavigate} from 'react-router-dom';
import {TopBar, SectionHeader} from '@/signal/components/Chrome';
import {
  Feed,
  Row,
  Card,
  Button,
  Chip,
  StatusPill,
  TabsStrip,
  KvList,
} from '@/signal/components/Primitives';
import {LoadingState} from '@/signal/components/Loaders';
import {ConfirmModal, PromptModal} from '@/signal/components/Modal';
import {Bell, Settings, Plus} from '@/signal/icons/icons';
import {useAuthStore, selectIsSuperAdmin} from '@/stores/useAuthStore';
import * as api from '@/lib/api';
import {
  adminWriteConfirmation,
  type AdminWriteChange,
  type AdminWriteConfirmation,
} from './adminSafety';

// Admin (super-admin)
// Canonical activation-funnel stage keys (uppercase + `.windowed`), aligned
// with /admin/activation-funnel and the IDE chrome's IdeAdmin.
const ADMIN_FUNNEL: Array<[string, string]> = [
  ['Signups', 'SIGNUP'],
  ['Projects', 'PROJECT_CREATED'],
  ['Specs', 'SPEC_SUBMITTED'],
  ['Plans approved', 'PLAN_APPROVED'],
  ['Runs complete', 'RUN_COMPLETE'],
  ['PRs merged', 'PR_MERGED'],
];
type AdminTab = 'overview' | 'users' | 'orgs' | 'analytics' | 'ops' | 'topups';

export function AdminPage() {
  const isSuperAdmin = useAuthStore(selectIsSuperAdmin);
  const user = useAuthStore((s) => s.user);
  const navigate = useNavigate();
  const [tab, setTab] = useState<AdminTab>('overview');
  const [toast, setToast] = useState<{kind: 'ok' | 'bad'; text: string} | null>(null);

  useEffect(() => {
    if (!isSuperAdmin) navigate('/compose', {replace: true});
  }, [isSuperAdmin, navigate]);

  const ok = (text: string) => setToast({kind: 'ok', text});
  const bad = (e: unknown) =>
    setToast({kind: 'bad', text: e instanceof Error ? e.message : 'Action failed.'});

  if (!isSuperAdmin) return null;

  const tabs: Array<[AdminTab, string]> = [
    ['overview', 'Overview'],
    ['users', 'Users'],
    ['orgs', 'Orgs'],
    ['analytics', 'Analytics'],
    ['ops', 'Ops'],
    ['topups', 'Top-ups'],
  ];

  return (
    <>
      <TopBar title='Admin' sub='SUPER_ADMIN · platform-wide' back backTo='/more' />
      <Feed>
        <Card style={{background: 'var(--signal-coral-soft)', borderColor: 'transparent'}}>
          <div
            style={{
              fontSize: 11,
              fontWeight: 700,
              color: 'var(--signal-coral-text)',
              letterSpacing: '0.14em',
              textTransform: 'uppercase',
              marginBottom: 6,
            }}
          >
            Restricted area
          </div>
          <div style={{fontSize: 14, color: 'var(--signal-coral-text)', lineHeight: 1.5}}>
            Every action here is logged. Be careful.
          </div>
        </Card>

        <TabsStrip
          items={tabs.map(([key, label]) => ({
            label,
            active: tab === key,
            onClick: () => setTab(key),
          }))}
        />

        {toast && (
          <div
            style={{
              background:
                toast.kind === 'ok' ? 'var(--signal-mint-soft)' : 'var(--signal-coral-soft)',
              color:
                toast.kind === 'ok'
                  ? 'var(--signal-mint-text-on-light)'
                  : 'var(--signal-coral-text)',
              border: `1px solid ${toast.kind === 'ok' ? 'var(--signal-mint)' : 'var(--signal-coral)'}`,
              borderRadius: 10,
              padding: '10px 14px',
              marginTop: 12,
              fontSize: 13,
            }}
          >
            {toast.text}
          </div>
        )}

        {tab === 'overview' && <AdminOverview />}
        {tab === 'users' && <AdminUsersSection ok={ok} bad={bad} />}
        {tab === 'orgs' && <AdminOrgsSection ok={ok} bad={bad} />}
        {tab === 'analytics' && <AdminAnalyticsSection orgId={user?.orgId ?? null} />}
        {tab === 'ops' && <AdminOpsSection ok={ok} bad={bad} />}
        {tab === 'topups' && <AdminTopupsSection ok={ok} bad={bad} />}
      </Feed>
    </>
  );
}

// Admin · Overview (funnel + tiers + flags)
function AdminOverview() {
  const [tiers, setTiers] = useState<api.TierLifecycleDto[]>([]);
  const [orgs, setOrgs] = useState<api.OrgDto[]>([]);
  const [flags, setFlags] = useState<api.FeatureFlagDto[]>([]);
  const [funnel, setFunnel] = useState<api.ActivationFunnelReport | null>(null);

  useEffect(() => {
    Promise.allSettled([
      api.fetchTiersLifecycle(),
      api.adminListOrgs(),
      api.adminListFeatureFlags(),
      api.getActivationFunnel(30),
    ]).then(([t, o, f, fn]) => {
      if (t.status === 'fulfilled') setTiers(t.value);
      if (o.status === 'fulfilled') setOrgs(o.value);
      if (f.status === 'fulfilled') setFlags(f.value);
      if (fn.status === 'fulfilled') setFunnel(fn.value);
    });
  }, []);

  const stage = (k: string) => {
    const s = funnel?.stages?.[k];
    return s ? String(s.windowed) : '—';
  };

  return (
    <>
      <SectionHeader>Platform snapshot</SectionHeader>
      <div className='signal-quick-grid'>
        <div className='signal-quick-card mint'>
          <div className='icon-bg'>
            <Plus size={16} />
          </div>
          <h4>Active orgs</h4>
          <div
            className='count'
            style={{color: 'var(--signal-ink)', fontSize: 17, fontWeight: 600}}
          >
            {orgs.length}
          </div>
        </div>
        <div className='signal-quick-card signal'>
          <div className='icon-bg'>
            <Bell size={16} />
          </div>
          <h4>Signups · 30d</h4>
          <div
            className='count'
            style={{color: 'var(--signal-ink)', fontSize: 17, fontWeight: 600}}
          >
            {stage('SIGNUP')}
          </div>
        </div>
        <div className='signal-quick-card coral'>
          <div className='icon-bg'>
            <Settings size={16} />
          </div>
          <h4>Runs · 30d</h4>
          <div
            className='count'
            style={{color: 'var(--signal-ink)', fontSize: 17, fontWeight: 600}}
          >
            {stage('RUN_COMPLETE')}
          </div>
        </div>
      </div>

      <SectionHeader>Activation funnel · 30d</SectionHeader>
      <Card>
        <KvList pairs={ADMIN_FUNNEL.map(([label, key]) => ({k: label, v: stage(key)}))} />
      </Card>

      <SectionHeader>Tier lifecycle</SectionHeader>
      {tiers.length === 0 ? (
        <LoadingState label='Loading tiers…' />
      ) : (
        tiers.map((t) => (
          <Row
            key={t.tier}
            dot={t.state === 'LISTED' ? 'mint' : t.state === 'HIDDEN' ? 'signal' : 'coral'}
            title={t.displayName || t.tier}
            meta={
              <span>
                {t.state} · {t.activeSubscribers} subs
              </span>
            }
            trailing={
              <StatusPill
                kind={t.state === 'LISTED' ? 'shipped' : t.state === 'HIDDEN' ? 'needs' : 'failed'}
              >
                {t.state}
              </StatusPill>
            }
          />
        ))
      )}

      <SectionHeader>Feature flags · {flags.length}</SectionHeader>
      {flags.length === 0 ? (
        <LoadingState label='Loading flags…' />
      ) : (
        <Card>
          <KvList
            pairs={flags.slice(0, 14).map((f) => ({
              k: f.flagKey,
              v: (
                <span>
                  {f.flagValue}{' '}
                  <span style={{color: 'var(--signal-text-mute)', fontSize: 11}}>· {f.scope}</span>
                </span>
              ),
            }))}
          />
        </Card>
      )}
    </>
  );
}

const ADMIN_USER_TIERS = ['FREE', 'PRO', 'TEAM', 'ENTERPRISE'];
const ADMIN_USER_ROLES = ['USER', 'FINANCE', 'SUPER_ADMIN'];
const ADMIN_USER_STATUSES = ['ACTIVE', 'SUSPENDED'];
const ADMIN_ORG_TIERS = ['FREE', 'PRO', 'TEAM', 'ENTERPRISE'];
const ADMIN_ORG_STATUSES = ['ACTIVE', 'SUSPENDED'];

type SectionProps = {ok: (s: string) => void; bad: (e: unknown) => void};
type PendingAdminWrite = {
  copy: AdminWriteConfirmation;
  run: () => Promise<void>;
};

// Compact inline-edit select reused across the Users + Orgs tables.
function AdminMiniSelect({
  value,
  options,
  onChange,
}: {
  value: string;
  options: string[];
  onChange: (v: string) => void;
}) {
  return (
    <select
      value={value}
      onChange={(e) => onChange(e.target.value)}
      style={{
        background: 'var(--signal-surface-2, var(--signal-surface))',
        color: 'var(--signal-ink)',
        border: '1px solid var(--signal-line)',
        borderRadius: 8,
        padding: '4px 8px',
        fontSize: 12,
        fontFamily: 'inherit',
      }}
    >
      {options.map((o) => (
        <option key={o} value={o}>
          {o}
        </option>
      ))}
    </select>
  );
}

function AdminUsersSection({ok, bad}: SectionProps) {
  const [users, setUsers] = useState<api.AuthUser[] | null>(null);
  const [confirm, setConfirm] = useState<PendingAdminWrite | null>(null);
  const [grantFor, setGrantFor] = useState<api.AuthUser | null>(null);

  useEffect(() => {
    api
      .adminListUsers()
      .then(setUsers)
      .catch(() => setUsers([]));
  }, []);

  const patch = (u: api.AuthUser) =>
    setUsers((prev) => (prev ? prev.map((x) => (x.id === u.id ? u : x)) : prev));

  const setTier = (u: api.AuthUser, tier: string) =>
    api
      .adminUpdateUserTier(u.id, tier)
      .then((r) => {
        patch(r);
        ok(`${u.email} → ${tier}`);
      })
      .catch(bad);
  const setRole = (u: api.AuthUser, role: string) =>
    api
      .adminUpdateUserRole(u.id, role)
      .then((r) => {
        patch(r);
        ok(`${u.email} role → ${role}`);
      })
      .catch(bad);
  const applyStatus = (u: api.AuthUser, status: string) =>
    api
      .adminUpdateUserStatus(u.id, status)
      .then((r) => {
        patch(r);
        ok(`${u.email} → ${status}`);
      })
      .catch(bad);

  const confirmWrite = (change: AdminWriteChange, run: () => Promise<void>) =>
    setConfirm({copy: adminWriteConfirmation(change), run});

  if (!users) return <LoadingState label='Loading users…' />;
  return (
    <>
      <SectionHeader>Users · {users.length}</SectionHeader>
      {users.map((u) => (
        <Row
          key={u.id}
          dot={u.status === 'SUSPENDED' ? 'coral' : 'mint'}
          title={`${u.firstName} ${u.lastName}`}
          meta={<span style={{wordBreak: 'break-all'}}>{u.email}</span>}
          trailing={
            <div style={{display: 'flex', gap: 6, flexWrap: 'wrap', justifyContent: 'flex-end'}}>
              <AdminMiniSelect
                value={u.tier}
                options={ADMIN_USER_TIERS}
                onChange={(v) =>
                  confirmWrite({kind: 'user-tier', email: u.email, from: u.tier, to: v}, () =>
                    setTier(u, v),
                  )
                }
              />
              <AdminMiniSelect
                value={u.globalRole}
                options={ADMIN_USER_ROLES}
                onChange={(v) =>
                  confirmWrite({kind: 'user-role', email: u.email, from: u.globalRole, to: v}, () =>
                    setRole(u, v),
                  )
                }
              />
              <AdminMiniSelect
                value={ADMIN_USER_STATUSES.includes(u.status) ? u.status : 'ACTIVE'}
                options={ADMIN_USER_STATUSES}
                onChange={(v) =>
                  confirmWrite({kind: 'user-status', email: u.email, from: u.status, to: v}, () =>
                    applyStatus(u, v),
                  )
                }
              />
              <Button kind='ghost' sm onClick={() => setGrantFor(u)}>
                + Runs
              </Button>
            </div>
          }
        />
      ))}

      <ConfirmModal
        open={!!confirm}
        title={confirm?.copy.title ?? 'Confirm admin change'}
        body={confirm?.copy.body ?? ''}
        confirmLabel={confirm?.copy.confirmLabel ?? 'Confirm'}
        danger={confirm?.copy.danger}
        onConfirm={async () => {
          if (confirm) await confirm.run();
        }}
        onClose={() => setConfirm(null)}
      />
      <PromptModal
        open={!!grantFor}
        title='Grant runs'
        body={grantFor ? `Grant bonus runs to ${grantFor.email}.` : ''}
        fields={[
          {name: 'quantity', label: 'Quantity', placeholder: '10', required: true},
          {name: 'reason', label: 'Reason (optional)', placeholder: 'goodwill'},
        ]}
        submitLabel='Grant'
        // eslint-disable-next-line no-restricted-syntax -- PromptModal callback prop (modal owns submit lifecycle); result reported via ok()/bad() toast.
        onSubmit={async (v) => {
          const qty = Number(v.quantity);
          if (grantFor && Number.isFinite(qty) && qty > 0) {
            await api
              .adminGrantUserRuns(grantFor.id, qty, null, v.reason || 'admin grant')
              .then(() => ok(`Granted ${qty} runs to ${grantFor.email}`))
              .catch(bad);
          }
          setGrantFor(null);
        }}
        onClose={() => setGrantFor(null)}
      />
    </>
  );
}

function AdminOrgsSection({ok, bad}: SectionProps) {
  const [orgs, setOrgs] = useState<api.OrgDto[] | null>(null);
  const [confirm, setConfirm] = useState<PendingAdminWrite | null>(null);
  const [grantFor, setGrantFor] = useState<api.OrgDto | null>(null);
  const [limitFor, setLimitFor] = useState<api.OrgDto | null>(null);

  useEffect(() => {
    api
      .adminListOrgs()
      .then(setOrgs)
      .catch(() => setOrgs([]));
  }, []);

  const patch = (o: api.OrgDto) =>
    setOrgs((prev) => (prev ? prev.map((x) => (x.id === o.id ? o : x)) : prev));

  const setTier = (o: api.OrgDto, tier: string) =>
    api
      .adminUpdateOrgTier(o.id, tier)
      .then((r) => {
        patch(r);
        ok(`${o.name} → ${tier}`);
      })
      .catch(bad);
  const applyStatus = (o: api.OrgDto, status: string) =>
    api
      .adminUpdateOrgStatus(o.id, status)
      .then((r) => {
        patch(r);
        ok(`${o.name} → ${status}`);
      })
      .catch(bad);

  const confirmWrite = (change: AdminWriteChange, run: () => Promise<void>) =>
    setConfirm({copy: adminWriteConfirmation(change), run});

  if (!orgs) return <LoadingState label='Loading orgs…' />;
  return (
    <>
      <SectionHeader>Orgs · {orgs.length}</SectionHeader>
      {orgs.map((o) => (
        <Row
          key={o.id}
          dot={o.status === 'SUSPENDED' ? 'coral' : 'mint'}
          title={o.name}
          meta={
            <span>
              {o.monthlyRunCount} runs ·{' '}
              {o.monthlyRunLimitOverride != null ? `${o.monthlyRunLimitOverride} cap` : 'tier cap'}
            </span>
          }
          trailing={
            <div style={{display: 'flex', gap: 6, flexWrap: 'wrap', justifyContent: 'flex-end'}}>
              <AdminMiniSelect
                value={o.tier}
                options={ADMIN_ORG_TIERS}
                onChange={(v) =>
                  confirmWrite({kind: 'org-tier', name: o.name, from: o.tier, to: v}, () =>
                    setTier(o, v),
                  )
                }
              />
              <AdminMiniSelect
                value={ADMIN_ORG_STATUSES.includes(o.status) ? o.status : 'ACTIVE'}
                options={ADMIN_ORG_STATUSES}
                onChange={(v) =>
                  confirmWrite({kind: 'org-status', name: o.name, from: o.status, to: v}, () =>
                    applyStatus(o, v),
                  )
                }
              />
              <Button kind='ghost' sm onClick={() => setLimitFor(o)}>
                Limit
              </Button>
              <Button kind='ghost' sm onClick={() => setGrantFor(o)}>
                + Runs
              </Button>
            </div>
          }
        />
      ))}

      <ConfirmModal
        open={!!confirm}
        title={confirm?.copy.title ?? 'Confirm admin change'}
        body={confirm?.copy.body ?? ''}
        confirmLabel={confirm?.copy.confirmLabel ?? 'Confirm'}
        danger={confirm?.copy.danger}
        onConfirm={async () => {
          if (confirm) await confirm.run();
        }}
        onClose={() => setConfirm(null)}
      />
      <PromptModal
        open={!!limitFor}
        title='Run-limit override'
        body={
          limitFor
            ? `Monthly run-limit override for ${limitFor.name}. Leave blank to clear and use the tier default.`
            : ''
        }
        fields={[
          {
            name: 'limit',
            label: 'Monthly limit',
            placeholder: 'blank = tier default',
            defaultValue:
              limitFor?.monthlyRunLimitOverride != null
                ? String(limitFor.monthlyRunLimitOverride)
                : '',
          },
        ]}
        submitLabel='Save'
        // eslint-disable-next-line no-restricted-syntax -- PromptModal callback prop (modal owns submit lifecycle); result reported via ok()/bad() toast.
        onSubmit={async (v) => {
          if (limitFor) {
            const raw = v.limit.trim();
            const limit = raw === '' ? null : Number(raw);
            if (limit === null || (Number.isFinite(limit) && limit >= 0)) {
              await api
                .adminUpdateOrgRunLimit(limitFor.id, limit)
                .then((r) => {
                  patch(r);
                  ok(`${limitFor.name} limit → ${limit ?? 'default'}`);
                })
                .catch(bad);
            }
          }
          setLimitFor(null);
        }}
        onClose={() => setLimitFor(null)}
      />
      <PromptModal
        open={!!grantFor}
        title='Grant runs'
        body={grantFor ? `Grant bonus runs to ${grantFor.name}.` : ''}
        fields={[
          {name: 'quantity', label: 'Quantity', placeholder: '10', required: true},
          {name: 'reason', label: 'Reason (optional)', placeholder: 'goodwill'},
        ]}
        submitLabel='Grant'
        // eslint-disable-next-line no-restricted-syntax -- PromptModal callback prop (modal owns submit lifecycle); result reported via ok()/bad() toast.
        onSubmit={async (v) => {
          const qty = Number(v.quantity);
          if (grantFor && Number.isFinite(qty) && qty > 0) {
            await api
              .adminGrantOrgRuns(grantFor.id, qty, null, v.reason || 'admin grant')
              .then(() => ok(`Granted ${qty} runs to ${grantFor.name}`))
              .catch(bad);
          }
          setGrantFor(null);
        }}
        onClose={() => setGrantFor(null)}
      />
    </>
  );
}

// Admin · Analytics (org-scoped)
function AdminAnalyticsSection({orgId}: {orgId: string | null}) {
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

  if (!orgId) return <Card>No org context — analytics are org-scoped.</Card>;
  if (loading) return <LoadingState label='Loading analytics…' />;

  const costEntries = usage ? Object.entries(usage.costByModel || {}) : [];
  return (
    <>
      {usage && (
        <>
          <SectionHeader>Usage · your org · 30d</SectionHeader>
          <div className='signal-quick-grid'>
            {[
              ['Total runs', String(usage.totalRuns)],
              ['Successful', String(usage.successfulRuns)],
              ['Failed', String(usage.failedRuns)],
              ['Total cost', `$${(usage.totalCost ?? 0).toFixed(2)}`],
            ].map(([label, value]) => (
              <div key={label} className='signal-quick-card'>
                <h4>{label}</h4>
                <div
                  className='count'
                  style={{color: 'var(--signal-ink)', fontSize: 17, fontWeight: 600}}
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
          <SectionHeader>Cost by model</SectionHeader>
          <Card>
            <KvList pairs={costEntries.map(([m, c]) => ({k: m, v: `$${c.toFixed(2)}`}))} />
          </Card>
        </>
      )}

      {billing && (
        <>
          <SectionHeader>Billing</SectionHeader>
          <Card>
            <KvList
              pairs={[
                {k: 'Plan', v: billing.currentPlan},
                {k: 'Status', v: billing.subscriptionStatus},
                {k: 'Period usage', v: `${billing.periodUsage} / ${billing.runLimit}`},
                {k: 'Top-up credits', v: String(billing.topupCredits)},
                {k: 'Gateway', v: billing.gateway || '—'},
              ]}
            />
          </Card>
        </>
      )}

      {members && members.length > 0 && (
        <>
          <SectionHeader>Members · {members.length}</SectionHeader>
          {members.map((m) => (
            <Row
              key={m.userId}
              dot='ocean'
              title={`${m.firstName} ${m.lastName}`}
              meta={<span>{m.runCount} runs</span>}
              trailing={<Chip>{Math.round((m.successRate ?? 0) * 100)}%</Chip>}
            />
          ))}
        </>
      )}
    </>
  );
}

function AdminOpsSection({ok, bad}: SectionProps) {
  const [busy, setBusy] = useState<string | null>(null);
  const [confirmKind, setConfirmKind] = useState<'halt' | 'reconcile' | null>(null);

  const doHalt = async () => {
    setBusy('halt');
    await api
      .haltAllActiveRuns('admin emergency halt')
      .then((r) => ok(`Halted ${r.haltedCount} run(s).`))
      .catch(bad)
      .finally(() => setBusy(null));
  };
  const doReconcile = async () => {
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
  };
  const verifyChain = () => {
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
  };
  const usageReport = (period: 'WEEKLY' | 'MONTHLY') => {
    setBusy('report');
    api
      .adminTriggerUsageReport(period)
      .then((r) => ok(`${period} usage report sent (${r.emailsSent} email(s)).`))
      .catch(bad)
      .finally(() => setBusy(null));
  };

  return (
    <>
      <SectionHeader>Emergency</SectionHeader>
      <Row
        dot='coral'
        title='Halt all active runs'
        meta={<span>Cancels every in-flight pipeline platform-wide</span>}
        trailing={
          <Button kind='coral' sm disabled={busy === 'halt'} onClick={() => setConfirmKind('halt')}>
            {busy === 'halt' ? '…' : 'Halt all'}
          </Button>
        }
      />

      <SectionHeader>Operations</SectionHeader>
      <Row
        dot='ocean'
        title='Verify audit chain'
        meta={<span>Recompute the activity_audit hash-chain</span>}
        trailing={
          <Button kind='secondary' sm disabled={busy === 'chain'} onClick={verifyChain}>
            {busy === 'chain' ? '…' : 'Verify'}
          </Button>
        }
      />
      <Row
        dot='ocean'
        title='Reconcile orphan runs'
        meta={<span>Mark stale non-terminal runs FAILED</span>}
        trailing={
          <Button
            kind='secondary'
            sm
            disabled={busy === 'reconcile'}
            onClick={() => setConfirmKind('reconcile')}
          >
            {busy === 'reconcile' ? '…' : 'Reconcile'}
          </Button>
        }
      />
      <Row
        dot='ocean'
        title='Trigger usage report'
        meta={<span>Email the periodic usage digest to org admins</span>}
        trailing={
          <div style={{display: 'flex', gap: 6}}>
            <Button
              kind='ghost'
              sm
              disabled={busy === 'report'}
              onClick={() => usageReport('WEEKLY')}
            >
              Weekly
            </Button>
            <Button
              kind='ghost'
              sm
              disabled={busy === 'report'}
              onClick={() => usageReport('MONTHLY')}
            >
              Monthly
            </Button>
          </div>
        }
      />

      <ConfirmModal
        open={confirmKind === 'halt'}
        title='Halt all active runs'
        body='This cancels in-flight pipelines for every org platform-wide. Use only in an emergency.'
        confirmLabel='Halt all'
        danger
        busy={busy === 'halt'}
        onConfirm={doHalt}
        onClose={() => setConfirmKind(null)}
      />
      <ConfirmModal
        open={confirmKind === 'reconcile'}
        title='Reconcile orphan runs'
        body='Mark stale non-terminal ExecutionRuns as FAILED. Safe, but logged.'
        confirmLabel='Reconcile'
        busy={busy === 'reconcile'}
        onConfirm={doReconcile}
        onClose={() => setConfirmKind(null)}
      />
    </>
  );
}

function AdminTopupsSection({ok, bad}: SectionProps) {
  const [pkgs, setPkgs] = useState<api.TopupPackageAdmin[] | null>(null);
  const [creating, setCreating] = useState(false);
  const [deleteFor, setDeleteFor] = useState<api.TopupPackageAdmin | null>(null);

  const load = () =>
    api
      .adminListTopupPackages()
      .then(setPkgs)
      .catch(() => setPkgs([]));
  useEffect(() => {
    void load();
  }, []);

  const toggleActive = (p: api.TopupPackageAdmin) =>
    api
      .adminUpdateTopupPackage(p.id, {active: !p.active})
      .then(() => {
        ok(`${p.name} ${!p.active ? 'activated' : 'deactivated'}`);
        void load();
      })
      .catch(bad);

  if (!pkgs) return <LoadingState label='Loading top-up packages…' />;
  return (
    <>
      <SectionHeader
        action={
          <Button kind='secondary' sm onClick={() => setCreating(true)}>
            + New
          </Button>
        }
      >
        Top-up packages · {pkgs.length}
      </SectionHeader>
      {pkgs.length === 0 ? (
        <Card>No top-up packages.</Card>
      ) : (
        pkgs.map((p) => (
          <Row
            key={p.id}
            dot={p.active ? 'mint' : 'mute'}
            title={p.name}
            meta={
              <span>
                {p.runCount} runs · ₹{p.amountInr} / ${p.amountUsd}
              </span>
            }
            trailing={
              <div style={{display: 'flex', gap: 6}}>
                <StatusPill kind={p.active ? 'shipped' : 'needs'}>
                  {p.active ? 'Active' : 'Off'}
                </StatusPill>
                <Button kind='ghost' sm onClick={() => void toggleActive(p)}>
                  {p.active ? 'Disable' : 'Enable'}
                </Button>
                <Button kind='coral' sm onClick={() => setDeleteFor(p)}>
                  Delete
                </Button>
              </div>
            }
          />
        ))
      )}

      <PromptModal
        open={creating}
        title='New top-up package'
        fields={[
          {name: 'name', label: 'Name', placeholder: 'Starter pack', required: true},
          {name: 'runCount', label: 'Run count', placeholder: '50', required: true},
          {name: 'amountInr', label: 'Amount (INR)', placeholder: '500', required: true},
          {name: 'amountUsd', label: 'Amount (USD, optional)', placeholder: '6'},
        ]}
        submitLabel='Create'
        // eslint-disable-next-line no-restricted-syntax -- PromptModal callback prop (modal owns submit lifecycle); result reported via ok()/bad() toast.
        onSubmit={async (v) => {
          const runCount = Number(v.runCount);
          const amountInr = Number(v.amountInr);
          const amountUsd = v.amountUsd.trim() === '' ? undefined : Number(v.amountUsd);
          if (v.name && Number.isFinite(runCount) && runCount > 0 && Number.isFinite(amountInr)) {
            await api
              .adminCreateTopupPackage({name: v.name, runCount, amountInr, amountUsd})
              .then(() => {
                ok(`Created ${v.name}`);
                void load();
              })
              .catch(bad);
          }
          setCreating(false);
        }}
        onClose={() => setCreating(false)}
      />
      <ConfirmModal
        open={!!deleteFor}
        title='Delete package'
        body={`Delete top-up package "${deleteFor?.name}"? This cannot be undone.`}
        confirmLabel='Delete'
        danger
        onConfirm={async () => {
          if (deleteFor)
            await api
              .adminDeleteTopupPackage(deleteFor.id)
              .then(() => {
                ok(`Deleted ${deleteFor.name}`);
                void load();
              })
              .catch(bad);
        }}
        onClose={() => setDeleteFor(null)}
      />
    </>
  );
}
