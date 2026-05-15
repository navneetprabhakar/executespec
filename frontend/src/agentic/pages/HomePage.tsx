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
  const part = hr < 5 ? 'Working late' : hr < 12 ? 'Good morning' : hr < 18 ? 'Good afternoon' : 'Good evening';
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
    Promise.all([
      getRuns({scope: 'mine', page: 0, size: 6}),
      getProjects(),
    ])
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
    () => (runs ?? []).filter((r) =>
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
                fontSize: 'var(--a-text-base)',
                fontWeight: 600,
                color: 'var(--a-text)',
              }}
            >
              Recent
            </h2>
            <Link
              to='/a/activity'
              style={{
                fontSize: 'var(--a-text-xs)',
                color: 'var(--a-brand)',
                textDecoration: 'none',
              }}
            >
              See all →
            </Link>
          </div>
          <div className='a-list'>
            {runs.slice(1, 5).map((r) => {
              const proj = r.projectId ? projectsById.get(r.projectId) : null;
              const dest = r.projectId
                ? `/a/projects/${r.projectId}/runs/${r.runId}`
                : `/a/runs/${r.runId}`;
              return (
                <Link
                  key={r.runId}
                  to={dest}
                  className='a-list-row a-press'
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
                        color: 'var(--a-text)',
                        fontSize: 'var(--a-text-sm)',
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
                        color: 'var(--a-text-subtle)',
                        fontSize: 'var(--a-text-xs)',
                      }}
                    >
                      {proj?.name ?? 'standalone'} · {r.status.toLowerCase()}
                    </span>
                  </div>
                  <span
                    style={{
                      color: 'var(--a-text-subtle)',
                      fontSize: 'var(--a-text-xs)',
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
                fontSize: 'var(--a-text-base)',
                fontWeight: 600,
                color: 'var(--a-text)',
              }}
            >
              Workspaces
            </h2>
          </div>
          <div
            style={{
              display: 'grid',
              gridTemplateColumns:
                'repeat(auto-fill, minmax(min(220px, 100%), 1fr))',
              gap: 12,
            }}
          >
            {projects.slice(0, 4).map((p) => (
              <Link
                key={p.id}
                to={`/a/projects/${p.id}`}
                className='a-card is-tappable'
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
                      background: 'var(--a-brand-soft)',
                      color: 'var(--a-brand)',
                    }}
                  >
                    <GitBranch size={12} />
                  </span>
                  <span
                    style={{
                      fontSize: 'var(--a-text-sm)',
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
                    fontSize: 'var(--a-text-xs)',
                    color: 'var(--a-text-subtle)',
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
          gridTemplateColumns:
            'repeat(auto-fill, minmax(min(220px, 100%), 1fr))',
          gap: 12,
          marginBottom: 20,
        }}
      >
        <Link
          to='/a/library'
          className='a-card is-tappable'
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
              background: 'var(--a-brand-soft)',
              color: 'var(--a-brand)',
            }}
          >
            <Library size={18} />
          </span>
          <div style={{flex: 1, minWidth: 0}}>
            <div
              style={{
                fontSize: 'var(--a-text-sm)',
                fontWeight: 600,
                color: 'var(--a-text)',
              }}
            >
              Browse catalog
            </div>
            <div
              style={{
                fontSize: 'var(--a-text-xs)',
                color: 'var(--a-text-subtle)',
              }}
            >
              Ready-made solutions + migrations
            </div>
          </div>
          <ArrowUpRight
            size={16}
            aria-hidden='true'
            style={{color: 'var(--a-text-subtle)'}}
          />
        </Link>
        <button
          type='button'
          onClick={() => navigate('/runs/new')}
          className='a-card is-tappable'
          style={{
            padding: '16px',
            border: '1px solid var(--a-border)',
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
              background: 'var(--a-surface-up)',
              color: 'var(--a-text)',
              border: '1px dashed var(--a-border)',
            }}
          >
            <Plus size={18} />
          </span>
          <div style={{flex: 1, minWidth: 0}}>
            <div
              style={{
                fontSize: 'var(--a-text-sm)',
                fontWeight: 600,
              }}
            >
              Start a fresh run
            </div>
            <div
              style={{
                fontSize: 'var(--a-text-xs)',
                color: 'var(--a-text-subtle)',
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
