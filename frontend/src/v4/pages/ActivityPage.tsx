/**
 * Activity — flat list of every run across every project. Replaces the
 * v3 /runs page; same data, v4 shape.
 *
 * Filter row: Scope (Mine / Org / All) · Status pill. One Reset chip
 * when any filter is non-default. No marketing copy.
 *
 * Each row is a tappable list row, not a card grid. iOS Settings density.
 */
import {useEffect, useMemo, useState, type ReactNode} from 'react';
import {Link, useSearchParams} from 'react-router-dom';
import {getRuns, type RunSummary, getProjects, type ProjectDto} from '@/lib/api';
import {useAuthStore, selectIsOrgAdmin} from '@/stores/useAuthStore';
import {fmtCost} from '@/lib/utils';
import LargeTitle from '../components/LargeTitle';
import StatusDot, {type StatusTone} from '../components/StatusDot';

type Scope = 'mine' | 'org' | 'all';
type Status = '' | 'COMPLETE' | 'WORKING' | 'AWAITING_APPROVAL' | 'FAILED' | 'CANCELLED';

function timeAgo(iso?: string | null): string {
  if (!iso) return '';
  const diff = Date.now() - new Date(iso).getTime();
  if (Number.isNaN(diff)) return '';
  if (diff < 60_000) return 'just now';
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)}m`;
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)}h`;
  return `${Math.floor(diff / 86_400_000)}d`;
}

function toneFor(s: string): StatusTone {
  if (s === 'COMPLETE') return 'success';
  if (s === 'PARTIAL' || s === 'AWAITING_APPROVAL') return 'warn';
  if (s === 'FAILED' || s === 'ABORTED' || s === 'CANCELLED') return 'fail';
  return 'progress';
}

function Chip({
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
      className='v4-press'
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        padding: '4px 12px',
        fontSize: 'var(--v4-text-xs)',
        fontWeight: 500,
        borderRadius: 9999,
        border: `1px solid ${active ? 'var(--v4-brand)' : 'var(--v4-border)'}`,
        background: active ? 'var(--v4-brand-soft)' : 'transparent',
        color: active ? 'var(--v4-brand)' : 'var(--v4-text-muted)',
        cursor: 'pointer',
        fontFamily: 'inherit',
      }}
    >
      {children}
    </button>
  );
}

export default function ActivityPage() {
  const [params, setParams] = useSearchParams();
  const isOrgAdmin = useAuthStore(selectIsOrgAdmin);
  const [scope, setScope] = useState<Scope>((params.get('scope') as Scope) || 'mine');
  const [status, setStatus] = useState<Status>((params.get('status') as Status) || '');
  const [runs, setRuns] = useState<RunSummary[] | null>(null);
  const [projects, setProjects] = useState<ProjectDto[]>([]);

  useEffect(() => {
    const next = new URLSearchParams();
    if (scope !== 'mine') next.set('scope', scope);
    if (status) next.set('status', status);
    setParams(next, {replace: true});
  }, [scope, status, setParams]);

  useEffect(() => {
    let cancelled = false;
    Promise.all([
      getRuns({scope, status: status || undefined, page: 0, size: 50}),
      projects.length === 0 ? getProjects() : Promise.resolve(projects),
    ])
      .then(([rRes, pList]) => {
        if (cancelled) return;
        setRuns(rRes.content);
        if (projects.length === 0) setProjects(pList);
      })
      .catch(() => {
        if (!cancelled) setRuns([]);
      });
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [scope, status]);

  const projectsById = useMemo(() => {
    const m = new Map<string, ProjectDto>();
    projects.forEach((p) => m.set(p.id, p));
    return m;
  }, [projects]);

  const filtersDirty = scope !== 'mine' || status !== '';
  const total = runs?.length ?? 0;
  const active = (runs ?? []).filter((r) =>
    ['WORKING', 'PLANNING', 'REVIEWING', 'AWAITING_APPROVAL'].includes(r.status),
  ).length;

  return (
    <>
      <LargeTitle
        title='Activity'
        subtitle={
          runs == null
            ? 'Loading…'
            : total === 0
              ? 'No runs match these filters'
              : `${total} run${total === 1 ? '' : 's'}${active > 0 ? ` · ${active} active` : ''}`
        }
      />

      <section
        aria-label='Filters'
        style={{
          display: 'flex',
          flexWrap: 'wrap',
          gap: 8,
          marginBottom: 14,
        }}
      >
        <Chip active={scope === 'mine'} onClick={() => setScope('mine')}>
          Mine
        </Chip>
        <Chip active={scope === 'org'} onClick={() => setScope('org')}>
          Org
        </Chip>
        {isOrgAdmin && (
          <Chip active={scope === 'all'} onClick={() => setScope('all')}>
            All
          </Chip>
        )}
        <span style={{width: 12, alignSelf: 'center'}} aria-hidden='true' />
        <Chip active={status === ''} onClick={() => setStatus('')}>
          Any status
        </Chip>
        <Chip active={status === 'COMPLETE'} onClick={() => setStatus('COMPLETE')}>
          Complete
        </Chip>
        <Chip active={status === 'WORKING'} onClick={() => setStatus('WORKING')}>
          Working
        </Chip>
        <Chip
          active={status === 'AWAITING_APPROVAL'}
          onClick={() => setStatus('AWAITING_APPROVAL')}
        >
          Awaiting approval
        </Chip>
        <Chip active={status === 'FAILED'} onClick={() => setStatus('FAILED')}>
          Failed
        </Chip>
        {filtersDirty && (
          <button
            type='button'
            onClick={() => {
              setScope('mine');
              setStatus('');
            }}
            className='v4-press'
            style={{
              marginLeft: 'auto',
              padding: '4px 10px',
              fontSize: 'var(--v4-text-xs)',
              color: 'var(--v4-text-muted)',
              background: 'transparent',
              border: '1px solid var(--v4-hairline)',
              borderRadius: 9999,
              cursor: 'pointer',
              fontFamily: 'inherit',
            }}
          >
            Reset
          </button>
        )}
      </section>

      {runs == null ? (
        <p style={{color: 'var(--v4-text-subtle)', fontSize: 'var(--v4-text-sm)'}}>Loading…</p>
      ) : runs.length === 0 ? (
        <div
          className='v4-card'
          style={{
            padding: 32,
            textAlign: 'center',
            color: 'var(--v4-text-muted)',
            fontSize: 'var(--v4-text-sm)',
          }}
        >
          Nothing here yet. Type something into the agent below to start a run.
        </div>
      ) : (
        <div className='v4-list'>
          {runs.map((r) => {
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
                    {r.actualCostUsd != null && r.actualCostUsd > 0
                      ? ` · ${fmtCost(r.actualCostUsd)}`
                      : ''}
                  </span>
                </div>
                <span
                  style={{
                    color: 'var(--v4-text-subtle)',
                    fontSize: 'var(--v4-text-xs)',
                    flexShrink: 0,
                  }}
                >
                  {timeAgo(r.completedAt ?? r.startedAt)}
                </span>
              </Link>
            );
          })}
        </div>
      )}
    </>
  );
}
