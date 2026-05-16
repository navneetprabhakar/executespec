import {ReactNode, useEffect, useMemo, useState} from 'react';
import {Link, useNavigate, useParams} from 'react-router-dom';
import {TopBar, TabBar, SectionHeader, IconButton} from '@/signal/components/Chrome';
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
import {LoadingState, SkeletonRow} from '@/signal/components/Loaders';
import {ScoreCard, PrCard} from '@/signal/components/Compose';
import {Bell, Search, Settings, Plus} from '@/signal/icons/icons';
import {useAuthStore, selectIsSuperAdmin, selectNormalizedTier} from '@/stores/useAuthStore';
import * as api from '@/lib/api';

// ──────────────────── Notifications ────────────────────
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
          <IconButton ariaLabel='Mark all read' onClick={readAll}>
            <Settings size={18} />
          </IconButton>
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

// ──────────────────── Search / palette ────────────────────
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

// ──────────────────── More tab ────────────────────
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
        <Row
          to='/topup'
          dot='signal'
          title='Top up credits'
          meta={<span>Buy run credits</span>}
          showChev
        />
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

// ──────────────────── Admin (super-admin) ────────────────────
export function AdminPage() {
  const isSuperAdmin = useAuthStore(selectIsSuperAdmin);
  const navigate = useNavigate();
  const [tiers, setTiers] = useState<api.TierLifecycleDto[]>([]);
  const [orgs, setOrgs] = useState<api.OrgDto[]>([]);
  const [flags, setFlags] = useState<api.FeatureFlagDto[]>([]);
  const [funnel, setFunnel] = useState<api.ActivationFunnelReport | null>(null);
  const [err, setErr] = useState('');
  const [msg, setMsg] = useState('');

  useEffect(() => {
    if (!isSuperAdmin) {
      navigate('/home', {replace: true});
      return;
    }
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
  }, [isSuperAdmin, navigate]);

  async function reconcile() {
    try {
      const r = await fetch('/api/admin/executions/reconcile', {
        method: 'POST',
        headers: {Authorization: `Bearer ${localStorage.getItem('access_token') ?? ''}`},
      });
      if (!r.ok) throw new Error(`Reconcile failed (${r.status}).`);
      setMsg('Reconcile dispatched.');
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Reconcile failed.');
    }
  }

  if (!isSuperAdmin) return null;

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

        {err && (
          <div
            style={{
              background: 'var(--signal-coral-soft)',
              color: 'var(--signal-coral-text)',
              border: '1px solid var(--signal-coral)',
              borderRadius: 10,
              padding: '10px 14px',
              marginTop: 12,
              fontSize: 13,
            }}
          >
            {err}
          </div>
        )}
        {msg && (
          <div
            style={{
              background: 'var(--signal-mint-soft)',
              color: 'var(--signal-mint-text-on-light)',
              border: '1px solid var(--signal-mint)',
              borderRadius: 10,
              padding: '10px 14px',
              marginTop: 12,
              fontSize: 13,
            }}
          >
            {msg}
          </div>
        )}

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
          {funnel && (
            <>
              <div className='signal-quick-card signal'>
                <div className='icon-bg'>
                  <Bell size={16} />
                </div>
                <h4>Signups · 30d</h4>
                <div
                  className='count'
                  style={{color: 'var(--signal-ink)', fontSize: 17, fontWeight: 600}}
                >
                  {(funnel as unknown as {stages?: {signup?: number}}).stages?.signup ?? '—'}
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
                  {(funnel as unknown as {stages?: {runComplete?: number}}).stages?.runComplete ??
                    '—'}
                </div>
              </div>
            </>
          )}
        </div>

        <SectionHeader>Tier lifecycle</SectionHeader>
        {tiers.length === 0 ? (
          <LoadingState label='Loading tiers…' />
        ) : (
          tiers.map((t) => {
            const tAny = t as unknown as {tier?: string; state?: string; orgCount?: number};
            const state = tAny.state || 'LISTED';
            return (
              <Row
                key={tAny.tier}
                dot={state === 'LISTED' ? 'mint' : state === 'HIDDEN' ? 'signal' : 'coral'}
                title={tAny.tier || '—'}
                meta={
                  <span>
                    {state}
                    {tAny.orgCount != null ? ` · ${tAny.orgCount} orgs` : ''}
                  </span>
                }
                trailing={
                  <StatusPill
                    kind={state === 'LISTED' ? 'shipped' : state === 'HIDDEN' ? 'needs' : 'failed'}
                  >
                    {state}
                  </StatusPill>
                }
              />
            );
          })
        )}

        <SectionHeader>Feature flags · {flags.length}</SectionHeader>
        {flags.length === 0 ? (
          <LoadingState label='Loading flags…' />
        ) : (
          <Card>
            <KvList
              pairs={flags.slice(0, 12).map((f) => {
                const fAny = f as unknown as {key?: string; value?: string};
                return {k: fAny.key || '—', v: fAny.value || '—'};
              })}
            />
          </Card>
        )}

        <SectionHeader>Operations</SectionHeader>
        <Row dot='ocean' title='Payment audit' meta={<span>Transaction history</span>} showChev />
        <Row dot='ocean' title='Webhook event log' meta={<span>Gateway events</span>} showChev />
        <Row
          dot='ocean'
          title='Reconcile orphan runs'
          meta={<span>Mark stale runs FAILED</span>}
          trailing={
            <Button kind='secondary' sm onClick={reconcile}>
              Run
            </Button>
          }
        />
      </Feed>
    </>
  );
}

// ──────────────────── Public shared report ────────────────────
export function SharedPage() {
  const {token} = useParams<{token: string}>();
  const [data, setData] = useState<api.SharedReportData | null>(null);
  const [err, setErr] = useState('');

  useEffect(() => {
    if (!token) return;
    api
      .getSharedReport(token)
      .then((d) => {
        if (d) setData(d);
        else setErr('Share link expired or revoked.');
      })
      .catch((e: unknown) => setErr(e instanceof Error ? e.message : 'Failed to load.'));
  }, [token]);

  if (err) {
    return (
      <>
        <TopBar title='Public report' sub='read-only · no auth' back backTo='/home' />
        <Feed>
          <Card style={{background: 'var(--signal-coral-soft)', borderColor: 'transparent'}}>
            <div style={{fontSize: 13, color: 'var(--signal-coral-text)'}}>{err}</div>
          </Card>
        </Feed>
      </>
    );
  }

  if (!data) {
    return (
      <>
        <TopBar title='Public report' sub='loading…' back backTo='/home' />
        <Feed>
          <LoadingState label='Loading shared report…' />
        </Feed>
      </>
    );
  }

  const score =
    (data as unknown as {verifiedScore?: number; score?: number}).verifiedScore ??
    (data as unknown as {score?: number}).score ??
    0;
  const verdict = score >= 90 ? 'Shippable' : score >= 70 ? 'Needs review' : 'Failed';
  const userRequest = (data as unknown as {userRequest?: string}).userRequest || '—';
  const prUrl =
    (data as unknown as {prUrl?: string; githubPrUrl?: string}).prUrl ||
    (data as unknown as {githubPrUrl?: string}).githubPrUrl;
  const cost =
    (data as unknown as {totalCost?: number; actualCost?: number}).totalCost ??
    (data as unknown as {actualCost?: number}).actualCost;
  const tests = (data as unknown as {testsTotal?: number; testsPassed?: number}).testsTotal;

  return (
    <>
      <TopBar title='Public report' sub='read-only · no auth' back backTo='/home' />
      <Feed>
        <Card style={{background: 'var(--signal-lavender-soft)', borderColor: 'transparent'}}>
          <div
            style={{
              fontSize: 11,
              fontWeight: 700,
              color: '#5443a8',
              letterSpacing: '0.14em',
              textTransform: 'uppercase',
              marginBottom: 6,
            }}
          >
            Shared report · public link
          </div>
          <div style={{fontSize: 14, color: '#5443a8', lineHeight: 1.55}}>
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
    </>
  );
}
