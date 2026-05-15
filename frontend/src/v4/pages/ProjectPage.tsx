/**
 * Per-project agent thread.
 *
 * Replaces the v3 ProjectDashboardPage's tabbed (Compose / Pulse / Repos /
 * Runs / Code / Chat) stack with a single thread-first surface:
 *
 *   ┌──────────────────────────────────────┐
 *   │ Large title: <project name>          │
 *   │ status line: N repos · X runs · Y$   │
 *   │                                      │
 *   │ ┌── Mission ─────────────────────┐  │ ← latest run card
 *   │ │ verdict · score · actions       │  │
 *   │ └────────────────────────────────┘  │
 *   │                                      │
 *   │ Workspace (collapsed list group)     │ ← repos · 2 · open
 *   │ Recent runs (list rows)              │
 *   │ Activity (list rows)                 │
 *   │                                      │
 *   └──────────────────────────────────────┘
 *   Persistent AgentInput at the bottom is contextualised "In Hostel Eval".
 *
 * Power-user inspection surfaces (architecture, tech-debt, contracts,
 * etc.) live behind a single "Open workspace" link that drops the user
 * into the v3 dashboard at the appropriate tab. We're not duplicating
 * 1500 lines of those panels — we're surfacing them when needed.
 */
import {useEffect, useMemo, useState} from 'react';
import {Link, useNavigate, useParams} from 'react-router-dom';
import {
  ChevronRight,
  FolderGit2,
  Activity as ActivityIcon,
} from 'lucide-react';
import {
  getProject,
  getProjectDashboard,
  getProjectRepos,
  getRuns,
  type ProjectDto,
  type RunSummary,
} from '@/lib/api';
import type {ProjectDashboardDTO, ProjectRepoLink} from '@/types/project';
import {fmtCost} from '@/lib/utils';
import LargeTitle from '../components/LargeTitle';
import MissionCard, {type MissionRun} from '../components/MissionCard';
import StatusDot, {type StatusTone} from '../components/StatusDot';
import {useV4Context} from '../lib/useV4Context';

function toneFor(s: string): StatusTone {
  if (s === 'COMPLETE') return 'success';
  if (s === 'PARTIAL' || s === 'AWAITING_APPROVAL') return 'warn';
  if (s === 'FAILED' || s === 'ABORTED' || s === 'CANCELLED') return 'fail';
  return 'progress';
}

function timeAgo(iso?: string | null): string {
  if (!iso) return '';
  const diff = Date.now() - new Date(iso).getTime();
  if (Number.isNaN(diff)) return '';
  if (diff < 60_000) return 'just now';
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)}m`;
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)}h`;
  return `${Math.floor(diff / 86_400_000)}d`;
}

export default function ProjectPage() {
  const {projectId} = useParams<{projectId: string}>();
  const navigate = useNavigate();
  const [project, setProject] = useState<ProjectDto | null>(null);
  const [dashboard, setDashboard] = useState<ProjectDashboardDTO | null>(null);
  const [repos, setRepos] = useState<ProjectRepoLink[]>([]);
  const [runs, setRuns] = useState<RunSummary[]>([]);
  const setAgentCtx = useV4Context((s) => s.set);
  const clearAgentCtx = useV4Context((s) => s.clear);

  // Tell the shell-mounted AgentInput which project we're in. Resets on
  // unmount so navigating to /v4/home / /v4/library / etc. drops the chip.
  useEffect(() => {
    if (project?.name) {
      setAgentCtx({label: project.name, projectId});
    }
    return () => clearAgentCtx();
  }, [project?.name, projectId, setAgentCtx, clearAgentCtx]);

  useEffect(() => {
    if (!projectId) return;
    let cancelled = false;
    Promise.all([
      getProject(projectId),
      getProjectDashboard(projectId),
      getProjectRepos(projectId).catch(() => [] as ProjectRepoLink[]),
      getRuns({scope: 'mine', projectId, page: 0, size: 8}).catch(() => ({
        content: [] as RunSummary[],
        totalElements: 0,
        size: 0,
        number: 0,
      })),
    ])
      .then(([p, d, rs, runsRes]) => {
        if (cancelled) return;
        setProject(p);
        setDashboard(d);
        setRepos(rs);
        setRuns(runsRes.content);
      })
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, [projectId]);

  const heroRun: MissionRun | null = useMemo(() => {
    if (runs.length === 0) return null;
    const r = runs[0];
    return {
      runId: r.runId,
      runTitle: r.runTitle,
      status: r.status,
      verdict: null,
      verifiedScore: null,
      estimatedScore: null,
      completedAt: r.completedAt ?? null,
      startedAt: r.startedAt ?? null,
      actualCostUsd: r.actualCostUsd ?? null,
      githubPrUrl: r.githubPrUrl ?? r.scmPrUrl ?? null,
      projectId: r.projectId ?? null,
    };
  }, [runs]);

  const subtitle = dashboard
    ? `${repos.length} repo${repos.length === 1 ? '' : 's'}` +
      ` · ${dashboard.completedRuns ?? 0} completed` +
      (dashboard.totalCostUsd && dashboard.totalCostUsd > 0
        ? ` · ${fmtCost(dashboard.totalCostUsd)}`
        : '')
    : 'Loading…';

  return (
    <>
      {/* 2026-05-15 — Workbench escape hatch removed. Every inspect
          surface (architecture / tech-debt / review / repos / docs /
          memory / health) now lives at /v4/projects/:id/inspect/:panel
          or /v4/projects/:id/repos so users never leave v4. */}
      <LargeTitle title={project?.name ?? 'Workspace'} subtitle={subtitle} />

      {heroRun && (
        <div style={{marginBottom: 20}}>
          <MissionCard run={heroRun} />
        </div>
      )}

      {/* Repos rollup */}
      <section style={{marginBottom: 20}}>
        <div
          style={{
            display: 'flex',
            alignItems: 'baseline',
            justifyContent: 'space-between',
            padding: '0 4px 8px',
          }}
        >
          <h2
            style={{
              margin: 0,
              fontSize: 'var(--v4-text-base)',
              fontWeight: 600,
            }}
          >
            Repos
          </h2>
          <Link
            to={`/v4/projects/${projectId}/repos`}
            style={{
              fontSize: 'var(--v4-text-xs)',
              color: 'var(--v4-brand)',
              textDecoration: 'none',
            }}
          >
            Manage →
          </Link>
        </div>
        {repos.length === 0 ? (
          <button
            type='button'
            onClick={() => navigate(`/v4/projects/${projectId}/repos`)}
            className='v4-card is-tappable'
            style={{
              width: '100%',
              padding: '14px',
              border: '1px dashed var(--v4-border)',
              background: 'transparent',
              color: 'var(--v4-text-muted)',
              fontSize: 'var(--v4-text-sm)',
              fontFamily: 'inherit',
              cursor: 'pointer',
              textAlign: 'left',
            }}
          >
            No repos linked yet — tap to add one.
          </button>
        ) : (
          <div className='v4-list'>
            {repos.slice(0, 5).map((r) => (
              <div key={r.repo.id} className='v4-list-row' style={{cursor: 'default'}}>
                <FolderGit2 size={16} aria-hidden='true' style={{color: 'var(--v4-text-muted)'}} />
                <div style={{flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column', gap: 2}}>
                  <span
                    style={{
                      fontFamily: 'inherit',
                      fontSize: 'var(--v4-text-sm)',
                      fontWeight: 500,
                      whiteSpace: 'nowrap',
                      overflow: 'hidden',
                      textOverflow: 'ellipsis',
                    }}
                    title={r.repo.githubRepoFullName}
                  >
                    {r.repo.githubRepoFullName}
                  </span>
                  <span
                    style={{
                      fontSize: 'var(--v4-text-xs)',
                      color: 'var(--v4-text-subtle)',
                    }}
                  >
                    {r.repo.defaultBranch}
                    {r.repo.webhookInstalled ? ' · webhook ready' : ' · webhook off'}
                  </span>
                </div>
              </div>
            ))}
          </div>
        )}
      </section>

      {/* Recent runs */}
      {runs.length > 1 && (
        <section style={{marginBottom: 20}}>
          <div
            style={{
              display: 'flex',
              alignItems: 'baseline',
              justifyContent: 'space-between',
              padding: '0 4px 8px',
            }}
          >
            <h2
              style={{
                margin: 0,
                fontSize: 'var(--v4-text-base)',
                fontWeight: 600,
              }}
            >
              Recent runs
            </h2>
            <Link
              to={`/v4/activity?projectId=${projectId}`}
              style={{
                fontSize: 'var(--v4-text-xs)',
                color: 'var(--v4-brand)',
                textDecoration: 'none',
              }}
            >
              See all →
            </Link>
          </div>
          <div className='v4-list'>
            {runs.slice(1, 6).map((r) => (
              <Link
                key={r.runId}
                to={`/v4/projects/${projectId}/runs/${r.runId}`}
                className='v4-list-row v4-press'
                style={{textDecoration: 'none'}}
              >
                <StatusDot tone={toneFor(r.status)} />
                <div
                  style={{
                    flex: 1,
                    minWidth: 0,
                    display: 'flex',
                    flexDirection: 'column',
                    gap: 2,
                  }}
                >
                  <span
                    style={{
                      color: 'var(--v4-text)',
                      fontSize: 'var(--v4-text-sm)',
                      fontWeight: 500,
                      whiteSpace: 'nowrap',
                      overflow: 'hidden',
                      textOverflow: 'ellipsis',
                    }}
                  >
                    {r.runTitle ?? 'Untitled run'}
                  </span>
                  <span
                    style={{
                      color: 'var(--v4-text-subtle)',
                      fontSize: 'var(--v4-text-xs)',
                    }}
                  >
                    {r.status.toLowerCase()}
                    {r.actualCostUsd != null && r.actualCostUsd > 0 ? ` · ${fmtCost(r.actualCostUsd)}` : ''}
                  </span>
                </div>
                <span
                  style={{
                    color: 'var(--v4-text-subtle)',
                    fontSize: 'var(--v4-text-xs)',
                  }}
                >
                  {timeAgo(r.completedAt ?? r.startedAt)}
                </span>
                <ChevronRight size={14} aria-hidden='true' style={{color: 'var(--v4-text-subtle)'}} />
              </Link>
            ))}
          </div>
        </section>
      )}

      {/* Architecture / quality jump rows */}
      <section style={{marginBottom: 20}}>
        <div style={{padding: '0 4px 8px'}}>
          <h2 style={{margin: 0, fontSize: 'var(--v4-text-base)', fontWeight: 600}}>
            Inspect
          </h2>
        </div>
        <div className='v4-list'>
          <Link
            to={`/v4/projects/${projectId}/inspect/architecture`}
            className='v4-list-row v4-press'
            style={{textDecoration: 'none'}}
          >
            <ActivityIcon size={16} aria-hidden='true' style={{color: 'var(--v4-brand)'}} />
            <span style={{flex: 1, fontSize: 'var(--v4-text-sm)', fontWeight: 500}}>
              Architecture doc
            </span>
            <ChevronRight size={14} aria-hidden='true' style={{color: 'var(--v4-text-subtle)'}} />
          </Link>
          <Link
            to={`/v4/projects/${projectId}/inspect/tech-debt`}
            className='v4-list-row v4-press'
            style={{textDecoration: 'none'}}
          >
            <ActivityIcon size={16} aria-hidden='true' style={{color: 'var(--v4-warn)'}} />
            <span style={{flex: 1, fontSize: 'var(--v4-text-sm)', fontWeight: 500}}>
              Tech debt
            </span>
            <ChevronRight size={14} aria-hidden='true' style={{color: 'var(--v4-text-subtle)'}} />
          </Link>
          <Link
            to={`/v4/projects/${projectId}/inspect/review`}
            className='v4-list-row v4-press'
            style={{textDecoration: 'none'}}
          >
            <ActivityIcon size={16} aria-hidden='true' style={{color: 'var(--v4-fail)'}} />
            <span style={{flex: 1, fontSize: 'var(--v4-text-sm)', fontWeight: 500}}>
              Code review
            </span>
            <ChevronRight size={14} aria-hidden='true' style={{color: 'var(--v4-text-subtle)'}} />
          </Link>
        </div>
      </section>

      {/* AgentInput is mounted by AgenticShell. ProjectPage sets context
          via useV4Context above so the shell input shows "In <project>"
          and submits with this projectId pre-filled. */}
    </>
  );
}
