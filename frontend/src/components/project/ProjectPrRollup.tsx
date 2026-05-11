import {useEffect, useMemo, useState} from 'react';
import {Link} from 'react-router-dom';
import {
  ChevronDown,
  ChevronRight,
  ExternalLink,
  FileCode2,
  Loader2,
  MessageSquareReply,
  RefreshCw,
} from 'lucide-react';
import {executionApi} from '@/lib/execution-api';
import type {ContinueOnPrConfig, PrRollupEntry} from '@/types/execution';
import {
  fetchPrReviewComments,
  getPullRequestSnapshot,
  refreshProjectPrs,
  type PullRequestSnapshot,
} from '@/lib/api';
import PrReviewButton from './PrReviewButton';
import DiffView from '@/components/shared/DiffView';
import {cn, errMsg} from '@/lib/utils';

export default function ProjectPrRollup({
  projectId,
  onAddressReview,
}: {
  projectId: string;
  onAddressReview?: (config: ContinueOnPrConfig) => void;
}) {
  const [prs, setPrs] = useState<PrRollupEntry[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [refreshing, setRefreshing] = useState(false);
  const [lastRefresh, setLastRefresh] = useState<string | null>(null);
  // Elapsed seconds since the current refresh started; ticks at 1Hz while
  // refreshing so the user sees the operation is alive instead of a static
  // spinner. Resets to 0 on each refresh kick-off.
  const [elapsedSec, setElapsedSec] = useState(0);
  // Set when a refresh failed but the backend may still finish in the
  // background (524/500 are both compatible with eventual completion). We
  // schedule a follow-up re-fetch so the UI reflects the eventual DB write
  // without forcing the user to click Refresh a second time.
  const [refetchPending, setRefetchPending] = useState(false);

  const load = () =>
    executionApi
      .listProjectPrs(projectId)
      .then(setPrs)
      .catch((e: unknown) => setError(errMsg(e) ?? 'Failed to load PRs'));

  useEffect(() => {
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [projectId]);

  // 1Hz tick while refreshing so the elapsed counter updates live.
  useEffect(() => {
    if (!refreshing) return;
    const t = window.setInterval(() => setElapsedSec((s) => s + 1), 1000);
    return () => window.clearInterval(t);
  }, [refreshing]);

  // Eventual-consistency fallback: after a refresh error, the backend may
  // still complete the GitHub poll + DB write in the background (the user
  // observed exactly this after the 2026-05-04 hotfix — a CF 524 came back
  // but the PRs were updated when they came back to the page). Quietly
  // re-fetch the rollup at +15s and +45s so the UI catches up without
  // requiring a manual retry.
  useEffect(() => {
    if (!refetchPending) return;
    const t1 = window.setTimeout(() => load(), 15_000);
    const t2 = window.setTimeout(() => {
      load();
      setRefetchPending(false);
    }, 45_000);
    return () => {
      window.clearTimeout(t1);
      window.clearTimeout(t2);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [refetchPending, projectId]);

  const onRefresh = async () => {
    setRefreshing(true);
    setError(null);
    setElapsedSec(0);
    try {
      const r = await refreshProjectPrs(projectId);
      // 2026-05-11 (V186) — surface the CI-failing count in the toast so the
      // user sees the red signal on a no-webhook setup without scrolling the
      // PR list. `ciFailing` is the count of PRs that came back FAILURE on
      // this sweep (rather than the cumulative count across all time).
      const parts: string[] = [];
      if (r.newlyMerged > 0) parts.push(`${r.newlyMerged} merged`);
      parts.push(`${r.updated}/${r.checked} updated`);
      if (r.ciFailing > 0) {
        parts.push(`${r.ciFailing} CI failing`);
      }
      setLastRefresh(parts.join(' · '));
      await load();
    } catch (e) {
      // Soft-fail: the backend's GitHub poll + DB write often completes
      // even when the foreground request errored or timed out at the edge.
      // Schedule the eventual-consistency re-fetch + give the user honest
      // copy that explains what's happening rather than a hard "failed".
      setError(`${errMsg(e) ?? 'Refresh failed'} — re-fetching statuses in the background.`);
      setRefetchPending(true);
      // Best-effort immediate re-fetch in case the DB already has the
      // updated rows by the time the foreground call errored.
      try {
        await load();
      } catch {
        /* ignore — handled by load's own catch */
      }
    } finally {
      setRefreshing(false);
    }
  };

  if (prs == null && !error) return <div className='text-xs text-muted'>Loading PRs…</div>;
  if (prs == null) {
    // Initial load itself failed (couldn't even fetch the rollup). Surface
    // the error and a retry; this branch fires before any refresh happened.
    return (
      <div className='text-xs text-accent-red'>
        {error}{' '}
        <button
          type='button'
          onClick={() => {
            setError(null);
            load();
          }}
          className='underline ml-1'
        >
          Retry
        </button>
      </div>
    );
  }

  const prCount = prs.length;

  // Banner shown WHILE a refresh is in flight. Communicates the operation,
  // shows elapsed time so the spinner isn't the only signal of progress, and
  // shifts copy past the 5s mark so the user knows the "slow" path is normal
  // (private-repo polls + GitHub rate limits routinely take >10s).
  const refreshingBanner = refreshing && (
    <div
      className='flex items-center gap-2 mb-2 px-2 py-1.5 text-[11px] rounded-sm border border-accent-blue/40 bg-accent-blue/5 text-accent-blue'
      role='status'
      aria-live='polite'
    >
      <Loader2 className='h-3.5 w-3.5 animate-spin shrink-0' />
      <span className='flex-1'>
        {elapsedSec < 5
          ? `Polling GitHub for ${prCount} pull request${prCount === 1 ? '' : 's'}…`
          : elapsedSec < 20
            ? `Still polling GitHub… ${elapsedSec}s elapsed (private repos can take a moment).`
            : `Taking longer than usual — ${elapsedSec}s elapsed. The backend will keep going; you don't need to retry.`}
      </span>
    </div>
  );

  const header = (
    <div className='flex items-center justify-between mb-2'>
      <h3 className='text-xs font-medium text-secondary'>Pull requests</h3>
      <div className='flex items-center gap-2'>
        {lastRefresh && !refreshing && (
          <span className='text-[11px] text-muted'>{lastRefresh}</span>
        )}
        <button
          onClick={onRefresh}
          disabled={refreshing}
          aria-busy={refreshing}
          className='inline-flex items-center gap-1 text-[11px] font-medium px-2 py-0.5 rounded-sm border border-accent-blue/40 bg-accent-blue/10 text-accent-blue hover:bg-accent-blue/20 disabled:opacity-50 disabled:cursor-progress'
          title={
            refreshing
              ? 'Polling GitHub — this can take up to ~30s for private repos'
              : 'Poll GitHub for latest PR status; auto-analyse on any new merge'
          }
        >
          {refreshing ? (
            <Loader2 className='h-3 w-3 animate-spin' />
          ) : (
            <RefreshCw className='h-3 w-3' />
          )}
          {refreshing ? `Refreshing… ${elapsedSec}s` : 'Refresh'}
        </button>
      </div>
    </div>
  );

  // Soft error: refresh foreground errored but the backend may still complete
  // (the schedule-a-refetch pattern in `onRefresh` is already handling that).
  // Render the banner ABOVE the content so the existing PR list stays visible
  // — never blank-screen the user on a refresh hiccup.
  const errorBanner = error && (
    <div
      className='mb-2 px-2 py-1.5 text-[11px] rounded-sm border border-accent-amber/40 bg-accent-amber/10 text-accent-amber'
      role='status'
      aria-live='polite'
    >
      {error}
      {refetchPending && ' (re-checking in 15s · 45s)'}
    </div>
  );

  if (prs.length === 0) {
    return (
      <div>
        {header}
        {refreshingBanner}
        {errorBanner}
        <div className='text-xs text-muted'>No pull requests yet.</div>
      </div>
    );
  }

  // V169 (2026-05-02) — three categories now:
  //   1. Launch PRs: entries with no repoFullName (ExecutionSolution-driven).
  //   2. Run PRs: entries with kind=RUN (or absent kind, for back-compat) AND a launchId.
  //   3. System PRs: entries with kind != RUN — bootstrap, architecture, contract, chat-tool.
  // The grouping mirrors the categories so the dashboard reads as
  // "what users started" + "what the platform did automatically".
  const launchEntries = prs.filter((p) => !p.repoFullName);
  const runEntries = prs.filter((p) => !!p.repoFullName && (!p.kind || p.kind === 'RUN'));
  const systemEntries = prs.filter((p) => !!p.kind && p.kind !== 'RUN');

  const byLaunch: Record<string, PrRollupEntry[]> = {};
  launchEntries.forEach((p) => {
    if (p.launchId) (byLaunch[p.launchId] ??= []).push(p);
  });
  const byRun: Record<string, PrRollupEntry[]> = {};
  runEntries.forEach((p) => {
    if (p.launchId) (byRun[p.launchId] ??= []).push(p);
  });
  const bySystemKind: Record<string, PrRollupEntry[]> = {};
  systemEntries.forEach((p) => {
    const k = p.kind || 'SYSTEM';
    (bySystemKind[k] ??= []).push(p);
  });

  return (
    <div className='space-y-4'>
      {header}
      {refreshingBanner}
      {errorBanner}
      {Object.entries(byLaunch).map(([launchId, group]) => (
        <PrGroup
          key={`launch-${launchId}`}
          label='Launch'
          idShort={launchId.substring(0, 8)}
          to={`/projects/${projectId}/executions/${launchId}`}
          prs={group}
          projectId={projectId}
          onAddressReview={onAddressReview}
        />
      ))}
      {Object.entries(byRun).map(([runId, group]) => (
        <PrGroup
          key={`run-${runId}`}
          label='Run'
          idShort={runId.substring(0, 8)}
          to={`/runs/${runId}`}
          prs={group}
          projectId={projectId}
          onAddressReview={onAddressReview}
        />
      ))}
      {Object.entries(bySystemKind).map(([kind, group]) => (
        <PrGroup
          key={`system-${kind}`}
          label={SYSTEM_KIND_LABEL[kind] ?? kind}
          idShort={`${group.length} PR${group.length > 1 ? 's' : ''}`}
          to={null}
          prs={group}
          projectId={projectId}
          onAddressReview={onAddressReview}
        />
      ))}
    </div>
  );
}

/** Display labels for the V169 system-PR kinds. */
const SYSTEM_KIND_LABEL: Record<string, string> = {
  BOOTSTRAP: 'Bootstrap',
  ARCHITECTURE: 'Architecture',
  CONTRACT: 'Contract',
  CHAT_TOOL: 'Chat tool',
  SYSTEM: 'System',
};

function PrGroup({
  label,
  idShort,
  to,
  prs,
  projectId,
  onAddressReview,
}: {
  label: string;
  idShort: string;
  /** Target route for the group header; null when the label is its own group key (system PRs). */
  to: string | null;
  prs: PrRollupEntry[];
  projectId: string;
  onAddressReview?: (config: ContinueOnPrConfig) => void;
}) {
  return (
    <div>
      <div className='flex items-center gap-2 mb-1 text-xs text-muted'>
        <span>{label}</span>
        {to ? (
          <Link to={to} className='font-mono text-accent-blue hover:underline'>
            {idShort}
          </Link>
        ) : (
          <span className='font-mono'>{idShort}</span>
        )}
        <span>
          · {prs.length} PR{prs.length > 1 ? 's' : ''}
        </span>
      </div>
      <div className='space-y-1'>
        {prs.map((pr) => (
          <PrRow key={pr.prUrl} pr={pr} projectId={projectId} onAddressReview={onAddressReview} />
        ))}
      </div>
    </div>
  );
}

function statusPill(status: string): string {
  if (status === 'MERGED')
    return 'text-[11px] px-1.5 py-0.5 rounded border text-status-success border-status-success/40';
  if (status === 'CLOSED')
    return 'text-[11px] px-1.5 py-0.5 rounded border text-muted border-[var(--border-default)]';
  return 'text-[11px] px-1.5 py-0.5 rounded border text-accent-blue border-accent-blue/40';
}

/**
 * CI rollup chip — only renders for SUCCESS / PENDING / FAILURE. UNKNOWN +
 * NEUTRAL collapse to no chip in the caller. Pre-existing `text-status-*` /
 * `text-muted` token classes are reused so light + dark themes stay coherent.
 */
function ciPill(status: 'SUCCESS' | 'PENDING' | 'FAILURE'): string {
  const base = 'text-[11px] px-1.5 py-0.5 rounded border font-mono';
  if (status === 'FAILURE')
    return `${base} text-status-error border-status-error/40 bg-status-error/5`;
  if (status === 'PENDING') return `${base} text-muted border-[var(--border-default)]`;
  return `${base} text-status-success border-status-success/40`;
}

function ciTooltip(
  status: 'SUCCESS' | 'PENDING' | 'FAILURE',
  failingCount: number,
  checkedAt: string | null,
): string {
  const head =
    status === 'FAILURE'
      ? `${failingCount} failing check${failingCount === 1 ? '' : 's'}`
      : status === 'PENDING'
        ? 'CI still running'
        : 'All checks passed';
  if (!checkedAt) return head;
  const when = new Date(checkedAt);
  const ageMs = Date.now() - when.getTime();
  const ageMin = Math.max(0, Math.round(ageMs / 60_000));
  const fresh =
    ageMin < 1 ? 'just now' : ageMin < 60 ? `${ageMin}m ago` : `${Math.round(ageMin / 60)}h ago`;
  return `${head} · as of ${fresh}`;
}

function PrRow({
  pr,
  projectId,
  onAddressReview,
}: {
  pr: PrRollupEntry;
  projectId: string;
  onAddressReview?: (config: ContinueOnPrConfig) => void;
}) {
  const [open, setOpen] = useState(false);
  const [snap, setSnap] = useState<PullRequestSnapshot | null>(null);
  const [loading, setLoading] = useState(false);
  const [snapError, setSnapError] = useState<string | null>(null);
  const [fetchingReview, setFetchingReview] = useState(false);

  const toggle = async () => {
    const next = !open;
    setOpen(next);
    if (!next || snap || loading || !pr.repoFullName || !pr.prNumber) return;
    setLoading(true);
    setSnapError(null);
    try {
      const s = await getPullRequestSnapshot(pr.repoFullName, pr.prNumber);
      setSnap(s);
    } catch (e) {
      setSnapError(e instanceof Error ? errMsg(e) : 'Failed to load PR');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className='rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)]'>
      <div className='flex items-center gap-2 px-3 py-1.5 text-xs'>
        <button
          onClick={toggle}
          disabled={!pr.repoFullName || !pr.prNumber}
          className='text-muted hover:text-primary disabled:opacity-30'
          title={
            !pr.repoFullName ? 'Preview available only for project-linked repos' : 'Preview PR'
          }
        >
          {open ? <ChevronDown className='h-3 w-3' /> : <ChevronRight className='h-3 w-3' />}
        </button>
        <span className={statusPill(pr.status)}>{pr.status}</span>
        <span className='font-mono'>#{pr.prNumber ?? '—'}</span>
        <span className='truncate flex-1 font-mono'>{pr.repoFullName ?? pr.prUrl}</span>
        {/* 2026-05-11 (V186) — CI rollup chip. Only renders when the last
            refresh observed a meaningful signal (FAILURE / PENDING / SUCCESS);
            UNKNOWN + NEUTRAL stay silent so the row doesn't show a chip on
            PRs without CI configured. Hover-title carries the "as of …"
            timestamp + failing count so a quick glance is enough for ops. */}
        {pr.ciStatus && pr.ciStatus !== 'UNKNOWN' && pr.ciStatus !== 'NEUTRAL' && (
          <span
            className={ciPill(pr.ciStatus)}
            title={ciTooltip(pr.ciStatus, pr.ciFailingCount ?? 0, pr.ciCheckedAt ?? null)}
          >
            {pr.ciStatus === 'FAILURE'
              ? `CI ✗${(pr.ciFailingCount ?? 0) > 1 ? ` ${pr.ciFailingCount}` : ''}`
              : pr.ciStatus === 'PENDING'
                ? 'CI …'
                : 'CI ✓'}
          </span>
        )}
        {pr.status === 'OPEN' && pr.repoFullName && pr.prNumber && onAddressReview && (
          <button
            onClick={async (e) => {
              e.stopPropagation();
              if (fetchingReview) return;
              setFetchingReview(true);
              try {
                const {comments} = await fetchPrReviewComments(
                  projectId,
                  // eslint-disable-next-line @typescript-eslint/no-non-null-assertion
                  pr.prNumber!,
                  // eslint-disable-next-line @typescript-eslint/no-non-null-assertion
                  pr.repoFullName!,
                );
                const headBranch =
                  snap?.headBranch ??
                  // eslint-disable-next-line @typescript-eslint/no-non-null-assertion
                  (await getPullRequestSnapshot(pr.repoFullName!, pr.prNumber!)).headBranch ??
                  '';
                // Address-review only fires for run/launch PRs (filtered upstream by
                // !!p.repoFullName + kind=RUN|undefined), so launchId is always set here.
                // The non-null assertion mirrors prNumber/repoFullName above.
                onAddressReview({
                  // eslint-disable-next-line @typescript-eslint/no-non-null-assertion
                  sourceExecutionId: pr.launchId!,
                  // eslint-disable-next-line @typescript-eslint/no-non-null-assertion
                  prNumber: pr.prNumber!,
                  // eslint-disable-next-line @typescript-eslint/no-non-null-assertion
                  repoFullName: pr.repoFullName!,
                  headBranch,
                  reviewComments: comments || '(no review comments found)',
                });
              } finally {
                setFetchingReview(false);
              }
            }}
            disabled={fetchingReview}
            className='text-muted hover:text-accent-blue inline-flex items-center gap-1 text-[11px]'
            title='Address PR review comments with a follow-up Launch'
          >
            {fetchingReview ? (
              <Loader2 className='h-3 w-3 animate-spin' />
            ) : (
              <MessageSquareReply className='h-3 w-3' />
            )}
          </button>
        )}
        {pr.repoFullName && pr.prNumber && (
          <PrReviewButton
            projectId={projectId}
            repoFullName={pr.repoFullName}
            prNumber={pr.prNumber}
          />
        )}
        <a
          href={pr.prUrl}
          target='_blank'
          rel='noreferrer'
          className='text-muted hover:text-accent-blue inline-flex items-center gap-1'
          title='Open on GitHub'
        >
          <ExternalLink className='h-3 w-3' />
        </a>
      </div>
      {open && (
        <div className='border-t border-[var(--border-default)] px-3 py-2 space-y-2 text-xs'>
          {loading && (
            <div className='flex items-center gap-1 text-muted'>
              <Loader2 className='h-3 w-3 animate-spin' /> Fetching PR from GitHub…
            </div>
          )}
          {snapError && <div className='text-accent-red'>{snapError}</div>}
          {snap && <PrPreview snap={snap} />}
          {!loading && !snap && !snapError && (
            <div className='text-muted'>No preview available.</div>
          )}
        </div>
      )}
    </div>
  );
}

/**
 * v2.6.4 user-feedback fix #2 — Per-file sidebar inside the expanded PR.
 *
 * The backend snapshot returns one big `diffPreview` blob with every file's
 * patch concatenated. We split it on `diff --git a/<x> b/<y>` boundaries
 * so the preview can show a GitHub-style left rail (filename + ±LoC) on
 * the left + the per-file diff on the right.
 *
 * Click a filename → that file's diff scrolls into view (anchor scroll).
 */
interface FileDiff {
  filename: string;
  additions: number;
  deletions: number;
  patch: string;
}

function splitDiffByFile(diffText: string): FileDiff[] {
  if (!diffText) return [];
  // `diff --git a/<filename> b/<filename>` is the file boundary marker
  // produced by `git diff` and GitHub's patch endpoint. We split on that
  // and then extract the right-hand filename from the marker.
  const parts = diffText.split(/(?=^diff --git )/m).filter((p) => p.trim());
  return parts.map((patch) => {
    const headerMatch = patch.match(/^diff --git a\/(.+?) b\/(.+?)\s*$/m);
    const filename = headerMatch ? headerMatch[2] : '(unknown)';
    let additions = 0;
    let deletions = 0;
    const lines = patch.split('\n');
    for (const ln of lines) {
      if (ln.startsWith('+') && !ln.startsWith('+++')) additions++;
      else if (ln.startsWith('-') && !ln.startsWith('---')) deletions++;
    }
    return {filename, additions, deletions, patch};
  });
}

function PrPreview({snap}: {snap: PullRequestSnapshot}) {
  const files = useMemo(() => splitDiffByFile(snap.diffPreview || ''), [snap.diffPreview]);
  const [selected, setSelected] = useState<number | null>(files.length > 0 ? 0 : null);

  return (
    <div className='space-y-2'>
      {snap.title && <div className='text-xs text-primary font-medium'>{snap.title}</div>}
      <div className='flex flex-wrap gap-x-3 gap-y-1 text-[11px] text-muted font-mono'>
        <span>
          branch: <span className='text-secondary'>{snap.headBranch}</span> →{' '}
          <span className='text-secondary'>{snap.baseBranch}</span>
        </span>
        <span>
          files:{' '}
          <span className={cn(snap.changedFiles > 0 ? 'text-primary' : 'text-muted')}>
            {snap.changedFiles}
          </span>
        </span>
        <span className='text-status-success'>+{snap.additions}</span>
        <span className='text-accent-red'>−{snap.deletions}</span>
      </div>
      {snap.body && snap.body.trim() && (
        <pre className='text-xs whitespace-pre-wrap text-secondary bg-[var(--gray-200)] rounded-sm px-2 py-1.5 max-h-32 overflow-y-auto'>
          {snap.body}
        </pre>
      )}

      {/* GitHub-style 2-pane: file rail (left) + selected file diff (right).
          Phones (<sm=640px): single column, file rail above the diff with
          its own scroll cap so it doesn't push the diff offscreen.
          Tablet+ (sm=640px+): side-by-side 220px rail + flexible diff.
          Falls back to the legacy single-blob DiffView when the diff
          couldn't be split (e.g. backend returned a non-git format). */}
      {files.length > 1 && selected != null ? (
        <div className='grid grid-cols-1 sm:grid-cols-[180px_minmax(0,1fr)] md:grid-cols-[220px_minmax(0,1fr)] gap-2'>
          <aside className='border border-[var(--border-default)] rounded-sm bg-[var(--gray-200)] max-h-80 overflow-y-auto'>
            <div className='px-2 py-1 text-[10px] font-mono uppercase tracking-wider text-muted border-b border-[var(--border-default)]'>
              Files ({files.length})
            </div>
            <ul>
              {files.map((f, i) => (
                <li key={i}>
                  <button
                    onClick={() => setSelected(i)}
                    className={cn(
                      'w-full text-left px-2 py-1 text-[11px] flex items-center gap-1.5 transition-colors',
                      i === selected
                        ? 'bg-accent-blue/10 text-accent-blue border-l-2 border-accent-blue'
                        : 'text-secondary hover:bg-[var(--gray-100)] border-l-2 border-transparent',
                    )}
                    title={f.filename}
                  >
                    <FileCode2 className='h-3 w-3 flex-shrink-0' />
                    <span className='truncate flex-1 font-mono'>{shortenPath(f.filename)}</span>
                    <span className='font-mono text-[10px] tabular-nums whitespace-nowrap'>
                      <span className='text-status-success'>+{f.additions}</span>
                      <span className='text-accent-red ml-0.5'>−{f.deletions}</span>
                    </span>
                  </button>
                </li>
              ))}
            </ul>
          </aside>
          <div className='min-w-0'>
            <div className='px-2 py-1 text-[10px] font-mono text-muted truncate'>
              {files[selected].filename}
            </div>
            <DiffView diff={files[selected].patch} />
          </div>
        </div>
      ) : (
        snap.diffPreview && <DiffView diff={snap.diffPreview} />
      )}
    </div>
  );
}

/**
 * Truncate `src/main/java/com/.../Foo.java` to `…/Foo.java` style for the
 * narrow rail. Keep the basename + parent dir when possible.
 */
function shortenPath(p: string): string {
  const segments = p.split('/');
  if (segments.length <= 2) return p;
  return `…/${segments[segments.length - 2]}/${segments[segments.length - 1]}`;
}
