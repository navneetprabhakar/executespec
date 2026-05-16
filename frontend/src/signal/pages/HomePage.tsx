import {useEffect, useState} from 'react';
import {TopBar, TabBar, SectionHeader, IconButton} from '@/signal/components/Chrome';
import {Feed, Row, Card} from '@/signal/components/Primitives';
import {LiveHero, LiveHeroAction} from '@/signal/components/Compose';
import {SkeletonRow} from '@/signal/components/Loaders';
import {Search, Bell, Plus, Check, Chat as ChatIcon} from '@/signal/icons/icons';
import {useAuthStore} from '@/stores/useAuthStore';
import * as api from '@/lib/api';

type Maybe<T> = T | null | undefined;

function timeOfDayGreeting(): string {
  const h = new Date().getHours();
  if (h < 12) return 'Good morning';
  if (h < 18) return 'Good afternoon';
  return 'Good evening';
}

function fmtHHMM(d: Date): string {
  return `${d.getHours().toString().padStart(2, '0')}:${d.getMinutes().toString().padStart(2, '0')}`;
}

function relTime(input?: Maybe<string>): string {
  if (!input) return '—';
  const t = new Date(input).getTime();
  if (!t) return '—';
  const ms = Date.now() - t;
  const s = Math.round(ms / 1000);
  if (s < 60) return `${s}s ago`;
  const m = Math.round(s / 60);
  if (m < 60) return `${m}m ago`;
  const h = Math.round(m / 60);
  if (h < 24) return `${h}h ago`;
  const d = Math.round(h / 24);
  if (d < 30) return `${d}d ago`;
  return new Date(input).toLocaleDateString();
}

function statusToDot(s?: string): 'live' | 'mint' | 'coral' | 'signal' | 'lavender' {
  const x = (s || '').toUpperCase();
  if (
    x === 'CLASSIFYING' ||
    x === 'PLANNING' ||
    x === 'EXECUTING' ||
    x === 'RUNNING' ||
    x === 'AWAITING_APPROVAL'
  )
    return 'live';
  if (x === 'COMPLETED' || x === 'SUCCESS') return 'mint';
  if (x === 'FAILED' || x === 'CANCELLED') return 'coral';
  if (x === 'PARTIAL') return 'signal';
  return 'lavender';
}

function scoreKind(score?: Maybe<number>): 'good' | 'warn' | 'fail' | 'default' {
  if (score == null) return 'default';
  if (score >= 85) return 'good';
  if (score >= 70) return 'warn';
  if (score > 0) return 'fail';
  return 'default';
}

function shortRunId(id?: string): string {
  if (!id) return 'R-—';
  return `R-${id.slice(0, 6)}`;
}

function formatElapsed(ms?: number): string {
  if (!ms || ms < 0) return '0s';
  const totalS = Math.floor(ms / 1000);
  const m = Math.floor(totalS / 60);
  const s = totalS % 60;
  if (m === 0) return `${s}s`;
  return `${m}m ${s}s`;
}

export default function HomePage() {
  const user = useAuthStore((s) => s.user);
  const permissions = useAuthStore((s) => s.permissions);
  const [now, setNow] = useState(new Date());
  const [liveRun, setLiveRun] = useState<api.RunDetail | null>(null);
  // null = still loading; [] = loaded with no results. Without this distinction
  // the page renders the empty state immediately on mount and the user thinks
  // the API is broken.
  const [recent, setRecent] = useState<api.RunSummary[] | null>(null);
  const [projects, setProjects] = useState<api.ProjectDto[] | null>(null);

  // Refresh the clock at 60s so the topbar stays current without re-fetching.
  useEffect(() => {
    const t = window.setInterval(() => setNow(new Date()), 60_000);
    return () => clearInterval(t);
  }, []);

  // Initial + periodic fetches. While a run is live we re-poll every 5s.
  useEffect(() => {
    let cancelled = false;
    let timer: number | undefined;

    async function tick() {
      try {
        const [runsPage, allProjects] = await Promise.all([
          api.getRuns({page: 0, size: 8}).catch(() => null),
          api.getProjects().catch(() => [] as api.ProjectDto[]),
        ]);
        if (cancelled) return;

        const runs = runsPage?.content || [];
        // Live = first non-terminal run we can find.
        const liveSummary = runs.find((r) => {
          const s = (r.status || '').toUpperCase();
          return (
            s === 'CLASSIFYING' ||
            s === 'PLANNING' ||
            s === 'EXECUTING' ||
            s === 'RUNNING' ||
            s === 'AWAITING_APPROVAL' ||
            s === 'PROVIDER_SWITCHING'
          );
        });
        if (liveSummary) {
          // Hydrate full detail for the hero card so we get tasks + cost.
          try {
            const detail = await api.getRun(liveSummary.runId);
            if (!cancelled) setLiveRun(detail);
          } catch {
            if (!cancelled) setLiveRun(null);
          }
        } else if (!cancelled) {
          setLiveRun(null);
        }

        if (!cancelled) {
          setRecent(runs.filter((r) => r.runId !== liveSummary?.runId).slice(0, 6));
          setProjects(allProjects.filter((p) => !p.archivedAt).slice(0, 4));
        }
      } catch {
        /* On error, fall out of the loading state so the user sees the empty
           state instead of skeletons spinning forever. Existing data is kept
           if we already had any. */
        if (!cancelled) {
          setRecent((prev) => prev ?? []);
          setProjects((prev) => prev ?? []);
        }
      }
      timer = window.setTimeout(tick, liveRun ? 5_000 : 30_000);
    }
    void tick();
    return () => {
      cancelled = true;
      if (timer) clearTimeout(timer);
    };
  }, [liveRun]);

  const displayName = (user?.firstName || user?.email?.split('@')[0] || 'there').trim();
  const liveTasks = (liveRun as unknown as {tasks?: Array<{status?: string}>})?.tasks || [];
  const liveDoneCount = liveTasks.filter(
    (t) => t.status === 'COMPLETED' || t.status === 'DONE',
  ).length;
  const liveTotal = liveTasks.length;
  const liveSpent =
    (liveRun as unknown as {actualCost?: number; totalCost?: number})?.actualCost ??
    (liveRun as unknown as {totalCost?: number})?.totalCost ??
    0;
  const liveElapsed = formatElapsed(
    (liveRun as unknown as {elapsedMs?: number; durationMs?: number})?.elapsedMs ||
      (liveRun as unknown as {durationMs?: number})?.durationMs ||
      0,
  );
  const liveUserReq = (liveRun as unknown as {userRequest?: string})?.userRequest || '—';

  const initials =
    user?.firstName && user?.lastName
      ? `${user.firstName[0]}${user.lastName[0]}`.toUpperCase()
      : (user?.email?.slice(0, 2).toUpperCase() ?? 'EX');

  return (
    <>
      <TopBar
        title='Today'
        sub={`${timeOfDayGreeting()}, ${displayName} · ${fmtHHMM(now)}`}
        actions={
          <>
            <IconButton to='/search' ariaLabel='Search'>
              <Search size={18} />
            </IconButton>
            <IconButton to='/notifications' ariaLabel='Notifications' badge>
              <Bell size={18} />
            </IconButton>
          </>
        }
        avatar={initials}
      />
      <Feed>
        {liveRun && (
          <LiveHero
            runId={shortRunId((liveRun as unknown as {runId?: string}).runId || '')}
            title={liveUserReq.slice(0, 120)}
            tasks={liveTotal ? `${liveDoneCount}/${liveTotal}` : '—'}
            elapsed={liveElapsed}
            spent={`$${liveSpent.toFixed(2)}`}
            actions={
              <>
                <LiveHeroAction
                  to={`/plan/${(liveRun as unknown as {runId?: string}).runId || ''}`}
                >
                  Plan
                </LiveHeroAction>
                <LiveHeroAction
                  to={`/live/${(liveRun as unknown as {runId?: string}).runId || ''}`}
                  primary
                >
                  Watch live
                </LiveHeroAction>
              </>
            }
          />
        )}

        <div className='signal-quick-grid'>
          <a className='signal-quick-card' href='/compose'>
            <div className='icon-bg'>
              <Plus size={16} />
            </div>
            <h4>New spec</h4>
            <div className='count'>⌘ + N</div>
          </a>
          <a className='signal-quick-card mint' href='/projects'>
            <div className='icon-bg'>
              <svg
                viewBox='0 0 24 24'
                style={{width: 16, height: 16}}
                fill='none'
                stroke='currentColor'
                strokeWidth={2}
                strokeLinecap='round'
              >
                <path d='M3 7l9-4 9 4M3 7v10l9 4m-9-14l9 4m0 0l9-4m-9 4v14' />
              </svg>
            </div>
            <h4>Projects</h4>
            <div className='count'>{projects === null ? '…' : `${projects.length} active`}</div>
          </a>
          <a className='signal-quick-card coral' href='/runs'>
            <div className='icon-bg'>
              <Check size={16} />
            </div>
            <h4>All runs</h4>
            {/* Surface the monthly run cap up front so FREE users discover the
                limit BEFORE submitting their 6th spec. Show used/limit when a
                finite cap exists; collapse to a friendlier label otherwise. */}
            <div className='count'>
              {permissions
                ? permissions.monthlyRunLimit === -1
                  ? `${permissions.currentRunCount} this cycle`
                  : `${permissions.currentRunCount} / ${permissions.monthlyRunLimit} this cycle`
                : recent === null
                  ? '…'
                  : recent.length
                    ? // SDET 2026-05-16 (BUG-021) — only append the "+" qualifier when we actually
                      // hit the home-fetch page size of 6. With <6 runs we know the count is exact,
                      // so "3 total" reads cleaner than "3+ total".
                      recent.length >= 6
                      ? `${recent.length}+ total`
                      : `${recent.length} total`
                    : 'No runs yet'}
            </div>
          </a>
          <a
            className='signal-quick-card lavender'
            href={projects?.[0] ? `/projects/${projects[0].id}/chat` : '/projects'}
          >
            <div className='icon-bg'>
              <ChatIcon size={16} />
            </div>
            <h4>Project chat</h4>
            <div className='count'>
              {projects?.[0]?.name || (projects === null ? '…' : 'no project')}
            </div>
          </a>
        </div>

        <SectionHeader action={<a href='/runs'>See all</a>}>Recent runs</SectionHeader>
        {recent === null ? (
          <>
            <SkeletonRow lines={2} />
            <SkeletonRow lines={2} />
            <SkeletonRow lines={2} />
          </>
        ) : recent.length === 0 ? (
          <Card>
            <p style={{fontSize: 14, color: 'var(--signal-text-dim)', margin: 0, lineHeight: 1.5}}>
              No runs yet.{' '}
              <a href='/compose' style={{color: 'var(--signal-coral)'}}>
                Submit your first spec →
              </a>
            </p>
          </Card>
        ) : (
          recent.map((r) => {
            const sc = (r as unknown as {verifiedScore?: number})?.verifiedScore;
            const cost =
              (r as unknown as {actualCost?: number; totalCost?: number})?.actualCost ??
              (r as unknown as {totalCost?: number})?.totalCost;
            const status = (r.status || '').toUpperCase();
            const isLive =
              status === 'CLASSIFYING' ||
              status === 'PLANNING' ||
              status === 'EXECUTING' ||
              status === 'AWAITING_APPROVAL' ||
              status === 'RUNNING';
            return (
              <Row
                key={r.runId}
                to={isLive ? `/live/${r.runId}` : `/result/${r.runId}`}
                dot={statusToDot(status)}
                title={(() => {
                  // Prefer the agent-generated runTitle (concise summary,
                  // <60 chars) over the raw userRequest, which is typically
                  // 200+ chars and looks terrible truncated in a list row.
                  const rAny = r as unknown as {
                    runTitle?: string;
                    title?: string;
                    userRequest?: string;
                  };
                  return (
                    rAny.runTitle?.trim() ||
                    rAny.title?.trim() ||
                    rAny.userRequest?.trim() ||
                    `Run ${shortRunId(r.runId)}`
                  );
                })()}
                meta={
                  <>
                    <span className='mono'>{shortRunId(r.runId)}</span>
                    <span>·</span>
                    <span>{relTime((r as unknown as {createdAt?: string})?.createdAt)}</span>
                  </>
                }
                trailing={sc != null ? `${Math.round(sc)}%` : isLive ? 'live' : '—'}
                trailingKind={scoreKind(sc)}
                trailingLabel={cost != null ? `$${cost.toFixed(2)}` : undefined}
              />
            );
          })
        )}

        <SectionHeader action={<a href='/projects'>All {projects?.length || ''}</a>}>
          Your projects
        </SectionHeader>
        {projects === null ? (
          <>
            <SkeletonRow lines={2} />
            <SkeletonRow lines={2} />
          </>
        ) : projects.length === 0 ? (
          <Card>
            <p style={{fontSize: 14, color: 'var(--signal-text-dim)', margin: 0, lineHeight: 1.5}}>
              You don't have a project yet.{' '}
              <a href='/projects' style={{color: 'var(--signal-coral)'}}>
                Create one →
              </a>
            </p>
          </Card>
        ) : (
          projects.slice(0, 3).map((p) => (
            <Row
              key={p.id}
              to={`/projects/${p.id}`}
              dot='lavender'
              title={p.name}
              meta={
                <>
                  <span>{(p as unknown as {repoCount?: number}).repoCount ?? 0} repos</span>
                  <span>·</span>
                  {(() => {
                    const n = (p as unknown as {totalRuns?: number}).totalRuns ?? 0;
                    return (
                      <span>
                        {n} {n === 1 ? 'run' : 'runs'}
                      </span>
                    );
                  })()}
                </>
              }
              showChev
            />
          ))
        )}
      </Feed>
      <TabBar active='home' />
    </>
  );
}
