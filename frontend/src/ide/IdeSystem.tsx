import {useEffect, useMemo, useState} from 'react';
import {Link, useNavigate} from 'react-router-dom';
import * as api from '@/lib/api';
import {
  useAuthStore,
  selectIsSuperAdmin,
  selectIsOrgAdmin,
  selectNormalizedTier,
} from '@/stores/useAuthStore';
import {IdeBadge, IdeLoader, statusKind} from '@/ide/IdeKit';
import {runTitleFor} from '@/signal/lib/runUtils';
import {useDocumentTitle} from '@/hooks/useDocumentTitle';
import {
  activeProjects,
  auditAccessErrorMessage,
  auditAction,
  auditActor,
  auditCsvText,
  auditRowState,
  auditTarget,
  auditTimestamp,
  buildSearchHits,
  customerSafeNotificationText,
  exactTime,
  filterAuditLogs,
  filterNotifications,
  isNotificationUnread,
  markAllNotificationsReadLocal,
  markNotificationReadLocal,
  notificationDotColor,
  notificationRowState,
  notificationTarget,
  SEARCH_QUICK_ACTIONS,
  setupAccountRows,
  setupEssentialRows,
  setupOperationRows,
  setupWorkspaceRows,
  shortAge,
  unreadNotificationCount,
  type AuditFilter,
  type MoreRow,
  type NotifFilter,
  type SearchHit,
} from './systemUtils';
import './surfaces/systemdiscovery.css';

// IDE-native system surfaces (notifications, search, audit, empty-project, more
// menu). The old Signal exports were retired; this file owns the route behavior.
//
// Version B row grammar (`.sysd-*` in surfaces/systemdiscovery.css): every list
// row is a GRID with a fixed leading track, so a severity dot, a category
// stripe or nothing at all still leaves every title on the same x. Row state is
// a 3px leading edge — the card body is never repainted. Do not reintroduce the
// per-row inline `React.CSSProperties` this file used to carry: the px
// font-size literals in it bypassed the `--ide-t-*` ramp and the phone step-down
// that comes with it.

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

/** Filter tabs plus at most one trailing ghost action. */
function Toolbar<T extends string>({
  tabs,
  value,
  onChange,
  action,
}: {
  tabs: Array<{key: T; label: string; n?: number}>;
  value: T;
  onChange: (next: T) => void;
  action?: React.ReactNode;
}) {
  return (
    <div className='sysd-toolbar'>
      <div className='ide-rtabs'>
        {tabs.map((t) => (
          <button
            key={t.key}
            className={`ide-rtab${value === t.key ? ' active' : ''}`}
            onClick={() => onChange(t.key)}
          >
            {t.label}
            {t.n != null && <span className='ide-rtab-badge'>{t.n}</span>}
          </button>
        ))}
      </div>
      {action}
    </div>
  );
}

export default function IdeNotifications() {
  const navigate = useNavigate();
  const isSuperAdmin = useAuthStore(selectIsSuperAdmin);
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

  const filtered = useMemo(() => filterNotifications(items, filter), [items, filter]);

  const unreadCount = unreadNotificationCount(items);

  async function readOne(id: string) {
    try {
      await api.markNotificationRead(id);
      setItems((prev) => markNotificationReadLocal(prev, id));
    } catch {
      /* ignore */
    }
  }

  async function readAll() {
    setMarkBusy(true);
    try {
      await api.markAllNotificationsRead();
      setItems((prev) => markAllNotificationsReadLocal(prev));
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
        {items === null ? 'Work, team, billing, system.' : `${unreadCount} unread`}
      </div>

      <Toolbar
        tabs={TABS}
        value={filter}
        onChange={setFilter}
        action={
          <button
            className='ide-btn ghost'
            disabled={unreadCount === 0 || markBusy}
            onClick={readAll}
          >
            {markBusy ? 'Marking…' : 'Mark all read'}
          </button>
        }
      />

      {err && <ErrCard msg={err} onRetry={() => void refresh()} />}

      {items === null ? (
        <IdeLoader label='Loading notifications…' />
      ) : filtered.length === 0 ? (
        <div className='ide-empty'>No notifications yet.</div>
      ) : (
        <div className='ide-card sysd-list'>
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
            const unread = isNotificationUnread(n);
            const linkTarget = notificationTarget(n);
            const open = () => {
              if (nAny.id) void readOne(nAny.id);
              // C11 — absolute backend links open in a new tab; in-app targets
              // go through the router (was a hand-rolled pushState +
              // PopStateEvent that broke on malformed targets).
              if (linkTarget.startsWith('http')) {
                window.open(linkTarget, '_blank', 'noopener,noreferrer');
              } else {
                navigate(linkTarget);
              }
            };
            return (
              // A row that navigates has always been an activatable control;
              // it used to be a plain <div onClick>, unreachable by keyboard.
              <button
                type='button'
                key={nAny.id || i}
                className={`sysd-row is-${notificationRowState(n)}`}
                onClick={open}
              >
                <span
                  className={`sysd-row-lead sysd-dot${unread ? '' : ' is-read'}`}
                  style={unread ? {color: notificationDotColor(n)} : undefined}
                  aria-hidden
                />
                <span className='sysd-row-main'>
                  <span className={`sysd-row-title${unread ? ' is-unread' : ''}`}>
                    {nAny.title || nAny.category || 'Notification'}
                  </span>
                  <span className='sysd-row-sub'>
                    {customerSafeNotificationText(
                      nAny.description || nAny.body || '',
                      Boolean(isSuperAdmin),
                    )}
                  </span>
                </span>
                <span className='sysd-row-time' title={exactTime(nAny.createdAt)}>
                  {shortAge(nAny.createdAt)}
                </span>
                <span className='sysd-row-end sysd-row-chev' aria-hidden>
                  ›
                </span>
              </button>
            );
          })}
        </div>
      )}
    </div>
  );
}

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

  const hits: SearchHit[] = useMemo(() => buildSearchHits(q, runs, projects), [q, runs, projects]);

  const recent = runs.slice(0, 5);

  return (
    <div className='ide-pane' style={{maxWidth: 760}}>
      <h1 className='ide-h1'>Search</h1>
      <div className='ide-sub'>Work, projects, files.</div>

      <div className='sysd-search'>
        <svg
          className='sysd-search-icon'
          viewBox='0 0 24 24'
          fill='none'
          stroke='currentColor'
          strokeWidth={1.75}
          aria-hidden
        >
          <circle cx='11' cy='11' r='7' />
          <path d='M21 21l-4.3-4.3' />
        </svg>
        <input
          type='search'
          aria-label='Search work, projects, and files'
          value={q}
          onChange={(e) => setQ(e.target.value)}
          placeholder='Search work, projects, files…'
          autoFocus
          onKeyDown={(e) => {
            if (e.key === 'Enter' && hits.length > 0) navigate(hits[0].to);
          }}
          style={{minHeight: 32}}
        />
        <span className='ide-mono sysd-search-kbd'>⌘K</span>
      </div>

      {err && <ErrCard msg={err} />}

      {q.trim() === '' ? (
        <>
          <div className='ide-h2' style={{marginTop: 0}}>
            Quick actions
          </div>
          <div className='ide-card sysd-list'>
            {SEARCH_QUICK_ACTIONS.map((row) => (
              <Link
                key={row.to}
                to={row.to}
                className='sysd-row'
                style={{['--sysd-lead' as string]: '0px'}}
              >
                <span className='sysd-row-lead' aria-hidden />
                <span className='sysd-row-main'>
                  <span className='sysd-row-title'>{row.label}</span>
                  <span className='sysd-row-sub'>{row.sub}</span>
                </span>
                <span className='sysd-row-time' />
                <span className='sysd-row-end sysd-row-chev' aria-hidden>
                  ›
                </span>
              </Link>
            ))}
          </div>

          <div className='ide-h2'>Recent</div>
          {!loaded ? (
            <IdeLoader label='Loading recent work…' />
          ) : recent.length === 0 ? (
            <div className='ide-empty'>No work yet.</div>
          ) : (
            <div className='ide-card sysd-list'>
              {recent.map((r) => (
                <Link
                  key={r.runId}
                  to={`/result/${r.runId}`}
                  className='sysd-row'
                  style={{['--sysd-lead' as string]: '0px'}}
                >
                  <span className='sysd-row-lead' aria-hidden />
                  <span className='sysd-row-main'>
                    <span className='sysd-row-title'>{runTitleFor(r)}</span>
                    <span className='ide-mono sysd-row-sub'>R-{r.runId.slice(0, 6)}</span>
                  </span>
                  <span className='sysd-row-time' />
                  <span className='sysd-row-end'>
                    <IdeBadge kind={statusKind(r.status)}>{r.status}</IdeBadge>
                  </span>
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
            <div className='ide-card sysd-list'>
              {hits.map((h) => (
                <Link
                  key={`${h.kind}-${h.id}`}
                  to={h.to}
                  className='sysd-row'
                  style={{['--sysd-lead' as string]: '0px'}}
                >
                  <span className='sysd-row-lead' aria-hidden />
                  <span className='sysd-row-main'>
                    <span className='sysd-row-title'>{h.title}</span>
                    <span className='sysd-row-sub'>{h.subtitle}</span>
                  </span>
                  <span className='sysd-row-time' />
                  {/* Fixed-width kind column: `RUN` and `PROJECT` must not
                      shift the row's right edge relative to one another. */}
                  <span className='ide-badge sysd-row-end sysd-kind'>{h.kind.toUpperCase()}</span>
                </Link>
              ))}
            </div>
          )}
        </>
      )}
    </div>
  );
}

export function IdeAudit() {
  const tier = useAuthStore(selectNormalizedTier);
  // F2 (QA, 2026-06-23) — the audit panel is for ORG_ADMIN + SUPER_ADMIN only.
  // selectIsOrgAdmin already returns true for SUPER_ADMIN || ORG_ADMIN, so it
  // is the single gate: non-admins never reach the fetch below (the server 403s
  // them anyway, but firing the request just to render a broken table is wrong).
  const isOrgAdmin = useAuthStore(selectIsOrgAdmin);
  const [logs, setLogs] = useState<api.AuditLogEntry[] | null>(null);
  const [filter, setFilter] = useState<AuditFilter>('all');
  const [err, setErr] = useState('');

  useEffect(() => {
    if (!isOrgAdmin) return;
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
        setErr(auditAccessErrorMessage(msg, tier, isOrgAdmin));
        setLogs([]); // exit the null/loading state so the empty UI renders
      });
    return () => {
      cancelled = true;
    };
  }, [tier, isOrgAdmin]);

  const filtered = filterAuditLogs(logs, filter);

  function exportCsv() {
    const blob = new Blob([auditCsvText(filtered)], {type: 'text/csv'});
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

  // F2 (QA, 2026-06-23) — non-admins get a clear empty state instead of the
  // table + a 403 error. No /api/admin/audit-logs request fires for them.
  if (!isOrgAdmin) {
    return (
      <div className='ide-pane' style={{maxWidth: 760}}>
        <h1 className='ide-h1'>Audit log</h1>
        <div className='ide-sub'>Compliance trail.</div>
        <div className='ide-empty'>Audit log is available to organization admins only.</div>
      </div>
    );
  }

  return (
    <div className='ide-pane' style={{maxWidth: 760}}>
      <h1 className='ide-h1'>Audit log</h1>
      <div className='ide-sub'>{logs === null ? 'Compliance trail.' : `${logs.length} events`}</div>

      <Toolbar
        tabs={TABS}
        value={filter}
        onChange={setFilter}
        action={
          <button className='ide-btn ghost' disabled={filtered.length === 0} onClick={exportCsv}>
            Export CSV
          </button>
        }
      />

      {err && <ErrCard msg={err} />}

      {logs === null ? (
        <IdeLoader label='Loading audit log…' />
      ) : filtered.length === 0 ? (
        <div className='ide-empty'>No audit events for this filter.</div>
      ) : (
        <div className='ide-card sysd-list'>
          {filtered.map((l, i) => {
            const lAny = l as unknown as {id?: string};
            const action = auditAction(l);
            const ts = auditTimestamp(l);
            const actor = auditActor(l);
            const target = auditTarget(l);
            return (
              <div
                key={lAny.id || `${i}-${ts}`}
                className={`sysd-row is-${auditRowState(l)}`}
                style={{['--sysd-lead' as string]: '0px'}}
              >
                <span className='sysd-row-lead' aria-hidden />
                <span className='sysd-row-main'>
                  <span className='sysd-row-title'>{action}</span>
                  <span className='ide-mono sysd-row-sub'>
                    {actor}
                    {actor && target ? ' · ' : ''}
                    {target}
                  </span>
                </span>
                <span className='sysd-row-time' title={exactTime(ts)}>
                  {shortAge(ts) || '—'}
                </span>
                <span className='sysd-row-end' aria-hidden />
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
      <h1 className='ide-h1'>Get started</h1>
      <div className='ide-sub'>Two paths to your first verified PR.</div>

      {/* Rule 2 — a tinted note with a leading edge. This used to be a card
          whose entire border turned green, which read as an error state. */}
      <div className='sysd-onboard-note'>
        <strong>No projects yet</strong>
        <span>Link a repo or start in a sandbox. About 3 minutes to a verified PR.</span>
      </div>

      <div className='ide-h2'>Link a GitHub repo</div>
      <div className='ide-card'>
        <div className='sysd-onboard-body'>
          Your existing codebase. We install a webhook, snapshot the architecture, and work runs
          against it.
        </div>
        {/* Rule 1 — the single accent-filled commit action on this surface. */}
        <Link className='ide-btn' to='/integrations' style={{display: 'inline-flex', gap: 8}}>
          <svg viewBox='0 0 24 24' className='sysd-onboard-icon' aria-hidden>
            <path
              fill='currentColor'
              d='M12 .5C5.65.5.5 5.65.5 12c0 5.1 3.3 9.4 7.88 10.92.58.1.78-.25.78-.55 0-.27-.01-1-.02-1.96-3.2.7-3.88-1.54-3.88-1.54-.52-1.33-1.28-1.68-1.28-1.68-1.05-.72.08-.7.08-.7 1.16.08 1.77 1.19 1.77 1.19 1.03 1.77 2.7 1.26 3.36.96.1-.75.4-1.26.73-1.55-2.55-.29-5.24-1.28-5.24-5.7 0-1.26.45-2.29 1.19-3.09-.12-.3-.52-1.46.1-3.04 0 0 .97-.31 3.18 1.18A11.07 11.07 0 0 1 12 6.8c.98 0 1.98.13 2.91.38 2.2-1.5 3.18-1.18 3.18-1.18.63 1.58.23 2.74.11 3.04.74.8 1.19 1.83 1.19 3.09 0 4.42-2.7 5.4-5.26 5.69.41.36.78 1.06.78 2.13 0 1.54-.01 2.78-.01 3.16 0 .3.2.66.79.55C20.2 21.4 23.5 17.1 23.5 12 23.5 5.65 18.35.5 12 .5z'
            />
          </svg>
          Connect GitHub
        </Link>
      </div>

      <div className='ide-h2'>Try a sandbox</div>
      <div className='ide-card'>
        <div className='sysd-onboard-body'>
          A fresh repo and one of three preset requests. Free, and nothing is committed to your
          repos.
        </div>
        <Link className='ide-btn ghost' to='/welcome'>
          Pick a starter →
        </Link>
      </div>
    </div>
  );
}

function MoreSection({title, rows}: {title: string; rows: MoreRow[]}) {
  return (
    <>
      <div className='ide-h2'>{title}</div>
      <div className='ide-card sysd-list'>
        {rows.map((row) => (
          <Link
            key={row.to}
            to={row.to}
            className='sysd-row'
            style={{['--sysd-lead' as string]: '0px'}}
          >
            <span className='sysd-row-lead' aria-hidden />
            <span className='sysd-row-main'>
              <span className='sysd-row-title'>{row.label}</span>
              <span className='sysd-row-sub'>{row.sub}</span>
            </span>
            <span className='sysd-row-time' />
            <span className='sysd-row-end sysd-row-chev' aria-hidden>
              ›
            </span>
          </Link>
        ))}
      </div>
    </>
  );
}

export function IdeMoreMenu() {
  useDocumentTitle('Setup');
  const user = useAuthStore((s) => s.user);
  const isSuperAdmin = useAuthStore(selectIsSuperAdmin);
  const isOrgAdmin = useAuthStore(selectIsOrgAdmin);
  const tier = useAuthStore(selectNormalizedTier);
  const [unread, setUnread] = useState<number | null>(null);
  const [projects, setProjects] = useState<api.ProjectDto[]>([]);
  const [runCount, setRunCount] = useState<number | null>(null);
  const [advancedOpen, setAdvancedOpen] = useState(false);

  useEffect(() => {
    let cancelled = false;
    Promise.allSettled([
      api.getUnreadCount(),
      api.getProjects(),
      api.getRuns({page: 0, size: 1}),
    ]).then(([u, p, r]) => {
      if (cancelled) return;
      if (u.status === 'fulfilled') setUnread(u.value.count);
      if (p.status === 'fulfilled') setProjects(activeProjects(p.value));
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
      <h1 className='ide-h1'>Setup</h1>
      <div className='ide-sub'>Everything work needs before it can run.</div>

      <MoreSection title='Essentials' rows={setupEssentialRows(tier)} />

      <MoreSection title='Workspace' rows={setupWorkspaceRows(projects, runCount, unread)} />

      <div className='ide-setup-advanced'>
        <button
          type='button'
          className='ide-btn ghost'
          aria-expanded={advancedOpen}
          onClick={() => setAdvancedOpen((v) => !v)}
        >
          {advancedOpen ? 'Hide advanced' : 'Show advanced'}
        </button>
        {advancedOpen && (
          <>
            <MoreSection title='Account' rows={setupAccountRows(user?.email)} />
            <MoreSection
              title='Operations'
              rows={setupOperationRows(tier, isSuperAdmin, isOrgAdmin)}
            />
          </>
        )}
      </div>

      {isSuperAdmin && (
        <>
          <div className='ide-h2'>Operator controls</div>
          <div className='ide-card sysd-list'>
            {/* Operator reach is the row state, so it is the leading stripe —
                not red body text competing with the row title. */}
            <Link
              to='/admin'
              className='sysd-row is-bad'
              style={{['--sysd-lead' as string]: '0px'}}
            >
              <span className='sysd-row-lead' aria-hidden />
              <span className='sysd-row-main'>
                <span className='sysd-row-title'>Admin · SUPER_ADMIN</span>
                <span className='sysd-row-sub'>Platform-wide controls</span>
              </span>
              <span className='sysd-row-time' />
              <span className='sysd-row-end sysd-row-chev' aria-hidden>
                ›
              </span>
            </Link>
            <Link
              to='/benchmark'
              className='sysd-row is-info'
              style={{['--sysd-lead' as string]: '0px'}}
            >
              <span className='sysd-row-lead' aria-hidden />
              <span className='sysd-row-main'>
                <span className='sysd-row-title'>Benchmarks</span>
                {/* Load-bearing: this surface tracks external harness events, it
                    does not orchestrate them. Do not compress this away. */}
                <span className='sysd-row-sub'>Operator telemetry, not a customer workflow</span>
              </span>
              <span className='sysd-row-time' />
              <span className='sysd-row-end sysd-row-chev' aria-hidden>
                ›
              </span>
            </Link>
          </div>
        </>
      )}

      <p className='sysd-version'>ExecuteSpec · v2.0.0</p>
    </div>
  );
}
