import {ReactNode, useEffect, useMemo, useState} from 'react';
import {Link, useLocation, useNavigate, useParams} from 'react-router-dom';
import {TopBar, TabBar, SectionHeader} from '@/signal/components/Chrome';
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
import {ScoreCard, PrCard} from '@/signal/components/Compose';
import {verdictFor} from '@/signal/components/VerdictPill';
import {Bell, Search, Settings, Plus} from '@/signal/icons/icons';
import {useAuthStore, selectIsSuperAdmin, selectNormalizedTier} from '@/stores/useAuthStore';
import * as api from '@/lib/api';

type NotifFilter = 'all' | 'run' | 'team' | 'billing' | 'system';

export function NotificationsPage() {
  const [items, setItems] = useState<api.NotificationItem[] | null>(null);
  const [filter, setFilter] = useState<NotifFilter>('all');
  const [err, setErr] = useState('');

  async function refresh() {
    try {
      const page = await api.getNotifications(0, 50);
      setItems((page as unknown as {content?: api.NotificationItem[]}).content ?? []);
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Failed to load notifications.');
    }
  }

  useEffect(() => {
    void refresh();
  }, []);

  const filtered = useMemo(() => {
    if (!items) return [];
    if (filter === 'all') return items;
    return items.filter((n) => {
      const t = (
        (n as unknown as {category?: string; type?: string}).category ||
        (n as unknown as {type?: string}).type ||
        ''
      ).toLowerCase();
      if (filter === 'run') return t.includes('run') || t.includes('plan');
      if (filter === 'team') return t.includes('member') || t.includes('invite');
      if (filter === 'billing')
        return t.includes('billing') || t.includes('quota') || t.includes('cap');
      if (filter === 'system')
        return t.includes('system') || t.includes('flag') || t.includes('release');
      return true;
    });
  }, [items, filter]);

  const unreadCount = (items || []).filter(
    (n) =>
      !(n as unknown as {read?: boolean; readAt?: string}).read &&
      !(n as unknown as {readAt?: string}).readAt,
  ).length;

  async function readOne(id: string) {
    try {
      await api.markNotificationRead(id);
      setItems(
        (prev) =>
          prev?.map((n) => ((n as unknown as {id?: string}).id === id ? {...n, read: true} : n)) ||
          prev,
      );
    } catch {
      /* ignore */
    }
  }

  async function readAll() {
    try {
      await api.markAllNotificationsRead();
      setItems((prev) => prev?.map((n) => ({...n, read: true})) || prev);
    } catch {
      /* ignore */
    }
  }

  return (
    <>
      <TopBar
        title='Notifications'
        sub={`${unreadCount} unread`}
        back
        backTo='/home'
        actions={
          <Button kind='ghost' sm onClick={readAll} disabled={unreadCount === 0}>
            Mark all read
          </Button>
        }
      />
      <Feed>
        <TabsStrip
          items={[
            {
              label: `All · ${items?.length ?? 0}`,
              active: filter === 'all',
              onClick: () => setFilter('all'),
            },
            {label: 'Run', active: filter === 'run', onClick: () => setFilter('run')},
            {label: 'Team', active: filter === 'team', onClick: () => setFilter('team')},
            {label: 'Billing', active: filter === 'billing', onClick: () => setFilter('billing')},
            {label: 'System', active: filter === 'system', onClick: () => setFilter('system')},
          ]}
        />

        {err && (
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
            {err}
          </div>
        )}

        {items === null ? (
          <LoadingState label='Loading notifications…' />
        ) : filtered.length === 0 ? (
          <Card>
            <p style={{fontSize: 14, color: 'var(--signal-text-dim)', margin: 0, lineHeight: 1.5}}>
              No notifications yet.
            </p>
          </Card>
        ) : (
          filtered.map((n) => {
            const nAny = n as unknown as {
              id?: string;
              read?: boolean;
              readAt?: string;
              title?: string;
              description?: string;
              body?: string;
              link?: string | null;
              type?: string;
              category?: string;
              createdAt?: string;
              severity?: string;
            };
            const unread = !nAny.read && !nAny.readAt;
            const tone =
              nAny.severity === 'error' ? 'coral' : nAny.severity === 'warn' ? 'signal' : 'mint';
            // Deep-link target: prefer the backend-provided `link`, else
            // route by event type. Falls through to /home so clicks never
            // dead-end.
            const t = (nAny.type || nAny.category || '').toUpperCase();
            const linkTarget = nAny.link
              ? nAny.link
              : t.includes('PLAN_READY') || t.includes('RUN')
                ? '/runs'
                : t.includes('PR')
                  ? '/runs'
                  : t.includes('BILLING') || t.includes('QUOTA') || t.includes('CAP')
                    ? '/billing'
                    : t.includes('MEMBER') || t.includes('INVITE')
                      ? '/org-members'
                      : '/home';
            return (
              <NotifRow
                key={nAny.id}
                unread={unread}
                tone={tone}
                title={nAny.title || nAny.category || 'Notification'}
                desc={nAny.description || nAny.body || ''}
                ts={nAny.createdAt ? new Date(nAny.createdAt).toLocaleString() : ''}
                onClick={() => {
                  if (nAny.id) void readOne(nAny.id);
                  // Use window.location for absolute backend-provided links;
                  // otherwise SPA-navigate via history.pushState so React Router catches it.
                  if (linkTarget.startsWith('http')) {
                    window.location.href = linkTarget;
                  } else {
                    window.history.pushState({}, '', linkTarget);
                    window.dispatchEvent(new PopStateEvent('popstate'));
                  }
                }}
              />
            );
          })
        )}
      </Feed>
    </>
  );
}

function NotifRow({
  unread,
  tone,
  title,
  desc,
  ts,
  onClick,
}: {
  unread?: boolean;
  tone?: 'coral' | 'mint' | 'signal' | 'default';
  title: ReactNode;
  desc: ReactNode;
  ts: string;
  onClick?: () => void;
}) {
  return (
    <div
      className={`signal-notif ${unread ? 'unread' : ''}`}
      onClick={onClick}
      style={{cursor: onClick ? 'pointer' : undefined}}
    >
      <div className={`icon-circle ${tone && tone !== 'default' ? tone : ''}`}>
        <Bell size={16} />
      </div>
      <div className='body'>
        <div className='title'>{title}</div>
        <div className='desc'>{desc}</div>
      </div>
      <span className='ts'>{ts}</span>
    </div>
  );
}

interface SearchHit {
  id: string;
  kind: 'run' | 'project' | 'page';
  title: string;
  subtitle: string;
  to: string;
  dot?: 'mint' | 'coral' | 'signal' | 'lavender' | 'live';
}

export function SearchPage() {
  const [q, setQ] = useState('');
  const [runs, setRuns] = useState<api.RunSummary[]>([]);
  const [projects, setProjects] = useState<api.ProjectDto[]>([]);
  const navigate = useNavigate();

  useEffect(() => {
    Promise.allSettled([api.getRuns({page: 0, size: 50}), api.getProjects()]).then(([r, p]) => {
      if (r.status === 'fulfilled') setRuns(r.value.content);
      if (p.status === 'fulfilled') setProjects(p.value);
    });
  }, []);

  const hits: SearchHit[] = useMemo(() => {
    const needle = q.trim().toLowerCase();
    if (!needle) return [];
    const out: SearchHit[] = [];
    for (const r of runs) {
      const txt =
        `${(r as unknown as {userRequest?: string}).userRequest ?? ''} ${r.runId}`.toLowerCase();
      if (txt.includes(needle)) {
        const s = (r.status || '').toUpperCase();
        const isLive =
          s === 'CLASSIFYING' ||
          s === 'PLANNING' ||
          s === 'EXECUTING' ||
          s === 'AWAITING_APPROVAL' ||
          s === 'RUNNING';
        out.push({
          id: r.runId,
          kind: 'run',
          title:
            (r as unknown as {userRequest?: string}).userRequest?.slice(0, 80) ||
            `Run R-${r.runId.slice(0, 6)}`,
          subtitle: `R-${r.runId.slice(0, 6)} · ${s.toLowerCase()}`,
          to: isLive ? `/live/${r.runId}` : `/result/${r.runId}`,
          dot: isLive ? 'live' : 'mint',
        });
      }
    }
    for (const p of projects) {
      const txt = `${p.name} ${p.id}`.toLowerCase();
      if (txt.includes(needle)) {
        out.push({
          id: p.id,
          kind: 'project',
          title: p.name,
          subtitle: `${(p as unknown as {repoCount?: number}).repoCount ?? 0} repos · ${(p as unknown as {totalRuns?: number}).totalRuns ?? 0} runs`,
          to: `/projects/${p.id}`,
          dot: 'lavender',
        });
      }
    }
    return out.slice(0, 30);
  }, [q, runs, projects]);

  return (
    <>
      <div className='signal-topbar' style={{gap: 8}}>
        <Link to='/home' className='signal-back-btn' aria-label='Back'>
          <svg
            viewBox='0 0 24 24'
            style={{width: 16, height: 16}}
            fill='none'
            stroke='currentColor'
            strokeWidth={2.4}
            strokeLinecap='round'
          >
            <path d='M19 12H5M12 19l-7-7 7-7' />
          </svg>
        </Link>
        <div
          style={{
            flex: 1,
            display: 'flex',
            gap: 8,
            alignItems: 'center',
            background: 'var(--signal-card-elev)',
            borderRadius: 14,
            padding: '10px 14px',
          }}
        >
          <Search size={16} />
          <input
            type='search'
            value={q}
            onChange={(e) => setQ(e.target.value)}
            placeholder='Search runs, projects, files…'
            autoFocus
            onKeyDown={(e) => {
              if (e.key === 'Enter' && hits.length > 0) navigate(hits[0].to);
            }}
            style={{
              flex: 1,
              background: 'transparent',
              border: 0,
              outline: 0,
              fontSize: 14,
              color: 'var(--signal-ink)',
            }}
          />
        </div>
        <span
          style={{
            fontFamily: "'JetBrains Mono', monospace",
            fontSize: 10,
            color: 'var(--signal-text-mute)',
            background: 'var(--signal-card-elev)',
            padding: '3px 7px',
            borderRadius: 4,
          }}
        >
          ⌘K
        </span>
      </div>
      <Feed>
        {q.trim() === '' ? (
          <>
            <SectionHeader>Quick actions</SectionHeader>
            <Row
              to='/compose'
              dot='signal'
              title='New spec'
              meta={<span>Open the composer</span>}
              trailing={<Chip>⌘ ⏎</Chip>}
            />
            <Row
              to='/projects'
              dot='lavender'
              title='Browse projects'
              meta={<span>All your active projects</span>}
              trailing={<Chip>→</Chip>}
            />
            <Row
              to='/runs'
              dot='lavender'
              title='All runs'
              meta={<span>Filter and browse</span>}
              trailing={<Chip>→</Chip>}
            />
            <SectionHeader>Recent</SectionHeader>
            {runs.slice(0, 5).map((r) => (
              <Row
                key={r.runId}
                to={`/result/${r.runId}`}
                dot='mint'
                title={
                  (r as unknown as {userRequest?: string}).userRequest?.slice(0, 80) ||
                  `Run R-${r.runId.slice(0, 6)}`
                }
                meta={<span className='mono'>R-{r.runId.slice(0, 6)}</span>}
                trailing={<Chip>RUN</Chip>}
              />
            ))}
          </>
        ) : (
          <>
            <SectionHeader>
              {hits.length} result{hits.length === 1 ? '' : 's'} for "{q}"
            </SectionHeader>
            {hits.length === 0 ? (
              <Card>
                <p style={{fontSize: 13.5, color: 'var(--signal-text-dim)', margin: 0}}>
                  No matches. Try a different query.
                </p>
              </Card>
            ) : (
              hits.map((h) => (
                <Row
                  key={`${h.kind}-${h.id}`}
                  to={h.to}
                  dot={h.dot}
                  title={h.title}
                  meta={<span>{h.subtitle}</span>}
                  trailing={<Chip>{h.kind.toUpperCase()}</Chip>}
                />
              ))
            )}
          </>
        )}
      </Feed>
    </>
  );
}

export function MorePage() {
  const user = useAuthStore((s) => s.user);
  const isSuperAdmin = useAuthStore(selectIsSuperAdmin);
  const tier = useAuthStore(selectNormalizedTier);
  const [unread, setUnread] = useState<number | null>(null);
  const [projects, setProjects] = useState<api.ProjectDto[]>([]);
  const [runCount, setRunCount] = useState<number | null>(null);

  useEffect(() => {
    Promise.allSettled([
      api.getUnreadCount(),
      api.getProjects(),
      api.getRuns({page: 0, size: 1}),
    ]).then(([u, p, r]) => {
      if (u.status === 'fulfilled') setUnread(u.value.count);
      if (p.status === 'fulfilled') setProjects(p.value.filter((x) => !x.archivedAt));
      if (r.status === 'fulfilled')
        setRunCount(
          (r.value as unknown as {totalElements?: number}).totalElements ?? r.value.content.length,
        );
    });
  }, []);

  return (
    <>
      <TopBar
        title='More'
        sub='Everything else'
        back
        backTo='/home'
        avatar={(user?.firstName?.[0] ?? '') + (user?.lastName?.[0] ?? '')}
      />
      <Feed>
        <SectionHeader>Workspace</SectionHeader>
        <Row
          to='/projects'
          dot='lavender'
          title='Projects'
          meta={<span>{projects.length} active</span>}
          showChev
        />
        <Row
          to='/runs'
          dot='lavender'
          title='All runs'
          meta={<span>{runCount != null ? `${runCount} total` : 'Loading…'}</span>}
          showChev
        />
        <Row
          to='/search'
          dot='lavender'
          title='Search · ⌘K'
          meta={<span>Runs, projects, files</span>}
          showChev
        />
        <Row
          to='/notifications'
          dot='coral'
          title='Notifications'
          meta={<span>{unread != null ? `${unread} unread` : '—'}</span>}
          showChev
        />

        <SectionHeader>Account &amp; billing</SectionHeader>
        <Row to='/account' title='Account' meta={<span>{user?.email}</span>} showChev />
        <Row to='/billing' dot='mint' title='Billing' meta={<span>{tier} tier</span>} showChev />
        <Row to='/plans' title='Plans &amp; pricing' meta={<span>Compare tiers</span>} showChev />

        <SectionHeader>Team</SectionHeader>
        <Row to='/org-members' title='Org members' meta={<span>Invite teammates</span>} showChev />
        <Row
          to='/integrations'
          title='Integrations'
          meta={<span>GitHub, Slack, Jira</span>}
          showChev
        />
        <Row to='/api-keys' title='API keys (BYOK)' meta={<span>Provider keys</span>} showChev />

        <SectionHeader>Discover</SectionHeader>
        <Row to='/insights' title='Insights' meta={<span>Analytics dashboard</span>} showChev />
        <Row to='/library' title='Library' meta={<span>Readymade solutions</span>} showChev />
        <Row to='/migrations' title='Migrations' meta={<span>Migration recipes</span>} showChev />
        <Row to='/templates' title='Spec templates' meta={<span>Saved specs</span>} showChev />
        {(tier === 'TEAM' || tier === 'ENTERPRISE' || isSuperAdmin) && (
          <Row to='/audit' title='Audit log' meta={<span>Compliance trail</span>} showChev />
        )}
        <Row
          to='/deployments'
          title='Deployments'
          meta={<span>Cloud Run / Docker</span>}
          showChev
        />
        <Row to='/trust' title='Trust & security' meta={<span>Compliance, SOC2</span>} showChev />

        {isSuperAdmin && (
          <>
            <SectionHeader>Admin</SectionHeader>
            <Row
              to='/admin'
              dot='coral'
              title='Admin · SUPER_ADMIN'
              meta={<span style={{color: 'var(--signal-coral-text)'}}>Platform-wide controls</span>}
              showChev
            />
          </>
        )}

        <p
          style={{
            textAlign: 'center',
            fontSize: 12,
            color: 'var(--signal-text-mute)',
            marginTop: 28,
          }}
        >
          ExecuteSpec · v1.0.0-M1
        </p>
      </Feed>
      <TabBar active='more' />
    </>
  );
}

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
    if (!isSuperAdmin) navigate('/home', {replace: true});
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
  const [confirm, setConfirm] = useState<{u: api.AuthUser; status: string} | null>(null);
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

  const onStatus = (u: api.AuthUser, status: string) => {
    if (status === 'SUSPENDED') setConfirm({u, status});
    else void applyStatus(u, status);
  };

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
                onChange={(v) => void setTier(u, v)}
              />
              <AdminMiniSelect
                value={u.globalRole}
                options={ADMIN_USER_ROLES}
                onChange={(v) => void setRole(u, v)}
              />
              <AdminMiniSelect
                value={ADMIN_USER_STATUSES.includes(u.status) ? u.status : 'ACTIVE'}
                options={ADMIN_USER_STATUSES}
                onChange={(v) => onStatus(u, v)}
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
        title='Suspend user'
        body={`Suspend ${confirm?.u.email}? They will lose access until reactivated.`}
        confirmLabel='Suspend'
        danger
        onConfirm={async () => {
          if (confirm) await applyStatus(confirm.u, confirm.status);
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
  const [confirm, setConfirm] = useState<{o: api.OrgDto; status: string} | null>(null);
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

  const onStatus = (o: api.OrgDto, status: string) => {
    if (status === 'SUSPENDED') setConfirm({o, status});
    else void applyStatus(o, status);
  };

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
                onChange={(v) => void setTier(o, v)}
              />
              <AdminMiniSelect
                value={ADMIN_ORG_STATUSES.includes(o.status) ? o.status : 'ACTIVE'}
                options={ADMIN_ORG_STATUSES}
                onChange={(v) => onStatus(o, v)}
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
        title='Suspend org'
        body={`Suspend ${confirm?.o.name}? All members lose access until reactivated.`}
        confirmLabel='Suspend'
        danger
        onConfirm={async () => {
          if (confirm) await applyStatus(confirm.o, confirm.status);
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

function SharedProjectSurfaceView({surface}: {surface: api.SharedProjectSurface}) {
  const meta = surface.meta;
  const surfaceText = meta.surface.replace(/_/g, ' ');
  const label = meta.label || surfaceText.toLowerCase();
  const expires = meta.expiresAt ? new Date(meta.expiresAt).toLocaleDateString() : 'No expiry';

  return (
    <div className='public-share-console'>
      <TopBar title='Public project link' sub='read-only · no auth' back backTo='/home' />
      <Feed>
        <Card className='public-share-notice'>
          <div className='public-share-notice-eyebrow'>Shared project surface · public link</div>
          <div className='public-share-notice-copy'>
            This read-only link can be opened without an ExecuteSpec account. The owner can revoke
            it at any time.
          </div>
        </Card>

        <SectionHeader>{label}</SectionHeader>
        <Card>
          <KvList
            pairs={[
              {k: 'Surface', v: surfaceText},
              {k: 'Project', v: meta.projectId},
              {
                k: 'Created',
                v: meta.createdAt ? new Date(meta.createdAt).toLocaleDateString() : '—',
              },
              {k: 'Expires', v: expires},
            ]}
          />
        </Card>

        {surface.kind === 'PROJECT_OVERVIEW' && (
          <>
            <SectionHeader>Project snapshot</SectionHeader>
            <Card>
              <KvList
                pairs={[
                  {k: 'Name', v: surface.project.name || surface.project.projectId},
                  {k: 'Status', v: surface.project.status || '—'},
                  {k: 'Provider', v: surface.project.aiProvider || '—'},
                  {k: 'GitHub owner', v: surface.project.githubOwner || '—'},
                  {
                    k: 'Updated',
                    v: surface.project.updatedAt
                      ? new Date(surface.project.updatedAt).toLocaleDateString()
                      : '—',
                  },
                ]}
              />
              {surface.project.description && (
                <p
                  style={{
                    fontSize: 14,
                    color: 'var(--signal-ink)',
                    lineHeight: 1.55,
                    margin: '14px 0 0',
                    whiteSpace: 'pre-wrap',
                  }}
                >
                  {surface.project.description}
                </p>
              )}
            </Card>
          </>
        )}

        {surface.kind === 'RUN_DETAIL' && (
          <>
            <SectionHeader>Run</SectionHeader>
            <Card>
              <KvList
                pairs={[
                  {k: 'Title', v: surface.run.title || surface.run.runId},
                  {k: 'Status', v: surface.run.status || '—'},
                  {k: 'Reason', v: surface.run.statusReason || '—'},
                  {
                    k: 'Cost',
                    v: surface.run.costUsd != null ? `$${surface.run.costUsd.toFixed(2)}` : '—',
                  },
                  {
                    k: 'Completed',
                    v: surface.run.completedAt
                      ? new Date(surface.run.completedAt).toLocaleDateString()
                      : 'active',
                  },
                ]}
              />
            </Card>
          </>
        )}

        {surface.kind === 'VALIDATION_REPORT' && (
          <ScoreCard
            verdict={verdictFor(surface.report.verifiedScore ?? 0, undefined).label}
            score={Math.round(surface.report.verifiedScore ?? 0)}
            label='Verified score'
            cells={[
              {
                v:
                  surface.report.testsTotal != null
                    ? `${surface.report.testsPassed ?? 0}/${surface.report.testsTotal}`
                    : '—',
                l: 'Tests',
              },
              {v: surface.report.finalBuildPassed ? 'pass' : '—', l: 'Build'},
              {v: surface.report.reviewerVerdict || '—', l: 'Review'},
            ]}
          />
        )}

        {surface.kind === 'CODE_REVIEW' && (
          <>
            <ScoreCard
              verdict={surface.review.status || 'Code review'}
              score={surface.review.totalIssues}
              label='Issues found'
              cells={[
                {v: `${surface.review.criticalCount}`, l: 'Critical'},
                {v: `${surface.review.majorCount}`, l: 'Major'},
                {v: surface.review.repoFullName || '—', l: 'Repo'},
              ]}
            />
            {surface.review.reportMarkdown && (
              <>
                <SectionHeader>Report</SectionHeader>
                <Card>
                  <pre
                    style={{
                      whiteSpace: 'pre-wrap',
                      margin: 0,
                      fontSize: 13,
                      color: 'var(--signal-ink)',
                      lineHeight: 1.55,
                    }}
                  >
                    {surface.review.reportMarkdown}
                  </pre>
                </Card>
              </>
            )}
          </>
        )}

        {surface.kind === 'PR_REVIEW' && (
          <>
            <ScoreCard
              verdict={surface.review.verdict || 'PR review'}
              score={surface.review.commentCount}
              label='Review comments'
              cells={[
                {v: surface.review.repoFullName || '—', l: 'Repo'},
                {v: surface.review.prNumber != null ? `#${surface.review.prNumber}` : '—', l: 'PR'},
                {v: surface.review.verdict || '—', l: 'Verdict'},
              ]}
            />
            {surface.review.summary && (
              <>
                <SectionHeader>Summary</SectionHeader>
                <Card>
                  <p style={{whiteSpace: 'pre-wrap', margin: 0, lineHeight: 1.55}}>
                    {surface.review.summary}
                  </p>
                </Card>
              </>
            )}
          </>
        )}

        {surface.kind === 'ARCHITECTURE_DOC' && (
          <>
            <SectionHeader>Architecture v{surface.architecture.version ?? '—'}</SectionHeader>
            <Card>
              <pre
                style={{
                  whiteSpace: 'pre-wrap',
                  margin: 0,
                  fontSize: 13,
                  color: 'var(--signal-ink)',
                  lineHeight: 1.55,
                }}
              >
                {surface.architecture.content || 'Architecture doc is empty.'}
              </pre>
            </Card>
          </>
        )}

        <p
          style={{
            textAlign: 'center',
            fontSize: 12,
            color: 'var(--signal-text-mute)',
            marginTop: 16,
          }}
        >
          Shipped by <strong style={{color: 'var(--signal-ink)'}}>ExecuteSpec</strong> ·{' '}
          <Link to='/login' style={{color: 'var(--signal-coral)'}}>
            Build your own →
          </Link>
        </p>
      </Feed>
    </div>
  );
}

export function SharedPage() {
  const {token} = useParams<{token: string}>();
  const location = useLocation();
  const [data, setData] = useState<api.SharedReportData | null>(null);
  const [projectSurface, setProjectSurface] = useState<api.SharedProjectSurface | null>(null);
  const [err, setErr] = useState('');

  useEffect(() => {
    if (!token) return;
    setData(null);
    setProjectSurface(null);
    setErr('');
    const legacyOnly = location.pathname.startsWith('/reports/shared/');
    if (legacyOnly) {
      api
        .getSharedReport(token)
        .then((d) => {
          if (d) setData(d);
          else setErr('Share link expired or revoked.');
        })
        .catch((e: unknown) => setErr(e instanceof Error ? e.message : 'Failed to load.'));
      return;
    }

    api
      .getSharedProjectSurface(token)
      .then(setProjectSurface)
      .catch(() => {
        api
          .getSharedReport(token)
          .then((d) => {
            if (d) setData(d);
            else setErr('Share link expired or revoked.');
          })
          .catch((e: unknown) => setErr(e instanceof Error ? e.message : 'Failed to load.'));
      });
  }, [location.pathname, token]);

  if (err) {
    return (
      <div className='public-share-console'>
        <TopBar title='Public report' sub='read-only · no auth' back backTo='/home' />
        <Feed>
          <Card className='public-share-error'>
            <div>{err}</div>
          </Card>
        </Feed>
      </div>
    );
  }

  if (!data) {
    if (projectSurface) return <SharedProjectSurfaceView surface={projectSurface} />;
    return (
      <div className='public-share-console'>
        <TopBar title='Public report' sub='loading…' back backTo='/home' />
        <Feed>
          <LoadingState label='Loading shared report…' />
        </Feed>
      </div>
    );
  }

  const score =
    (data as unknown as {verifiedScore?: number; score?: number}).verifiedScore ??
    (data as unknown as {score?: number}).score ??
    0;
  // Shared verdict helper — was inline ≥90 here, which disagreed with the
  // canonical ≥85 band used everywhere else. Shared report has no run status,
  // so pass undefined and let the score drive the verdict.
  const verdict = verdictFor(score, undefined).label;
  const userRequest = (data as unknown as {userRequest?: string}).userRequest || '—';
  const prUrl =
    (data as unknown as {prUrl?: string; githubPrUrl?: string}).prUrl ||
    (data as unknown as {githubPrUrl?: string}).githubPrUrl;
  const cost =
    (data as unknown as {totalCost?: number; actualCost?: number}).totalCost ??
    (data as unknown as {actualCost?: number}).actualCost;
  const tests = (data as unknown as {testsTotal?: number; testsPassed?: number}).testsTotal;

  return (
    <div className='public-share-console'>
      <TopBar title='Public report' sub='read-only · no auth' back backTo='/home' />
      <Feed>
        <Card className='public-share-notice'>
          <div className='public-share-notice-eyebrow'>Shared report · public link</div>
          <div className='public-share-notice-copy'>
            This page is publicly accessible. The original owner can revoke at any time.
          </div>
        </Card>

        <ScoreCard
          verdict={verdict}
          score={Math.round(score)}
          label='Verified score'
          cells={[
            {
              v: tests
                ? `${(data as unknown as {testsPassed?: number}).testsPassed ?? 0}/${tests}`
                : '—',
              l: 'Tests',
            },
            {v: cost != null ? `$${cost.toFixed(2)}` : '—', l: 'Cost'},
            {v: prUrl ? 'open' : '—', l: 'PR'},
          ]}
        />

        <SectionHeader>Spec</SectionHeader>
        <Card>
          <p
            style={{
              fontSize: 14,
              color: 'var(--signal-ink)',
              lineHeight: 1.55,
              margin: 0,
              whiteSpace: 'pre-wrap',
            }}
          >
            {userRequest}
          </p>
        </Card>

        {prUrl && (
          <>
            <SectionHeader>Pull request</SectionHeader>
            <PrCard
              label='PR · OPEN'
              title={userRequest.slice(0, 80)}
              repo={prUrl.replace(/^https?:\/\/[^/]+\//, '').replace(/\/pull\//, '#')}
              ciStatus={<span className='ci-ok'>● open</span>}
              actions={
                <button type='button' onClick={() => window.open(prUrl, '_blank', 'noopener')}>
                  Open on GitHub
                </button>
              }
            />
          </>
        )}

        <p
          style={{
            textAlign: 'center',
            fontSize: 12,
            color: 'var(--signal-text-mute)',
            marginTop: 16,
          }}
        >
          Shipped by <strong style={{color: 'var(--signal-ink)'}}>ExecuteSpec</strong> ·{' '}
          <Link to='/login' style={{color: 'var(--signal-coral)'}}>
            Build your own →
          </Link>
        </p>
      </Feed>
    </div>
  );
}
