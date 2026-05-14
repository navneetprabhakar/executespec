import {useEffect, useMemo, useState, type ReactNode} from 'react';
import {Link, useNavigate, useSearchParams} from 'react-router-dom';
import {getRuns, type RunSummary, type ProjectDto, getProjects} from '@/lib/api';
import {useAuthStore, selectIsOrgAdmin} from '@/stores/useAuthStore';
import Skeleton from '@/components/shared/Skeleton';

function FilterPill({
  active,
  onClick,
  children,
}: {
  active: boolean;
  onClick: () => void;
  children: ReactNode;
}) {
  return (
    <button
      type='button'
      onClick={onClick}
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        padding: '4px 12px',
        fontSize: 12,
        fontWeight: 500,
        borderRadius: 999,
        border: `1px solid ${active ? 'var(--v3-brand)' : 'var(--v3-border)'}`,
        background: active ? 'rgba(91,141,239,0.16)' : 'transparent',
        color: active ? 'var(--v3-brand-hover, var(--v3-brand))' : 'var(--v3-text-muted)',
        cursor: 'pointer',
        transition: 'all 0.12s ease',
        fontFamily: 'inherit',
        lineHeight: 1.4,
      }}
    >
      {children}
    </button>
  );
}

/**
 * /runs — flat list of every run accessible to the caller.
 *
 * Replaces the prior /runs → /home redirect (the v2 RunsPage was
 * deleted with the v2 chrome retirement on 2026-05-06, leaving the
 * topbar Runs link pointing at a redirect). FREE / individual users
 * have no Project Shell (gated to ENTERPRISE/MAX), so they had no
 * way to see runs they'd already submitted — this page closes that
 * gap.
 *
 * Hits GET /api/runs which is NOT tier-gated (handled by
 * RunVisibilitySpec — caller sees only runs they own, plus org-shared
 * runs when applicable, plus global when SUPER_ADMIN).
 */
type ScopeFilter = 'mine' | 'org' | 'all';
type StatusFilter = '' | 'COMPLETE' | 'WORKING' | 'AWAITING_APPROVAL' | 'FAILED' | 'CANCELLED';

const STATUS_TONE: Record<string, {bg: string; fg: string; bd: string}> = {
  COMPLETE: {bg: 'rgba(16,185,129,0.14)', fg: '#34D399', bd: 'rgba(16,185,129,0.40)'},
  WORKING: {bg: 'rgba(91,141,239,0.14)', fg: '#7AA5F5', bd: 'rgba(91,141,239,0.40)'},
  PLANNING: {bg: 'rgba(91,141,239,0.14)', fg: '#7AA5F5', bd: 'rgba(91,141,239,0.40)'},
  REVIEWING: {bg: 'rgba(91,141,239,0.14)', fg: '#7AA5F5', bd: 'rgba(91,141,239,0.40)'},
  AWAITING_APPROVAL: {bg: 'rgba(245,158,11,0.14)', fg: '#FBBF24', bd: 'rgba(245,158,11,0.40)'},
  FAILED: {bg: 'rgba(239,68,68,0.14)', fg: '#FCA5A5', bd: 'rgba(239,68,68,0.40)'},
  CANCELLED: {bg: 'rgba(120,128,140,0.14)', fg: '#9AA3B0', bd: 'rgba(120,128,140,0.40)'},
  PARTIAL: {bg: 'rgba(245,158,11,0.14)', fg: '#FBBF24', bd: 'rgba(245,158,11,0.40)'},
};

function statusTone(status?: string) {
  return STATUS_TONE[status ?? ''] ?? STATUS_TONE.CANCELLED;
}

function timeAgo(iso?: string | null): string {
  if (!iso) return '';
  const then = new Date(iso).getTime();
  if (Number.isNaN(then)) return '';
  const sec = Math.max(1, Math.round((Date.now() - then) / 1000));
  if (sec < 60) return `${sec}s ago`;
  const min = Math.round(sec / 60);
  if (min < 60) return `${min}m ago`;
  const hr = Math.round(min / 60);
  if (hr < 24) return `${hr}h ago`;
  const d = Math.round(hr / 24);
  if (d < 30) return `${d}d ago`;
  const mo = Math.round(d / 30);
  return `${mo}mo ago`;
}

export default function RunsListPage() {
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const isOrgAdmin = useAuthStore(selectIsOrgAdmin);

  const initialScope = (params.get('scope') as ScopeFilter) || 'mine';
  const initialStatus = (params.get('status') as StatusFilter) || '';

  const [scope, setScope] = useState<ScopeFilter>(initialScope);
  const [status, setStatus] = useState<StatusFilter>(initialStatus);
  const [page, setPage] = useState(0);
  const [size] = useState(25);
  const [runs, setRuns] = useState<RunSummary[] | null>(null);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);
  const [projects, setProjects] = useState<ProjectDto[]>([]);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setErr(null);
    Promise.all([
      getRuns({scope, status: status || undefined, page, size}),
      projects.length === 0 ? getProjects() : Promise.resolve(projects),
    ])
      .then(([runRes, projList]) => {
        if (cancelled) return;
        setRuns(runRes.content);
        setTotal(runRes.totalElements);
        if (projects.length === 0 && projList.length > 0) setProjects(projList);
      })
      .catch((e: Error) => {
        if (cancelled) return;
        setErr(e.message ?? 'Failed to load runs');
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [scope, status, page, size]);

  // Persist filter choices to URL so /runs?scope=org&status=FAILED is shareable
  useEffect(() => {
    const next = new URLSearchParams();
    if (scope !== 'mine') next.set('scope', scope);
    if (status) next.set('status', status);
    setParams(next, {replace: true});
  }, [scope, status, setParams]);

  const projectName = useMemo(() => {
    const map = new Map<string, string>();
    projects.forEach((p) => p.id && map.set(p.id, p.name ?? ''));
    return (id?: string) => (id ? (map.get(id) ?? id.slice(0, 8)) : '');
  }, [projects]);

  return (
    <div style={{maxWidth: 1280, margin: '0 auto', padding: '32px 24px'}}>
      <header
        style={{
          marginBottom: 24,
          display: 'flex',
          alignItems: 'flex-start',
          justifyContent: 'space-between',
          gap: 16,
          flexWrap: 'wrap',
        }}
      >
        <div>
          <div
            style={{
              fontFamily: 'var(--v3-font-mono)',
              fontSize: 11,
              letterSpacing: '0.08em',
              textTransform: 'uppercase',
              color: 'var(--accent-blue-text, var(--accent-blue))',
              display: 'inline-block',
              padding: '4px 10px',
              border: '1px solid rgba(91,141,239,0.30)',
              borderRadius: 999,
              marginBottom: 12,
            }}
          >
            Runs
          </div>
          <h1 style={{fontSize: 28, fontWeight: 700, color: 'var(--v3-text)', margin: 0}}>
            Every PR you've shipped, in one place.
          </h1>
          <p style={{margin: '8px 0 0', color: 'var(--v3-text-muted)', maxWidth: 640}}>
            The full feed of runs you've submitted across every project. Click in to read the
            validation report, open the PR, or fork a re-run.
          </p>
        </div>
        {/* 2026-05-07 — top-right "Launch new run" CTA targets the
            standalone /runs/new launcher (project picker is optional —
            standalone is the default). The previous /home target
            forced users into a specific project shell, breaking the
            FREE / no-project use case the user flagged: "I should be
            able to submit run even without a project, that is a
            critical offering." */}
        <button
          type='button'
          onClick={() => navigate('/runs/new')}
          style={{
            background: 'var(--v3-brand)',
            color: 'var(--v3-text-on-brand)',
            border: 0,
            padding: '10px 18px',
            borderRadius: 8,
            fontWeight: 600,
            fontSize: 13,
            cursor: 'pointer',
            fontFamily: 'inherit',
            whiteSpace: 'nowrap',
          }}
        >
          + Launch new run
        </button>
      </header>

      {/* Filter row */}
      <div
        style={{
          display: 'flex',
          flexWrap: 'wrap',
          gap: 8,
          alignItems: 'center',
          marginBottom: 20,
        }}
      >
        <span style={{fontSize: 12, color: 'var(--v3-text-muted)', marginRight: 4}}>Scope</span>
        <FilterPill active={scope === 'mine'} onClick={() => setScope('mine')}>
          Mine
        </FilterPill>
        <FilterPill active={scope === 'org'} onClick={() => setScope('org')}>
          Org
        </FilterPill>
        {isOrgAdmin && (
          <FilterPill active={scope === 'all'} onClick={() => setScope('all')}>
            All
          </FilterPill>
        )}
        <span style={{width: 12}} />
        <span style={{fontSize: 12, color: 'var(--v3-text-muted)', marginRight: 4}}>Status</span>
        <FilterPill active={status === ''} onClick={() => setStatus('')}>
          Any
        </FilterPill>
        <FilterPill active={status === 'COMPLETE'} onClick={() => setStatus('COMPLETE')}>
          Complete
        </FilterPill>
        <FilterPill active={status === 'WORKING'} onClick={() => setStatus('WORKING')}>
          Working
        </FilterPill>
        <FilterPill
          active={status === 'AWAITING_APPROVAL'}
          onClick={() => setStatus('AWAITING_APPROVAL')}
        >
          Needs approval
        </FilterPill>
        <FilterPill active={status === 'FAILED'} onClick={() => setStatus('FAILED')}>
          Failed
        </FilterPill>
      </div>

      {err && (
        <div
          style={{
            padding: 16,
            border: '1px solid rgba(239,68,68,0.40)',
            background: 'rgba(239,68,68,0.10)',
            borderRadius: 12,
            color: '#FCA5A5',
            marginBottom: 16,
          }}
        >
          {err}
        </div>
      )}

      {loading && !runs && (
        <div style={{padding: 12}}>
          <Skeleton variant='row' count={6} label='Loading runs' />
        </div>
      )}

      {runs && runs.length === 0 && !err && (
        <div
          className='v3-frame'
          style={{
            padding: '48px 24px',
            textAlign: 'center',
          }}
        >
          <div style={{fontSize: 16, fontWeight: 600, color: 'var(--v3-text)', marginBottom: 8}}>
            No runs yet
          </div>
          <p style={{color: 'var(--v3-text-muted)', margin: '0 auto 16px', maxWidth: 480}}>
            Start one from any project — or jump into the Library to grab a readymade solution as a
            starting spec.
          </p>
          <div style={{display: 'flex', gap: 8, justifyContent: 'center'}}>
            <button
              type='button'
              onClick={() => navigate('/runs/new')}
              style={{
                background: 'var(--v3-brand)',
                color: '#fff',
                border: 0,
                padding: '10px 18px',
                borderRadius: 8,
                fontWeight: 600,
                fontSize: 13,
                cursor: 'pointer',
              }}
            >
              Start a run →
            </button>
            <button
              type='button'
              onClick={() => navigate('/library')}
              style={{
                background: 'transparent',
                color: 'var(--v3-text)',
                border: '1px solid var(--v3-border)',
                padding: '10px 18px',
                borderRadius: 8,
                fontWeight: 600,
                fontSize: 13,
                cursor: 'pointer',
              }}
            >
              Browse Library
            </button>
          </div>
        </div>
      )}

      {runs && runs.length > 0 && (
        <div className='v3-frame'>
          {runs.map((r, i) => {
            const tone = statusTone(r.status);
            const projectId = r.projectId ?? undefined;
            const runId = r.runId ?? '';
            const runShort = runId.slice(0, 8); // display only — route uses full id
            // RunDetailPage calls getRun(runId) which expects the full UUID;
            // the route param is named `runShort` but the value passed in must
            // be the full id (matches every other launcher in the app:
            // ProjectLaunchesList, ProjectPrRollup, etc.). 2026-05-06 — when
            // the run isn't linked to a project (FREE / pre-project-shell),
            // route to the flat /runs/{id} which mounts RunDetailPage with
            // an undefined projectId. The previous /projects/empty?run=...
            // target landed in EmptyProjectPage and dropped the runId.
            const target = projectId ? `/projects/${projectId}/runs/${runId}` : `/runs/${runId}`;
            return (
              <Link
                key={r.runId}
                to={target}
                style={{
                  display: 'grid',
                  gridTemplateColumns: '1fr auto',
                  gap: 16,
                  alignItems: 'center',
                  padding: '14px 18px',
                  textDecoration: 'none',
                  color: 'inherit',
                  borderTop: i === 0 ? 0 : '1px solid var(--v3-border)',
                }}
              >
                <div style={{minWidth: 0}}>
                  {/* 2026-05-10 (mobile audit) — child row was `display: flex
                      gap: 8` with no flex-wrap, so on 375px the project name
                      span shrank and re-wrapped each word onto its own line
                      while the status pill + runId stayed compact. New shape:
                      flex-wrap with `min-width: 0` + `flex: 0 1 auto` on the
                      project-name span so it truncates instead of wrapping
                      vertically. */}
                  <div
                    style={{
                      display: 'flex',
                      alignItems: 'center',
                      flexWrap: 'wrap',
                      gap: 8,
                      marginBottom: 4,
                      minWidth: 0,
                    }}
                  >
                    <span
                      style={{
                        fontFamily: 'var(--v3-font-mono)',
                        fontSize: 11,
                        padding: '2px 8px',
                        borderRadius: 999,
                        background: tone.bg,
                        color: tone.fg,
                        border: `1px solid ${tone.bd}`,
                        fontWeight: 600,
                        flexShrink: 0,
                      }}
                    >
                      {(r.status ?? 'UNKNOWN').replace(/_/g, ' ')}
                    </span>
                    {(r.projectName || projectId) && (
                      <span
                        style={{
                          fontFamily: 'var(--v3-font-mono)',
                          fontSize: 11,
                          color: 'var(--v3-text-subtle)',
                          minWidth: 0,
                          maxWidth: '100%',
                          overflow: 'hidden',
                          textOverflow: 'ellipsis',
                          whiteSpace: 'nowrap',
                        }}
                      >
                        {r.projectName ?? projectName(projectId)}
                      </span>
                    )}
                    <span
                      style={{
                        fontFamily: 'var(--v3-font-mono)',
                        fontSize: 11,
                        color: 'var(--v3-text-subtle)',
                        flexShrink: 0,
                      }}
                    >
                      · {runShort}
                    </span>
                  </div>
                  {/* 2026-05-14 (P4) — was single-line truncate, cut titles mid-word
                      ("Add Hello from ExecuteSpec audit run to RE…"). Mobile gets
                      a 2-line clamp via the global CSS hammer; desktop stays
                      one-line via .allow-truncate opt-out. */}
                  <div
                    className='allow-truncate'
                    style={{
                      fontSize: 14,
                      fontWeight: 600,
                      color: 'var(--v3-text)',
                      overflow: 'hidden',
                      display: '-webkit-box',
                      WebkitLineClamp: 2,
                      WebkitBoxOrient: 'vertical' as const,
                      wordBreak: 'break-word',
                    }}
                  >
                    {r.runTitle || r.userRequest || '(no spec text)'}
                  </div>
                </div>
                <div style={{display: 'flex', alignItems: 'center', gap: 16, flexShrink: 0}}>
                  {typeof r.actualCostUsd === 'number' && (
                    <span
                      style={{
                        fontFamily: 'var(--v3-font-mono)',
                        fontSize: 11.5,
                        color: 'var(--v3-text-muted)',
                      }}
                    >
                      ${r.actualCostUsd.toFixed(2)}
                    </span>
                  )}
                  <span
                    style={{
                      fontFamily: 'var(--v3-font-mono)',
                      fontSize: 11.5,
                      color: 'var(--v3-text-subtle)',
                      width: 64,
                      textAlign: 'right',
                    }}
                  >
                    {timeAgo(r.startedAt)}
                  </span>
                </div>
              </Link>
            );
          })}
        </div>
      )}

      {/* Pagination */}
      {runs && total > size && (
        <div
          style={{
            display: 'flex',
            justifyContent: 'center',
            gap: 8,
            marginTop: 20,
            alignItems: 'center',
          }}
        >
          <button
            type='button'
            disabled={page === 0}
            onClick={() => setPage((p) => Math.max(0, p - 1))}
            style={{
              background: 'transparent',
              color: page === 0 ? 'var(--v3-text-subtle)' : 'var(--v3-text)',
              border: '1px solid var(--v3-border)',
              padding: '6px 14px',
              borderRadius: 8,
              cursor: page === 0 ? 'not-allowed' : 'pointer',
              fontSize: 12,
            }}
          >
            ← Prev
          </button>
          <span style={{fontSize: 12, color: 'var(--v3-text-muted)'}}>
            Page {page + 1} of {Math.max(1, Math.ceil(total / size))}
          </span>
          <button
            type='button'
            disabled={(page + 1) * size >= total}
            onClick={() => setPage((p) => p + 1)}
            style={{
              background: 'transparent',
              color: (page + 1) * size >= total ? 'var(--v3-text-subtle)' : 'var(--v3-text)',
              border: '1px solid var(--v3-border)',
              padding: '6px 14px',
              borderRadius: 8,
              cursor: (page + 1) * size >= total ? 'not-allowed' : 'pointer',
              fontSize: 12,
            }}
          >
            Next →
          </button>
        </div>
      )}
    </div>
  );
}
