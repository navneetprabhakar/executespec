/**
 * X-13 (UX audit, 2026-05-03) — read-only project share surface.
 *
 * Pre-fix the only "share with a non-user" affordance was per-run
 * (`SharedReportPage`). Enterprise managers asking "can I send a
 * snapshot of project X to my CFO?" had no path beyond screenshots.
 * This component reserves the URL pattern and provides the contract
 * the backend needs to implement.
 *
 * The audit recommended `/projects/{id}/snapshot/{token}` rendering
 * PulseTab read-only with metrics + last 10 runs. We use {token}
 * (not {projectId}) in the visible URL so the share link doesn't
 * leak the real project id, matching the semantics of
 * `/reports/shared/{shareToken}`.
 *
 * STATUS: stub. The backend endpoint
 * `GET /api/projects/snapshot/{token}` is NOT yet implemented.
 * When it ships:
 *   - Replace the call to fetchProjectSnapshot below with the real
 *     api helper.
 *   - The expected payload shape is documented in
 *     {@link ProjectSnapshotDto} below.
 *
 * UX-preservation contract: read-only renders of existing primitives
 * only — VerdictPill, StatusBadge, PulseTab-like card layout. No new
 * tokens, no new fonts. Branded shell (top-bar with ExecuteSpec
 * logo) so the recipient knows where they are without being authed.
 */

import {useEffect, useState} from 'react';
import {useParams, Link} from 'react-router-dom';
import {AlertTriangle, ExternalLink} from 'lucide-react';
import {cn} from '@/lib/utils';

/**
 * Backend contract — what the snapshot endpoint should return.
 * Pinned here so the backend implementer + this stub can converge
 * without a separate spec doc.
 */
export interface ProjectSnapshotDto {
  /** Display name only — never the real id. */
  projectName: string;
  /** Truncated (≈ 280 chars) description so search engines / link
   *  previewers can summarise without exposing internal details. */
  projectDescriptionExcerpt: string | null;
  /** Generated time of the snapshot. Display only. */
  snapshotAt: string;
  /** Aggregate stats — same shape as ProjectDashboardDTO but only
   *  the fields safe to expose to non-users. */
  stats: {
    repoCount: number;
    completedRuns: number;
    failedRuns: number;
    avgScore: number | null;
    totalCostUsd: number | null;
  };
  /** Last 10 runs as a read-only feed. */
  recentRuns: Array<{
    runId: string;
    title: string;
    status: string;
    statusReason: string | null;
    finalBuildPassed: boolean | null;
    verifiedScore: number | null;
    durationMs: number | null;
    completedAt: string | null;
  }>;
  /** Repos (display only — no PAT, no SHA, no raw URL). */
  repos: Array<{
    name: string;
    language: string | null;
  }>;
}

const SNAPSHOT_ENDPOINT_AVAILABLE = false;

async function fetchProjectSnapshot(token: string): Promise<ProjectSnapshotDto> {
  // X-13 — when the backend lands, replace this body with:
  //   return req<ProjectSnapshotDto>(`/projects/snapshot/${token}`);
  // For now we simulate the not-implemented state so the UI can still
  // render a useful "not yet available" message instead of a 404.
  if (!SNAPSHOT_ENDPOINT_AVAILABLE) {
    void token;
    throw new Error('not-implemented');
  }
  // The real fetch will live here once the endpoint ships.
  throw new Error('unreachable');
}

export default function SharedProjectPage() {
  const {token} = useParams<{token: string}>();
  const [snapshot, setSnapshot] = useState<ProjectSnapshotDto | null>(null);
  const [error, setError] = useState<'not-implemented' | 'fetch-error' | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (!token) {
      setError('fetch-error');
      setLoading(false);
      return;
    }
    fetchProjectSnapshot(token)
      .then((s) => {
        setSnapshot(s);
        setLoading(false);
      })
      .catch((e: Error) => {
        setError(e.message === 'not-implemented' ? 'not-implemented' : 'fetch-error');
        setLoading(false);
      });
  }, [token]);

  return (
    <div className='min-h-screen bg-[var(--bg-primary)] text-primary'>
      {/* Branded top-bar — un-authed visitors land here knowing where they are. */}
      <header className='flex items-center h-14 px-4 border-b border-[var(--border-default)] gap-3'>
        <Link to='/' className='text-primary font-semibold hover:text-accent-blue'>
          ExecuteSpec
        </Link>
        <span className='text-muted'>/</span>
        <span className='text-secondary text-sm'>Shared project snapshot</span>
        <div className='flex-1' />
        <Link
          to='/'
          className='text-xs text-muted hover:text-primary inline-flex items-center gap-1'
        >
          Sign in <ExternalLink className='h-3 w-3' />
        </Link>
      </header>

      <main className='max-w-3xl mx-auto py-8 px-4'>
        {loading && <div className='text-xs text-muted'>Loading snapshot…</div>}

        {error === 'not-implemented' && (
          <div className='rounded-sm border border-status-waiting/30 bg-status-waiting/10 px-4 py-3 flex items-start gap-3'>
            <AlertTriangle className='h-4 w-4 text-status-waiting flex-shrink-0 mt-0.5' />
            <div className='space-y-1 text-xs'>
              <div className='font-medium text-primary'>Project snapshot sharing — coming soon</div>
              <p className='text-muted leading-relaxed'>
                The backend endpoint that powers project-level snapshots is not yet implemented.
                One-shot validation report sharing remains available at{' '}
                <Link to='/' className='text-accent-blue underline'>
                  /reports/shared/&lt;token&gt;
                </Link>
                . Track progress in the audit doc{' '}
                <code className='font-mono text-[11px]'>
                  docs/audits/enterprise-ux-2026-05-03.md
                </code>{' '}
                under X-13.
              </p>
            </div>
          </div>
        )}

        {error === 'fetch-error' && (
          <div className='rounded-sm border border-status-error/30 bg-status-error/10 px-4 py-3 flex items-start gap-3'>
            <AlertTriangle className='h-4 w-4 text-status-error flex-shrink-0 mt-0.5' />
            <div className='space-y-1 text-xs'>
              <div className='font-medium text-primary'>Snapshot link is invalid or expired</div>
              <p className='text-muted'>
                Ask the person who shared this with you to regenerate it.
              </p>
            </div>
          </div>
        )}

        {snapshot && (
          // Real render — drives off the contract. When the backend ships
          // this is the only block that will exercise.
          <div className='space-y-6'>
            <header className='space-y-1'>
              <h1 className='text-xl font-semibold text-primary tracking-[-0.015em]'>
                {snapshot.projectName}
              </h1>
              {snapshot.projectDescriptionExcerpt && (
                <p className='text-sm text-secondary leading-relaxed'>
                  {snapshot.projectDescriptionExcerpt}
                </p>
              )}
              <p className='text-[11px] text-muted'>
                Snapshot generated {new Date(snapshot.snapshotAt).toLocaleString()}
              </p>
            </header>

            <section className='grid grid-cols-2 sm:grid-cols-4 gap-3'>
              <Stat label='Repos' value={String(snapshot.stats.repoCount)} />
              <Stat label='Done' value={String(snapshot.stats.completedRuns)} />
              <Stat
                label='Score'
                value={
                  snapshot.stats.avgScore != null
                    ? `${Math.round(snapshot.stats.avgScore)}/100`
                    : '—'
                }
              />
              <Stat
                label='Spend'
                value={
                  snapshot.stats.totalCostUsd != null
                    ? `$${snapshot.stats.totalCostUsd.toFixed(2)}`
                    : '—'
                }
              />
            </section>

            <section>
              <h2 className='text-xs font-semibold uppercase tracking-wider text-muted mb-2'>
                Recent runs
              </h2>
              <ul className='space-y-1'>
                {snapshot.recentRuns.map((r) => (
                  <li
                    key={r.runId}
                    className='rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] px-3 py-2 flex items-center justify-between gap-2'
                  >
                    <span className='text-xs text-primary truncate min-w-0'>{r.title}</span>
                    <span
                      className={cn(
                        'text-[10px] font-mono uppercase px-1.5 py-0.5 rounded-sm flex-shrink-0',
                        r.status === 'COMPLETE' && 'bg-status-success/10 text-status-success',
                        r.status === 'PARTIAL' && 'bg-status-partial/10 text-status-partial',
                        (r.status === 'FAILED' || r.status === 'CANCELLED') &&
                          'bg-status-error/10 text-status-error',
                      )}
                    >
                      {r.status}
                    </span>
                  </li>
                ))}
                {snapshot.recentRuns.length === 0 && (
                  <li className='text-[11px] text-muted px-3 py-2'>No runs yet.</li>
                )}
              </ul>
            </section>
          </div>
        )}
      </main>
    </div>
  );
}

function Stat({label, value}: {label: string; value: string}) {
  return (
    <div className='rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] px-3 py-2'>
      <div className='text-[10px] uppercase tracking-wider font-mono text-muted'>{label}</div>
      <div className='text-sm font-medium text-primary mt-0.5 tabular-nums'>{value}</div>
    </div>
  );
}
