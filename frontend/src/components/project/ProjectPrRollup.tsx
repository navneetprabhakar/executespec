import {useEffect, useMemo, useState} from 'react';
import {Link, useNavigate} from 'react-router-dom';
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
import DiffView from '@/components/shared/DiffView';
import {cn} from '@/lib/utils';

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

  const load = () =>
    executionApi
      .listProjectPrs(projectId)
      .then(setPrs)
      .catch((e: any) => setError(e?.message ?? 'Failed to load PRs'));

  useEffect(() => {
    load();
  }, [projectId]);

  const onRefresh = async () => {
    setRefreshing(true);
    setError(null);
    try {
      const r = await refreshProjectPrs(projectId);
      setLastRefresh(
        r.newlyMerged > 0
          ? `${r.newlyMerged} merged · ${r.updated}/${r.checked} updated`
          : `${r.updated}/${r.checked} updated`,
      );
      await load();
    } catch (e: any) {
      setError(e?.message ?? 'Refresh failed');
    } finally {
      setRefreshing(false);
    }
  };

  if (error) return <div className='text-xs text-accent-red'>{error}</div>;
  if (prs == null) return <div className='text-xs text-muted'>Loading PRs…</div>;

  const header = (
    <div className='flex items-center justify-between mb-2'>
      <h3 className='text-xs font-medium text-secondary'>Pull requests</h3>
      <div className='flex items-center gap-2'>
        {lastRefresh && <span className='text-[11px] text-muted'>{lastRefresh}</span>}
        <button
          onClick={onRefresh}
          disabled={refreshing}
          className='inline-flex items-center gap-1 text-[11px] font-medium px-2 py-0.5 rounded-sm border border-accent-blue/40 bg-accent-blue/10 text-accent-blue hover:bg-accent-blue/20 disabled:opacity-50'
          title='Poll GitHub for latest PR status; auto-analyse on any new merge'
        >
          {refreshing ? (
            <Loader2 className='h-3 w-3 animate-spin' />
          ) : (
            <RefreshCw className='h-3 w-3' />
          )}
          Refresh
        </button>
      </div>
    </div>
  );

  if (prs.length === 0) {
    return (
      <div>
        {header}
        <div className='text-xs text-muted'>No pull requests yet.</div>
      </div>
    );
  }

  const launchEntries = prs.filter((p) => !p.repoFullName);
  const runEntries = prs.filter((p) => !!p.repoFullName);

  const byLaunch: Record<string, PrRollupEntry[]> = {};
  launchEntries.forEach((p) => {
    (byLaunch[p.launchId] ??= []).push(p);
  });
  const byRun: Record<string, PrRollupEntry[]> = {};
  runEntries.forEach((p) => {
    (byRun[p.launchId] ??= []).push(p);
  });

  return (
    <div className='space-y-4'>
      {header}
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
    </div>
  );
}

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
  to: string;
  prs: PrRollupEntry[];
  projectId: string;
  onAddressReview?: (config: ContinueOnPrConfig) => void;
}) {
  return (
    <div>
      <div className='flex items-center gap-2 mb-1 text-xs text-muted'>
        <span>{label}</span>
        <Link to={to} className='font-mono text-accent-blue hover:underline'>
          {idShort}
        </Link>
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
      setSnapError(e instanceof Error ? e.message : 'Failed to load PR');
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
        {pr.status === 'OPEN' && pr.repoFullName && pr.prNumber && onAddressReview && (
          <button
            onClick={async (e) => {
              e.stopPropagation();
              if (fetchingReview) return;
              setFetchingReview(true);
              try {
                const {comments} = await fetchPrReviewComments(
                  projectId,
                  pr.prNumber!,
                  pr.repoFullName!,
                );
                const headBranch =
                  snap?.headBranch ??
                  (await getPullRequestSnapshot(pr.repoFullName!, pr.prNumber!)).headBranch ??
                  '';
                onAddressReview({
                  sourceExecutionId: pr.launchId,
                  prNumber: pr.prNumber!,
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
