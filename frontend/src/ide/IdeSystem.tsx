import {useEffect, useMemo, useState} from 'react';
import {Link, useNavigate} from 'react-router-dom';
import * as api from '@/lib/api';
import {useAuthStore, selectIsSuperAdmin, selectNormalizedTier} from '@/stores/useAuthStore';
import {IdeBadge, IdeLoader, statusKind} from '@/ide/IdeKit';
import {runTitleFor} from '@/signal/lib/runUtils';

// IDE-native versions of the remaining Signal "system" surfaces (notifications,
// search, audit, empty-project, more menu). These mirror the data/API/actions of
// the Signal originals (signal/pages/MiscPages.tsx + ExtraPages.tsx) re-rendered
// in the VS Code-style IDE visual language. Conventions cribbed from
// IdeMore.tsx + IdeProjectMore.tsx (.ide-pane / .ide-h1 / .ide-sub / .ide-card /
// .ide-h2 / .ide-empty / .ide-rtabs / .ide-btn + IdeKit IdeLoader/IdeBadge).

// Shared row-link style used by the notification + audit + search lists. Matches
// the IdeRuns list-row treatment (flex row, 11px/16px padding, bottom border).
function rowStyle(last: boolean): React.CSSProperties {
  return {
    display: 'flex',
    alignItems: 'center',
    gap: 12,
    padding: '11px 16px',
    textDecoration: 'none',
    color: 'var(--ide-fg)',
    borderBottom: last ? 'none' : '1px solid var(--ide-border)',
  };
}

// Shared inline error card (red foreground) — same shape IdeRuns uses. C7:
// pass `onRetry` to render a ghost Retry button next to the message.
function ErrCard({msg, onRetry}: {msg: string; onRetry?: () => void}) {
  return (
    <div className='ide-card' style={{marginBottom: 12, color: 'var(--ide-red)'}}>
      {msg}
      {onRetry && (
        <button
          className='ide-btn ghost'
          style={{padding: '2px 10px', marginLeft: 8}}
          onClick={onRetry}
        >
          Retry
        </button>
      )}
    </div>
  );
}

type NotifFilter = 'all' | 'run' | 'team' | 'billing' | 'system';

export default function IdeNotifications() {
  const navigate = useNavigate();
  const [items, setItems] = useState<api.NotificationItem[] | null>(null);
  const [filter, setFilter] = useState<NotifFilter>('all');
  const [markBusy, setMarkBusy] = useState(false);
  const [err, setErr] = useState('');

  async function refresh() {
    setErr('');
    try {
      const page = await api.getNotifications(0, 50);
      setItems((page as unknown as {content?: api.NotificationItem[]}).content ?? []);
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Failed to load notifications.');
      setItems([]); // exit the loading state so the empty UI renders
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
    setMarkBusy(true);
    try {
      await api.markAllNotificationsRead();
      setItems((prev) => prev?.map((n) => ({...n, read: true})) || prev);
    } catch {
      /* ignore */
    } finally {
      setMarkBusy(false);
    }
  }

  const TABS: Array<{key: NotifFilter; label: string; n?: number}> = [
    {key: 'all', label: 'All', n: items?.length ?? 0},
    {key: 'run', label: 'Work'},
    {key: 'team', label: 'Team'},
    {key: 'billing', label: 'Billing'},
    {key: 'system', label: 'System'},
  ];

  return (
    <div className='ide-pane' style={{maxWidth: 760}}>
      <h1 className='ide-h1'>Notifications</h1>
      <div className='ide-sub'>
        {items === null ? 'Work, team, billing, and system events.' : `${unreadCount} unread`}
      </div>

      <div style={{display: 'flex', alignItems: 'center', gap: 12, marginBottom: 12}}>
        <div className='ide-rtabs' style={{flex: 1, marginBottom: 0}}>
          {TABS.map((t) => (
            <button
              key={t.key}
              className={`ide-rtab${filter === t.key ? ' active' : ''}`}
              onClick={() => setFilter(t.key)}
            >
              {t.label}
              {t.n != null && <span className='ide-rtab-badge'>{t.n}</span>}
            </button>
          ))}
        </div>
        <button
          className='ide-btn ghost'
          style={{flexShrink: 0}}
          disabled={unreadCount === 0 || markBusy}
          onClick={readAll}
        >
          {markBusy ? 'Marking…' : 'Mark all read'}
        </button>
      </div>

      {err && <ErrCard msg={err} onRetry={() => void refresh()} />}

      {items === null ? (
        <IdeLoader label='Loading notifications…' />
      ) : filtered.length === 0 ? (
        <div className='ide-empty'>No notifications yet.</div>
      ) : (
        <div className='ide-card' style={{padding: 0}}>
          {filtered.map((n, i) => {
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
            const dot =
              nAny.severity === 'error'
                ? 'var(--ide-red)'
                : nAny.severity === 'warn'
                  ? 'var(--ide-yellow)'
                  : 'var(--ide-green)';
            // Deep-link target: prefer the backend-provided `link`, else route by
            // event type. Falls through to /home so clicks never dead-end.
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
            const ts = nAny.createdAt ? new Date(nAny.createdAt).toLocaleString() : '';
            return (
              <div
                key={nAny.id || i}
                onClick={() => {
                  if (nAny.id) void readOne(nAny.id);
                  // C11 — absolute backend links open in a new tab; in-app
                  // targets go through the router (was a hand-rolled
                  // pushState + PopStateEvent that broke on malformed targets).
                  if (linkTarget.startsWith('http')) {
                    window.open(linkTarget, '_blank', 'noopener,noreferrer');
                  } else {
                    navigate(linkTarget);
                  }
                }}
                style={{...rowStyle(i >= filtered.length - 1), cursor: 'pointer'}}
              >
                <span
                  style={{
                    width: 8,
                    height: 8,
                    borderRadius: '50%',
                    flexShrink: 0,
                    background: unread ? dot : 'var(--ide-border)',
                  }}
                />
                <span style={{flex: 1, minWidth: 0}}>
                  <span
                    style={{
                      display: 'block',
                      fontWeight: unread ? 600 : 500,
                      color: 'var(--ide-fg-bright)',
                      overflow: 'hidden',
                      textOverflow: 'ellipsis',
                      whiteSpace: 'nowrap',
                    }}
                  >
                    {nAny.title || nAny.category || 'Notification'}
                  </span>
                  <span
                    style={{
                      display: 'block',
                      fontSize: 12,
                      color: 'var(--ide-fg-muted)',
                      overflow: 'hidden',
                      textOverflow: 'ellipsis',
                      whiteSpace: 'nowrap',
                    }}
                  >
                    {nAny.description || nAny.body || ''}
                  </span>
                </span>
                {ts && (
                  <span
                    className='ide-mono'
                    style={{fontSize: 11, color: 'var(--ide-fg-muted)', flexShrink: 0}}
                  >
                    {ts}
                  </span>
                )}
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}

interface SearchHit {
  id: string;
  kind: 'run' | 'project';
  title: string;
  subtitle: string;
  to: string;
}

const LIVE_SEARCH_STATUSES = new Set([
  'CLASSIFYING',
  'PLANNING',
  'EXECUTING',
  'AWAITING_APPROVAL',
  'RUNNING',
]);

export function IdeSearch() {
  const [q, setQ] = useState('');
  const [runs, setRuns] = useState<api.RunSummary[]>([]);
  const [projects, setProjects] = useState<api.ProjectDto[]>([]);
  const [loaded, setLoaded] = useState(false);
  const [err, setErr] = useState('');
  const navigate = useNavigate();

  useEffect(() => {
    let cancelled = false;
    Promise.allSettled([api.getRuns({page: 0, size: 50}), api.getProjects()])
      .then(([r, p]) => {
        if (cancelled) return;
        if (r.status === 'fulfilled') setRuns(r.value.content ?? []);
        if (p.status === 'fulfilled') setProjects(p.value);
        if (r.status === 'rejected' && p.status === 'rejected') {
          setErr('Failed to load search index.');
        }
      })
      .finally(() => {
        if (!cancelled) setLoaded(true);
      });
    return () => {
      cancelled = true;
    };
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
        const isLive = LIVE_SEARCH_STATUSES.has(s);
        out.push({
          id: r.runId,
          kind: 'run',
          title: runTitleFor(r),
          subtitle: `R-${r.runId.slice(0, 6)} · ${s.toLowerCase()}`,
          to: isLive ? `/live/${r.runId}` : `/result/${r.runId}`,
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
          subtitle: `${(p as unknown as {repoCount?: number}).repoCount ?? 0} repos · ${(p as unknown as {totalRuns?: number}).totalRuns ?? 0} work items`,
          to: `/projects/${p.id}`,
        });
      }
    }
    return out.slice(0, 30);
  }, [q, runs, projects]);

  const recent = runs.slice(0, 5);

  return (
    <div className='ide-pane' style={{maxWidth: 760}}>
      <div className='ide-h1'>Search</div>
      <div className='ide-sub'>Work, projects, files.</div>

      <div
        style={{
          display: 'flex',
          gap: 8,
          alignItems: 'center',
          background: 'var(--ide-bg-input, var(--ide-bg-tab-inactive))',
          border: '1px solid var(--ide-border)',
          borderRadius: 6,
          padding: '8px 12px',
          marginBottom: 16,
        }}
      >
        <svg
          viewBox='0 0 24 24'
          style={{width: 16, height: 16, flexShrink: 0, color: 'var(--ide-fg-muted)'}}
          fill='none'
          stroke='currentColor'
          strokeWidth={2}
        >
          <circle cx='11' cy='11' r='7' />
          <path d='M21 21l-4.3-4.3' />
        </svg>
        <input
          type='search'
          value={q}
          onChange={(e) => setQ(e.target.value)}
          placeholder='Search work, projects, files…'
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
            color: 'var(--ide-fg-bright)',
          }}
        />
        <span
          className='ide-mono'
          style={{
            fontSize: 10,
            color: 'var(--ide-fg-muted)',
            background: 'var(--ide-bg-tab-inactive)',
            padding: '3px 7px',
            borderRadius: 4,
            flexShrink: 0,
          }}
        >
          ⌘K
        </span>
      </div>

      {err && <ErrCard msg={err} />}

      {q.trim() === '' ? (
        <>
          <div className='ide-h2' style={{marginTop: 0}}>
            Quick actions
          </div>
          <div className='ide-card' style={{padding: 0}}>
            {[
              {to: '/compose', label: 'New work', sub: 'Draft a request'},
              {to: '/projects', label: 'Browse projects', sub: 'All your active projects'},
              {to: '/runs', label: 'Work', sub: 'Filter and browse'},
            ].map((row, i, arr) => (
              <Link key={row.to} to={row.to} style={rowStyle(i >= arr.length - 1)}>
                <span style={{flex: 1, minWidth: 0}}>
                  <span style={{display: 'block', fontWeight: 500, color: 'var(--ide-fg-bright)'}}>
                    {row.label}
                  </span>
                  <span style={{fontSize: 12, color: 'var(--ide-fg-muted)'}}>{row.sub}</span>
                </span>
                <span style={{color: 'var(--ide-fg-muted)', fontSize: 12}}>›</span>
              </Link>
            ))}
          </div>

          <div className='ide-h2'>Recent</div>
          {!loaded ? (
            <IdeLoader label='Loading recent work…' />
          ) : recent.length === 0 ? (
            <div className='ide-empty'>No work yet.</div>
          ) : (
            <div className='ide-card' style={{padding: 0}}>
              {recent.map((r, i) => (
                <Link
                  key={r.runId}
                  to={`/result/${r.runId}`}
                  style={rowStyle(i >= recent.length - 1)}
                >
                  <span style={{flex: 1, minWidth: 0}}>
                    <span
                      style={{
                        display: 'block',
                        fontWeight: 500,
                        color: 'var(--ide-fg-bright)',
                        overflow: 'hidden',
                        textOverflow: 'ellipsis',
                        whiteSpace: 'nowrap',
                      }}
                    >
                      {runTitleFor(r)}
                    </span>
                    <span className='ide-mono' style={{fontSize: 11, color: 'var(--ide-fg-muted)'}}>
                      R-{r.runId.slice(0, 6)}
                    </span>
                  </span>
                  <IdeBadge kind={statusKind(r.status)}>{r.status}</IdeBadge>
                </Link>
              ))}
            </div>
          )}
        </>
      ) : (
        <>
          <div className='ide-h2' style={{marginTop: 0}}>
            {hits.length} result{hits.length === 1 ? '' : 's'} for “{q}”
          </div>
          {hits.length === 0 ? (
            <div className='ide-empty'>
              No matches for “{q.trim()}”. Try a run id (R-abc123), a project name, or a page name.
            </div>
          ) : (
            <div className='ide-card' style={{padding: 0}}>
              {hits.map((h, i) => (
                <Link key={`${h.kind}-${h.id}`} to={h.to} style={rowStyle(i >= hits.length - 1)}>
                  <span style={{flex: 1, minWidth: 0}}>
                    <span
                      style={{
                        display: 'block',
                        fontWeight: 500,
                        color: 'var(--ide-fg-bright)',
                        overflow: 'hidden',
                        textOverflow: 'ellipsis',
                        whiteSpace: 'nowrap',
                      }}
                    >
                      {h.title}
                    </span>
                    <span style={{fontSize: 12, color: 'var(--ide-fg-muted)'}}>{h.subtitle}</span>
                  </span>
                  <span className='ide-badge'>{h.kind.toUpperCase()}</span>
                </Link>
              ))}
            </div>
          )}
        </>
      )}
    </div>
  );
}

type AuditFilter = 'all' | 'auth' | 'billing' | 'members' | 'admin';

export function IdeAudit() {
  const tier = useAuthStore(selectNormalizedTier);
  const user = useAuthStore((s) => s.user);
  const isOrgAdmin = user?.orgRole === 'ORG_ADMIN' || user?.globalRole === 'SUPER_ADMIN';
  const [logs, setLogs] = useState<api.AuditLogEntry[] | null>(null);
  const [filter, setFilter] = useState<AuditFilter>('all');
  const [err, setErr] = useState('');

  useEffect(() => {
    let cancelled = false;
    api
      .getAuditLogs({page: 0, size: 100})
      .then((p) => {
        if (cancelled) return;
        setLogs((p as unknown as {content?: api.AuditLogEntry[]}).content ?? []);
      })
      .catch((e: unknown) => {
        if (cancelled) return;
        // Tailor the 403 message to what the caller can do about it:
        //   - FREE/PRO → upsell, audit is a TEAM+ feature
        //   - TEAM+ but not ORG_ADMIN → "ask your org admin"
        //   - TEAM+ ORG_ADMIN → org-level flag isn't on yet; point to Org Settings
        const msg = e instanceof Error ? e.message : 'Failed to load audit log.';
        if (/403|permission|forbidden/i.test(msg)) {
          if (tier === 'FREE' || tier === 'PRO') {
            setErr('Audit log is a TEAM+ feature. Upgrade your plan to enable it.');
          } else if (!isOrgAdmin) {
            setErr(
              'Audit log is restricted to org admins. Ask your org admin to grant you access.',
            );
          } else {
            setErr(
              "Audit log isn't enabled for your org yet. Open Org Settings → Compliance to turn it on, or contact support if the toggle isn't visible.",
            );
          }
        } else {
          setErr(msg);
        }
        setLogs([]); // exit the null/loading state so the empty UI renders
      });
    return () => {
      cancelled = true;
    };
  }, [tier, isOrgAdmin]);

  const filtered = (logs || []).filter((l) => {
    if (filter === 'all') return true;
    const t = (
      (l as unknown as {action?: string; eventType?: string}).action ||
      (l as unknown as {eventType?: string}).eventType ||
      ''
    ).toLowerCase();
    if (filter === 'auth')
      return (
        t.includes('login') || t.includes('oauth') || t.includes('logout') || t.includes('password')
      );
    if (filter === 'billing')
      return (
        t.includes('billing') ||
        t.includes('subscription') ||
        t.includes('payment') ||
        t.includes('invoice')
      );
    if (filter === 'members')
      return t.includes('member') || t.includes('invite') || t.includes('seat');
    if (filter === 'admin') return t.includes('admin') || t.includes('tier') || t.includes('flag');
    return true;
  });

  function exportCsv() {
    const rows = ['action,actor,target,createdAt'];
    for (const l of filtered) {
      const lAny = l as unknown as {
        action?: string;
        eventType?: string;
        actorEmail?: string;
        targetId?: string;
        createdAt?: string;
        occurredAt?: string;
      };
      rows.push(
        `${lAny.action || lAny.eventType || ''},${lAny.actorEmail || ''},${lAny.targetId || ''},${lAny.createdAt || lAny.occurredAt || ''}`,
      );
    }
    const blob = new Blob([rows.join('\n')], {type: 'text/csv'});
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `audit-${new Date().toISOString().slice(0, 10)}.csv`;
    a.click();
    URL.revokeObjectURL(url);
  }

  const TABS: Array<{key: AuditFilter; label: string; n?: number}> = [
    {key: 'all', label: 'All', n: logs?.length ?? 0},
    {key: 'auth', label: 'Auth'},
    {key: 'billing', label: 'Billing'},
    {key: 'members', label: 'Members'},
    {key: 'admin', label: 'Admin'},
  ];

  return (
    <div className='ide-pane' style={{maxWidth: 760}}>
      <div className='ide-h1'>Audit log</div>
      <div className='ide-sub'>{logs === null ? 'Compliance trail.' : `${logs.length} events`}</div>

      <div style={{display: 'flex', alignItems: 'center', gap: 12, marginBottom: 12}}>
        <div className='ide-rtabs' style={{flex: 1, marginBottom: 0}}>
          {TABS.map((t) => (
            <button
              key={t.key}
              className={`ide-rtab${filter === t.key ? ' active' : ''}`}
              onClick={() => setFilter(t.key)}
            >
              {t.label}
              {t.n != null && <span className='ide-rtab-badge'>{t.n}</span>}
            </button>
          ))}
        </div>
        <button
          className='ide-btn ghost'
          style={{flexShrink: 0}}
          disabled={filtered.length === 0}
          onClick={exportCsv}
        >
          Export CSV
        </button>
      </div>

      {err && <ErrCard msg={err} />}

      {logs === null ? (
        <IdeLoader label='Loading audit log…' />
      ) : filtered.length === 0 ? (
        <div className='ide-empty'>No audit events for this filter.</div>
      ) : (
        <div className='ide-card' style={{padding: 0}}>
          {filtered.map((l, i) => {
            const lAny = l as unknown as {
              id?: string;
              action?: string;
              eventType?: string;
              actorEmail?: string;
              actorUserId?: string;
              targetId?: string;
              createdAt?: string;
              occurredAt?: string;
            };
            const action = lAny.action || lAny.eventType || 'event';
            const ts = lAny.createdAt || lAny.occurredAt;
            const actor = lAny.actorEmail || lAny.actorUserId;
            return (
              <div key={lAny.id || `${i}-${ts}`} style={rowStyle(i >= filtered.length - 1)}>
                <span style={{flex: 1, minWidth: 0}}>
                  <span
                    style={{
                      display: 'block',
                      fontWeight: 500,
                      color: 'var(--ide-fg-bright)',
                      overflow: 'hidden',
                      textOverflow: 'ellipsis',
                      whiteSpace: 'nowrap',
                    }}
                  >
                    {action}
                  </span>
                  <span style={{fontSize: 12, color: 'var(--ide-fg-muted)'}}>
                    {actor && <span className='ide-mono'>{actor}</span>}
                    {actor && lAny.targetId ? ' · ' : ''}
                    {lAny.targetId}
                  </span>
                </span>
                <span
                  className='ide-mono'
                  style={{fontSize: 11, color: 'var(--ide-fg-muted)', flexShrink: 0}}
                >
                  {ts ? new Date(ts).toLocaleString() : '—'}
                </span>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}

// Empty project (onboarding)
export function IdeEmptyProject() {
  return (
    <div className='ide-pane' style={{maxWidth: 760}}>
      <div className='ide-h1'>Get started</div>
      <div className='ide-sub'>Let’s get your first PR shipped.</div>

      <div className='ide-card' style={{borderColor: 'var(--ide-green)'}}>
        <div
          style={{fontSize: 15, fontWeight: 600, color: 'var(--ide-fg-bright)', marginBottom: 6}}
        >
          You don’t have any projects yet
        </div>
        <div style={{fontSize: 13, color: 'var(--ide-fg-muted)', lineHeight: 1.5}}>
          Link a repo or pick a sandbox to see ExecuteSpec in action. ~3 minutes to your first
          verified PR.
        </div>
      </div>

      <div className='ide-h2'>Path 1 · Link a GitHub repo</div>
      <div className='ide-card'>
        <div
          style={{fontSize: 13, color: 'var(--ide-fg-muted)', marginBottom: 12, lineHeight: 1.5}}
        >
          Bring an existing codebase. We’ll install a webhook, snapshot the architecture, and you
          can start work against it immediately.
        </div>
        <Link className='ide-btn' to='/integrations' style={{display: 'inline-flex', gap: 8}}>
          <svg viewBox='0 0 24 24' style={{width: 15, height: 15}} aria-hidden>
            <path
              fill='currentColor'
              d='M12 .5C5.65.5.5 5.65.5 12c0 5.1 3.3 9.4 7.88 10.92.58.1.78-.25.78-.55 0-.27-.01-1-.02-1.96-3.2.7-3.88-1.54-3.88-1.54-.52-1.33-1.28-1.68-1.28-1.68-1.05-.72.08-.7.08-.7 1.16.08 1.77 1.19 1.77 1.19 1.03 1.77 2.7 1.26 3.36.96.1-.75.4-1.26.73-1.55-2.55-.29-5.24-1.28-5.24-5.7 0-1.26.45-2.29 1.19-3.09-.12-.3-.52-1.46.1-3.04 0 0 .97-.31 3.18 1.18A11.07 11.07 0 0 1 12 6.8c.98 0 1.98.13 2.91.38 2.2-1.5 3.18-1.18 3.18-1.18.63 1.58.23 2.74.11 3.04.74.8 1.19 1.83 1.19 3.09 0 4.42-2.7 5.4-5.26 5.69.41.36.78 1.06.78 2.13 0 1.54-.01 2.78-.01 3.16 0 .3.2.66.79.55C20.2 21.4 23.5 17.1 23.5 12 23.5 5.65 18.35.5 12 .5z'
            />
          </svg>
          Connect GitHub
        </Link>
      </div>

      <div className='ide-h2'>Path 2 · Try a sandbox</div>
      <div className='ide-card'>
        <div
          style={{fontSize: 13, color: 'var(--ide-fg-muted)', marginBottom: 12, lineHeight: 1.5}}
        >
          We’ll spin up a brand-new repo with one of three preset work requests. Free, no commits to
          your repos.
        </div>
        <Link className='ide-btn ghost' to='/welcome'>
          Pick a starter →
        </Link>
      </div>
    </div>
  );
}

// Named IdeMoreMenu to avoid clashing with IdeMore (the account-surface file).
type MoreRow = {to: string; label: string; sub: string};

function MoreSection({title, rows}: {title: string; rows: MoreRow[]}) {
  return (
    <>
      <div className='ide-h2'>{title}</div>
      <div className='ide-card' style={{padding: 0}}>
        {rows.map((row, i) => (
          <Link key={row.to} to={row.to} style={rowStyle(i >= rows.length - 1)}>
            <span style={{flex: 1, minWidth: 0}}>
              <span style={{display: 'block', fontWeight: 500, color: 'var(--ide-fg-bright)'}}>
                {row.label}
              </span>
              <span style={{fontSize: 12, color: 'var(--ide-fg-muted)'}}>{row.sub}</span>
            </span>
            <span style={{color: 'var(--ide-fg-muted)', fontSize: 12}}>›</span>
          </Link>
        ))}
      </div>
    </>
  );
}

export function IdeMoreMenu() {
  const user = useAuthStore((s) => s.user);
  const isSuperAdmin = useAuthStore(selectIsSuperAdmin);
  const tier = useAuthStore(selectNormalizedTier);
  const [unread, setUnread] = useState<number | null>(null);
  const [projects, setProjects] = useState<api.ProjectDto[]>([]);
  const [runCount, setRunCount] = useState<number | null>(null);

  useEffect(() => {
    let cancelled = false;
    Promise.allSettled([
      api.getUnreadCount(),
      api.getProjects(),
      api.getRuns({page: 0, size: 1}),
    ]).then(([u, p, r]) => {
      if (cancelled) return;
      if (u.status === 'fulfilled') setUnread(u.value.count);
      if (p.status === 'fulfilled') setProjects(p.value.filter((x) => !x.archivedAt));
      if (r.status === 'fulfilled')
        setRunCount(
          (r.value as unknown as {totalElements?: number}).totalElements ?? r.value.content.length,
        );
    });
    return () => {
      cancelled = true;
    };
  }, []);

  return (
    <div className='ide-pane' style={{maxWidth: 760}}>
      <div className='ide-h1'>More</div>
      <div className='ide-sub'>Everything else.</div>

      <MoreSection
        title='Workspace'
        rows={[
          {to: '/projects', label: 'Projects', sub: `${projects.length} active`},
          {
            to: '/runs',
            label: 'Work',
            sub: runCount != null ? `${runCount} items` : 'Loading…',
          },
          {to: '/search', label: 'Search · ⌘K', sub: 'Work, projects, files'},
          {
            to: '/notifications',
            label: 'Notifications',
            sub: unread != null ? `${unread} unread` : '—',
          },
        ]}
      />

      <MoreSection
        title='Account & billing'
        rows={[
          {to: '/account', label: 'Account', sub: user?.email || '—'},
          {to: '/billing', label: 'Billing', sub: `${tier} tier`},
          {to: '/plans', label: 'Plans & pricing', sub: 'Compare tiers'},
        ]}
      />

      <MoreSection
        title='Team'
        rows={[
          {to: '/org-members', label: 'Org members', sub: 'Invite teammates'},
          {to: '/integrations', label: 'Integrations', sub: 'GitHub, Slack, Jira'},
          {to: '/provider-keys', label: 'Provider keys', sub: 'BYOK model keys'},
        ]}
      />

      <MoreSection
        title='Discover'
        rows={[
          {to: '/insights', label: 'Insights', sub: 'Usage and outcomes'},
          ...(tier === 'TEAM' || tier === 'ENTERPRISE' || isSuperAdmin
            ? [{to: '/audit', label: 'Audit log', sub: 'Compliance trail'}]
            : []),
          {to: '/deployments', label: 'Deployments', sub: 'Cloud Run / Docker'},
          {to: '/trust', label: 'Trust & security', sub: 'Compliance, SOC2'},
        ]}
      />

      {isSuperAdmin && (
        <>
          <div className='ide-h2'>Admin</div>
          <div className='ide-card' style={{padding: 0}}>
            <Link to='/admin' style={rowStyle(true)}>
              <span style={{flex: 1, minWidth: 0}}>
                <span style={{display: 'block', fontWeight: 500, color: 'var(--ide-fg-bright)'}}>
                  Admin · SUPER_ADMIN
                </span>
                <span style={{fontSize: 12, color: 'var(--ide-red)'}}>Platform-wide controls</span>
              </span>
              <span style={{color: 'var(--ide-fg-muted)', fontSize: 12}}>›</span>
            </Link>
          </div>
        </>
      )}

      <p
        style={{
          textAlign: 'center',
          fontSize: 12,
          color: 'var(--ide-fg-muted)',
          marginTop: 28,
        }}
      >
        ExecuteSpec · v1.0.0-M1
      </p>
    </div>
  );
}
