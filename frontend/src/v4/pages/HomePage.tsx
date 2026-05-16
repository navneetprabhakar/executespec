/**
 * Agentic v2 Home — the agent's home screen.
 *
 * Mobile-first hero stack:
 *   1. Greeting / status line ("Good evening, Navneet · agent ready")
 *   2. MissionCard of the most-recent run (verdict-first, score, actions)
 *   3. Activity stream — last 5 runs as compact list rows
 *   4. Quick-jump tiles — Catalog, Migrations, New project
 *
 * The persistent AgentInput at the viewport bottom is the primary creation
 * surface — type a spec, hit ↑, agent takes it from there. No giant
 * marketing copy, no five tabs at the top.
 *
 * Polls /api/runs (mine, page 0, size 6) once on mount; refreshes on tab
 * focus. SSE wiring lives at the page-detail level, not here.
 */
import {useEffect, useMemo, useState} from 'react';
import {Link, useNavigate} from 'react-router-dom';
import {ArrowUpRight, Library, GitBranch, Plus} from 'lucide-react';
import {getRuns, getProjects, type RunSummary, type ProjectDto} from '@/lib/api';
import {useAuthStore} from '@/stores/useAuthStore';
import LargeTitle from '../components/LargeTitle';
import MissionCard, {type MissionRun} from '../components/MissionCard';
import StatusDot, {type StatusTone} from '../components/StatusDot';

function greeting(name?: string | null): string {
  const hr = new Date().getHours();
  const part =
    hr < 5
      ? 'Working late'
      : hr < 12
        ? 'Good morning'
        : hr < 18
          ? 'Good afternoon'
          : 'Good evening';
  const first = (name || '').split(/\s+/)[0]?.split('@')[0];
  return first ? `${part}, ${first}` : part;
}

function toneFor(status: string): StatusTone {
  if (status === 'COMPLETE') return 'success';
  if (status === 'PARTIAL') return 'warn';
  if (status === 'FAILED' || status === 'ABORTED' || status === 'CANCELLED') return 'fail';
  if (status === 'AWAITING_APPROVAL') return 'warn';
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

export default function HomePage() {
  const user = useAuthStore((s) => s.user);
  const navigate = useNavigate();
  const [runs, setRuns] = useState<RunSummary[] | null>(null);
  const [projects, setProjects] = useState<ProjectDto[]>([]);

  useEffect(() => {
    let cancelled = false;
    Promise.all([getRuns({scope: 'mine', page: 0, size: 6}), getProjects()])
      .then(([rRes, pRes]) => {
        if (cancelled) return;
        setRuns(rRes.content);
        setProjects(pRes);
      })
      .catch(() => {
        if (!cancelled) setRuns([]);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const activeCount = useMemo(
    () =>
      (runs ?? []).filter((r) =>
        ['WORKING', 'PLANNING', 'REVIEWING', 'AWAITING_APPROVAL'].includes(r.status),
      ).length,
    [runs],
  );

  const lastRun = runs?.[0];
  const heroRun: MissionRun | null = lastRun
    ? {
        runId: lastRun.runId,
        runTitle: lastRun.runTitle,
        status: lastRun.status,
        verdict: null,
        verifiedScore: null,
        estimatedScore: null,
        completedAt: lastRun.completedAt ?? null,
        startedAt: lastRun.startedAt ?? null,
        actualCostUsd: lastRun.actualCostUsd ?? null,
        githubPrUrl: lastRun.githubPrUrl ?? lastRun.scmPrUrl ?? null,
        projectId: lastRun.projectId ?? null,
      }
    : null;

  const projectsById = useMemo(() => {
    const m = new Map<string, ProjectDto>();
    projects.forEach((p) => m.set(p.id, p));
    return m;
  }, [projects]);

  return (
    <>
      <LargeTitle
        title={greeting(user?.email)}
        subtitle={
          runs == null
            ? 'Catching up on activity…'
            : activeCount > 0
              ? `Agent active · ${activeCount} run${activeCount === 1 ? '' : 's'} in flight`
              : runs.length === 0
                ? 'Ready when you are.'
                : 'Agent ready.'
        }
      />

      {heroRun && (
        <div style={{marginBottom: 20}}>
          <MissionCard run={heroRun} />
        </div>
      )}

      {/* Recent activity — compact list rows */}
      {runs && runs.length > 1 && (
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
                color: 'var(--v4-text)',
              }}
            >
              Recent
            </h2>
            <Link
              to='/v4/activity'
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
            {runs.slice(1, 5).map((r) => {
              const proj = r.projectId ? projectsById.get(r.projectId) : null;
              const dest = r.projectId
                ? `/v4/projects/${r.projectId}/runs/${r.runId}`
                : `/v4/runs/${r.runId}`;
              return (
                <Link
                  key={r.runId}
                  to={dest}
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
                      {proj?.name ?? 'standalone'} · {r.status.toLowerCase()}
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
                </Link>
              );
            })}
          </div>
        </section>
      )}

      {/* Project tiles — 1 col phone, 2 col tablet+, capped at 4 */}
      {projects.length > 0 && (
        <section style={{marginBottom: 20}}>
          <div style={{padding: '0 4px 8px'}}>
            <h2
              style={{
                margin: 0,
                fontSize: 'var(--v4-text-base)',
                fontWeight: 600,
                color: 'var(--v4-text)',
              }}
            >
              Workspaces
            </h2>
          </div>
          <div
            style={{
              display: 'grid',
              gridTemplateColumns: 'repeat(auto-fill, minmax(min(220px, 100%), 1fr))',
              gap: 12,
            }}
          >
            {projects.slice(0, 4).map((p) => (
              <Link
                key={p.id}
                to={`/v4/projects/${p.id}`}
                className='v4-card is-tappable'
                style={{
                  padding: '14px',
                  textDecoration: 'none',
                  color: 'inherit',
                  display: 'flex',
                  flexDirection: 'column',
                  gap: 8,
                }}
              >
                <div
                  style={{
                    display: 'flex',
                    alignItems: 'center',
                    gap: 8,
                  }}
                >
                  <span
                    aria-hidden='true'
                    style={{
                      display: 'inline-flex',
                      alignItems: 'center',
                      justifyContent: 'center',
                      width: 24,
                      height: 24,
                      borderRadius: 6,
                      background: 'var(--v4-brand-soft)',
                      color: 'var(--v4-brand)',
                    }}
                  >
                    <GitBranch size={12} />
                  </span>
                  <span
                    style={{
                      fontSize: 'var(--v4-text-sm)',
                      fontWeight: 600,
                      whiteSpace: 'nowrap',
                      overflow: 'hidden',
                      textOverflow: 'ellipsis',
                    }}
                  >
                    {p.name}
                  </span>
                </div>
                <div
                  style={{
                    display: 'flex',
                    gap: 12,
                    fontSize: 'var(--v4-text-xs)',
                    color: 'var(--v4-text-subtle)',
                  }}
                >
                  <span>{p.aiProvider?.toLowerCase() ?? 'mixed'}</span>
                  {p.status && <span>· {p.status.toLowerCase()}</span>}
                </div>
              </Link>
            ))}
          </div>
        </section>
      )}

      {/* Catalog tile + new project tile */}
      <section
        style={{
          display: 'grid',
          gridTemplateColumns: 'repeat(auto-fill, minmax(min(220px, 100%), 1fr))',
          gap: 12,
          marginBottom: 20,
        }}
      >
        <Link
          to='/v4/library'
          className='v4-card is-tappable'
          style={{
            padding: '16px',
            textDecoration: 'none',
            color: 'inherit',
            display: 'flex',
            alignItems: 'center',
            gap: 12,
          }}
        >
          <span
            aria-hidden='true'
            style={{
              display: 'inline-flex',
              alignItems: 'center',
              justifyContent: 'center',
              width: 32,
              height: 32,
              borderRadius: 10,
              background: 'var(--v4-brand-soft)',
              color: 'var(--v4-brand)',
            }}
          >
            <Library size={18} />
          </span>
          <div style={{flex: 1, minWidth: 0}}>
            <div
              style={{
                fontSize: 'var(--v4-text-sm)',
                fontWeight: 600,
                color: 'var(--v4-text)',
              }}
            >
              Browse catalog
            </div>
            <div
              style={{
                fontSize: 'var(--v4-text-xs)',
                color: 'var(--v4-text-subtle)',
              }}
            >
              Ready-made solutions + migrations
            </div>
          </div>
          <ArrowUpRight size={16} aria-hidden='true' style={{color: 'var(--v4-text-subtle)'}} />
        </Link>
        <button
          type='button'
          onClick={() => navigate('/runs/new')}
          className='v4-card is-tappable'
          style={{
            padding: '16px',
            border: '1px solid var(--v4-border)',
            display: 'flex',
            alignItems: 'center',
            gap: 12,
            fontFamily: 'inherit',
            textAlign: 'left',
            cursor: 'pointer',
            color: 'inherit',
          }}
        >
          <span
            aria-hidden='true'
            style={{
              display: 'inline-flex',
              alignItems: 'center',
              justifyContent: 'center',
              width: 32,
              height: 32,
              borderRadius: 10,
              background: 'var(--v4-surface-up)',
              color: 'var(--v4-text)',
              border: '1px dashed var(--v4-border)',
            }}
          >
            <Plus size={18} />
          </span>
          <div style={{flex: 1, minWidth: 0}}>
            <div
              style={{
                fontSize: 'var(--v4-text-sm)',
                fontWeight: 600,
              }}
            >
              Start a fresh run
            </div>
            <div
              style={{
                fontSize: 'var(--v4-text-xs)',
                color: 'var(--v4-text-subtle)',
              }}
            >
              Or just type into the agent below
            </div>
          </div>
        </button>
      </section>
    </>
  );
}
