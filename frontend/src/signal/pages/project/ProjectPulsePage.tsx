import {useEffect, useState} from 'react';
import {useParams} from 'react-router-dom';
import {TopBar, SectionHeader, IconButton} from '@/signal/components/Chrome';
import {Feed, Row, Card} from '@/signal/components/Primitives';
import {LiveHero, LiveHeroAction, PrCard} from '@/signal/components/Compose';
import {SkeletonRow} from '@/signal/components/Loaders';
import {ProjectTabs} from './ProjectTabs';
import {Share, More} from '@/signal/icons/icons';
import {useProjectMeta} from '@/signal/hooks/useProjectMeta';
import * as api from '@/lib/api';

function relTime(input?: string): string {
  if (!input) return '—';
  const t = new Date(input).getTime();
  if (!t) return '—';
  const s = Math.round((Date.now() - t) / 1000);
  if (s < 60) return `${s}s ago`;
  const m = Math.round(s / 60);
  if (m < 60) return `${m}m ago`;
  const h = Math.round(m / 60);
  if (h < 24) return `${h}h ago`;
  const d = Math.round(h / 24);
  return `${d}d ago`;
}

function statusToDot(s?: string): 'live' | 'mint' | 'coral' | 'signal' | 'lavender' {
  const x = (s || '').toUpperCase();
  if (x === 'CLASSIFYING' || x === 'PLANNING' || x === 'EXECUTING' || x === 'AWAITING_APPROVAL' || x === 'RUNNING') return 'live';
  if (x === 'COMPLETED') return 'mint';
  if (x === 'FAILED' || x === 'CANCELLED') return 'coral';
  if (x === 'PARTIAL') return 'signal';
  return 'lavender';
}

function scoreKind(score?: number | null): 'good' | 'warn' | 'fail' | 'default' {
  if (score == null) return 'default';
  if (score >= 85) return 'good';
  if (score >= 70) return 'warn';
  if (score > 0) return 'fail';
  return 'default';
}

function shortRunId(id?: string): string {
  return id ? `R-${id.slice(0, 6)}` : 'R-—';
}

export default function ProjectPulsePage() {
  const {projectId} = useParams();
  const id = projectId || '';
  const project = useProjectMeta(id);
  const [dash, setDash] = useState<unknown>(null);
  // null = still loading; [] = loaded with no results.
  const [recentRuns, setRecentRuns] = useState<api.RunSummary[] | null>(null);
  const [repos, setRepos] = useState<Array<Record<string, unknown>> | null>(null);
  const [health, setHealth] = useState<api.WorkspaceHealthReport | null>(null);
  const [err, setErr] = useState('');

  useEffect(() => {
    if (!id) return;
    let cancelled = false;
    Promise.allSettled([
      api.getProjectDashboard(id),
      api.getRuns({projectId: id, page: 0, size: 6} as Parameters<typeof api.getRuns>[0]),
      api.getProjectRepos(id),
      api.getWorkspaceHealth(id),
    ]).then(([d, r, rp, h]) => {
      if (cancelled) return;
      if (d.status === 'fulfilled') setDash(d.value);
      if (r.status === 'fulfilled') setRecentRuns(r.value.content); else setRecentRuns([]);
      if (rp.status === 'fulfilled') setRepos(rp.value as unknown as Array<Record<string, unknown>>); else setRepos([]);
      if (h.status === 'fulfilled') setHealth(h.value);
      if (d.status === 'rejected' && r.status === 'rejected') {
        setErr('Failed to load project.');
      }
    });
    return () => {
      cancelled = true;
    };
  }, [id]);

  const live = (recentRuns || []).find((r) => {
    const s = (r.status || '').toUpperCase();
    return s === 'CLASSIFYING' || s === 'PLANNING' || s === 'EXECUTING' || s === 'AWAITING_APPROVAL' || s === 'RUNNING';
  });

  const dashAny = (dash || {}) as {avgScore?: number; avgDurationMs?: number; totalSpent30d?: number; mergedPrs30d?: number; openPrs?: Array<{repoFullName?: string; prNumber?: number; title?: string; status?: string; additions?: number; deletions?: number}>; recentRuns?: api.RunSummary[]};
  const avgScore = dashAny.avgScore != null ? `${Math.round(dashAny.avgScore)}%` : '—';
  const avgRunTime = dashAny.avgDurationMs
    ? (() => {
        const m = Math.floor(dashAny.avgDurationMs! / 60000);
        const s = Math.floor((dashAny.avgDurationMs! % 60000) / 1000);
        return `${m}m ${s}s`;
      })()
    : '—';
  const spent = dashAny.totalSpent30d != null ? `$${dashAny.totalSpent30d.toFixed(2)}` : '—';
  const merged = dashAny.mergedPrs30d != null ? `${dashAny.mergedPrs30d} / 30d` : '—';
  const openPrs = dashAny.openPrs || [];
  const projectName = project?.name || 'project';
  const memberCount = (project as unknown as {memberCount?: number})?.memberCount;

  return (
    <>
      <TopBar
        title={projectName}
        sub={repos === null ? 'loading…' : `${repos.length} ${repos.length === 1 ? 'repo' : 'repos'}${memberCount != null ? ` · ${memberCount} collaborators` : ''}`}
        back
        backTo='/projects'
        actions={
          <>
            <IconButton ariaLabel='Share'><Share size={18} /></IconButton>
            <IconButton ariaLabel='More'><More size={18} /></IconButton>
          </>
        }
      />
      <Feed>
        <ProjectTabs active='pulse' />

        {err && <div style={{background: 'var(--signal-coral-soft)', color: 'var(--signal-coral-text)', border: '1px solid var(--signal-coral)', borderRadius: 10, padding: '10px 14px', marginBottom: 12, fontSize: 13}}>{err}</div>}

        {live && (
          <LiveHero
            runId={`${shortRunId(live.runId)} · LIVE`}
            title={(live as unknown as {userRequest?: string})?.userRequest?.slice(0, 120) || '—'}
            tasks={(() => {
              const tasks = (live as unknown as {tasks?: Array<{status?: string}>})?.tasks || [];
              const done = tasks.filter((t) => t.status === 'COMPLETED' || t.status === 'DONE').length;
              return tasks.length ? `${done}/${tasks.length}` : '—';
            })()}
            elapsed={(() => {
              const ms = (live as unknown as {elapsedMs?: number; durationMs?: number})?.elapsedMs ?? (live as unknown as {durationMs?: number})?.durationMs ?? 0;
              const m = Math.floor(ms / 60000);
              const s = Math.floor((ms % 60000) / 1000);
              return m > 0 ? `${m}m ${s}s` : `${s}s`;
            })()}
            spent={(() => {
              const c = (live as unknown as {actualCost?: number; totalCost?: number})?.actualCost ?? (live as unknown as {totalCost?: number})?.totalCost ?? 0;
              return `$${c.toFixed(2)}`;
            })()}
            actions={
              <>
                <LiveHeroAction to={`/plan/${live.runId}`}>Plan</LiveHeroAction>
                <LiveHeroAction to={`/live/${live.runId}`} primary>Watch live</LiveHeroAction>
              </>
            }
          />
        )}

        <div className='signal-quick-grid'>
          <div className='signal-quick-card mint'>
            <div className='icon-bg'>
              <svg viewBox='0 0 24 24' style={{width: 16, height: 16}} fill='none' stroke='currentColor' strokeWidth={2} strokeLinecap='round'>
                <path d='M9 11l3 3L22 4' />
              </svg>
            </div>
            <h4>Avg score</h4>
            <div className='count' style={{color: 'var(--signal-ink)', fontSize: 17, fontWeight: 600}}>{avgScore}</div>
          </div>
          <div className='signal-quick-card'>
            <div className='icon-bg'>
              <svg viewBox='0 0 24 24' style={{width: 16, height: 16}} fill='none' stroke='currentColor' strokeWidth={2} strokeLinecap='round'>
                <circle cx='12' cy='12' r='10' />
                <path d='M12 6v6l4 2' />
              </svg>
            </div>
            <h4>Avg run time</h4>
            <div className='count' style={{color: 'var(--signal-ink)', fontSize: 17, fontWeight: 600}}>{avgRunTime}</div>
          </div>
          <div className='signal-quick-card coral'>
            <div className='icon-bg'>
              <svg viewBox='0 0 24 24' style={{width: 16, height: 16}} fill='none' stroke='currentColor' strokeWidth={2} strokeLinecap='round'>
                <line x1='12' y1='1' x2='12' y2='23' />
                <path d='M17 5H9.5a3.5 3.5 0 0 0 0 7h5a3.5 3.5 0 0 1 0 7H6' />
              </svg>
            </div>
            <h4>Spent · 30d</h4>
            <div className='count' style={{color: 'var(--signal-ink)', fontSize: 17, fontWeight: 600}}>{spent}</div>
          </div>
          <div className='signal-quick-card lavender'>
            <div className='icon-bg'>
              <svg viewBox='0 0 24 24' style={{width: 16, height: 16}} fill='none' stroke='currentColor' strokeWidth={2} strokeLinecap='round'>
                <path d='M6 3v18M18 3v18M3 6h18M3 18h18' />
              </svg>
            </div>
            <h4>PRs merged</h4>
            <div className='count' style={{color: 'var(--signal-ink)', fontSize: 17, fontWeight: 600}}>{merged}</div>
          </div>
        </div>

        <SectionHeader action={<a href={`/projects/${id}/repos`}>View all</a>}>Workspace health</SectionHeader>
        {repos === null ? (
          <>
            <SkeletonRow lines={2} />
            <SkeletonRow lines={2} />
          </>
        ) : (health?.repos?.length ?? 0) === 0 && repos.length === 0 ? (
          <Card>
            <p style={{fontSize: 13.5, color: 'var(--signal-text-dim)', margin: 0}}>
              No repos linked yet. <a href={`/projects/${id}/repos`} style={{color: 'var(--signal-coral)'}}>Link a repo →</a>
            </p>
          </Card>
        ) : (
          (health?.repos || repos.map((r) => ({repoId: (r as unknown as {id?: string}).id, name: (r as unknown as {name?: string}).name, headSha: (r as unknown as {headSha?: string}).headSha, webhookInstalled: false, openPrs: 0}))).slice(0, 4).map((r) => {
            const rAny = r as unknown as {repoId?: string; name?: string; headSha?: string; commitsBehind?: number; webhookInstalled?: boolean; openPrs?: number};
            const driftLabel = rAny.commitsBehind != null && rAny.commitsBehind > 0 ? `drift +${rAny.commitsBehind}` : (rAny.headSha?.slice(0, 7) ?? 'main');
            const dot: 'mint' | 'signal' | 'coral' = rAny.commitsBehind && rAny.commitsBehind > 0 ? 'signal' : !rAny.webhookInstalled ? 'coral' : 'mint';
            return (
              <Row
                key={rAny.repoId || rAny.name}
                to={`/projects/${id}/repos`}
                dot={dot}
                title={rAny.name || '—'}
                meta={
                  <>
                    <span className='mono'>{driftLabel}</span>
                    <span>·</span>
                    <span style={{color: rAny.webhookInstalled ? 'var(--signal-mint-text-on-light)' : 'var(--signal-coral-text)'}}>
                      {rAny.webhookInstalled ? '✓ webhook' : '○ install webhook'}
                    </span>
                    {(rAny.openPrs ?? 0) > 0 && (
                      <>
                        <span>·</span>
                        <span>{rAny.openPrs} {rAny.openPrs === 1 ? 'PR' : 'PRs'}</span>
                      </>
                    )}
                  </>
                }
                showChev
              />
            );
          })
        )}

        {openPrs.length > 0 && (
          <>
            <SectionHeader action={<span>{openPrs.length} open</span>}>Open pull requests</SectionHeader>
            {openPrs.slice(0, 4).map((pr, i) => (
              <PrCard
                key={`${pr.repoFullName}-${pr.prNumber}-${i}`}
                label={`PR · ${(pr.status || 'OPEN').toUpperCase()}`}
                title={pr.title || `PR #${pr.prNumber}`}
                repo={`${pr.repoFullName}#${pr.prNumber}`}
                ciStatus={<span className='ci-ok'>● PR</span>}
                add={pr.additions != null ? `+${pr.additions}` : undefined}
                rem={pr.deletions != null ? `−${pr.deletions}` : undefined}
              />
            ))}
          </>
        )}

        <SectionHeader action={<a href={`/projects/${id}/runs`}>All {recentRuns?.length ?? ''}</a>}>Recent runs</SectionHeader>
        {recentRuns === null ? (
          <>
            <SkeletonRow lines={2} />
            <SkeletonRow lines={2} />
            <SkeletonRow lines={2} />
          </>
        ) : recentRuns.length === 0 ? (
          <Card>
            <p style={{fontSize: 13.5, color: 'var(--signal-text-dim)', margin: 0}}>No runs yet in this project.</p>
          </Card>
        ) : (
          recentRuns.slice(0, 5).map((r) => {
            const sc = (r as unknown as {verifiedScore?: number}).verifiedScore;
            const cost = (r as unknown as {actualCost?: number; totalCost?: number}).actualCost ?? (r as unknown as {totalCost?: number}).totalCost;
            const s = (r.status || '').toUpperCase();
            const isLive = s === 'CLASSIFYING' || s === 'PLANNING' || s === 'EXECUTING' || s === 'AWAITING_APPROVAL' || s === 'RUNNING';
            return (
              <Row
                key={r.runId}
                to={isLive ? `/live/${r.runId}` : `/result/${r.runId}`}
                dot={statusToDot(s)}
                title={r.userRequest || (r as unknown as {runTitle?: string}).runTitle || `Run ${shortRunId(r.runId)}`}
                meta={<><span className='mono'>{shortRunId(r.runId)}</span><span>·</span><span>{relTime((r as unknown as {createdAt?: string}).createdAt)}</span></>}
                trailing={sc != null ? `${Math.round(sc)}%` : isLive ? 'live' : '—'}
                trailingKind={scoreKind(sc)}
                trailingLabel={cost != null ? `$${cost.toFixed(2)}` : undefined}
              />
            );
          })
        )}
      </Feed>
    </>
  );
}
